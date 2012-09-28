package com.comcast.xfinity.sirius.api.impl.paxos

import akka.actor.Actor
import akka.actor.ActorRef
import akka.event.Logging
import akka.util.duration._
import com.comcast.xfinity.sirius.api.impl.paxos.PaxosMessages._
import com.comcast.xfinity.sirius.api.SiriusConfiguration
import com.comcast.xfinity.sirius.admin.MonitoringHooks
import annotation.tailrec
import com.comcast.xfinity.sirius.api.impl.paxos.Replica.Reap
import com.comcast.xfinity.sirius.util.RichJTreeMap

object Replica {

  case object Reap

  /**
   * Clients must implement a function of this type and pass it in on
   * construction.  The function takes a Decision and should perform
   * any operation necessary to handle the decision.  Decisions may
   * arrive out of order and multiple times.  It is the responsibility
   * of the implementer to handle these cases.  Additionally, it is the
   * responsibility of the implementer to reply to client identified
   * by Decision.command.client.
   */
  type PerformFun = Decision => Unit

  /**
   * Create a Replica instance.
   *
   * The performFun argument must apply the operation, and return true indicating
   * that the operation was successfully performed/acknowledged, or return false
   * indicating that the operation was ignored.  When true is returned the initiating
   * actor of this request is sent the RequestPerformed message.  It is expected that
   * there is one actor per request.  When false is returned no such message is sent.
   * The reason for this is that multiple decisions may arrive for an individual slot.
   * While not absolutely necessary, this helps reduce chatter.
   *
   * Note this should be called from within a Props factory on Actor creation
   * due to the requirements of Akka.
   *
   * @param localLeader reference of replica's local {@link Leader}
   * @param performFun function specified by
   *          [[com.comcast.xfinity.sirius.api.impl.paxos.Replica.PerformFun]], applied to
   *          decisions as they arrive
   * @param config SiriusConfiguration to pass in arbitrary config,
   *          @see SiriusConfiguration for more information
   */
  def apply(localLeader: ActorRef,
            startingSlotNum: Long,
            performFun: PerformFun,
            config: SiriusConfiguration): Replica = {

    val reapFreqSecs = config.getProp(SiriusConfiguration.REPROPOSAL_CLEANUP_FREQ, 1)
    val reproposalWindowSecs = config.getProp(SiriusConfiguration.REPROPOSAL_WINDOW, 10)

    new Replica(localLeader, startingSlotNum, performFun, reproposalWindowSecs)(config) {
      val reapCancellable =
        context.system.scheduler.schedule(reapFreqSecs seconds,
                                          reapFreqSecs seconds, self, Reap)
      override def preStart() {
        registerMonitor(new ReplicaInfo, config)
      }
      override def postStop() {
        unregisterMonitors(config)
        reapCancellable.cancel()
      }
    }
  }
}

class Replica(localLeader: ActorRef,
              startingSlotNum: Long,
              performFun: Replica.PerformFun,
              reproposalWindowSecs: Int)
             (implicit config: SiriusConfiguration = new SiriusConfiguration) extends Actor with MonitoringHooks {

  var slotNum = startingSlotNum
  val outstandingProposals = new RichJTreeMap[Long, Command]()
  val decisions = new RichJTreeMap[Long, Command]()

  val logger = Logging(context.system, "Sirius")
  val traceLogger = Logging(context.system, "SiriusTrace")

  // XXX for monitoring...
  var lastProposed = ""
  var numProposed = 0
  var lastDuration = 0L
  var longestDuration = 0L

  def receive = {
    case Request(command: Command) =>
      propose(command)

    case decision @ Decision(slot, decisionCommand) =>
      traceLogger.debug("Received decision slot {} for {}", slot, decisionCommand)

      decisions.put(slot, decisionCommand)
      reproposeIfClobbered(slot, decisionCommand)

      try {
        performFun(decision)
      } catch {
        // XXX: is this too liberal?
        case t: Throwable =>
          logger.error("Received exception applying decision {}: {}", decision, t)
      }

    case decisionHint @ DecisionHint(decisionHintSlotNum) =>
      slotNum = decisionHintSlotNum + 1

      outstandingProposals.filter((k, _) => k > decisionHintSlotNum)
      decisions.filter((k, _) => k > decisionHintSlotNum)

      localLeader forward decisionHint

    case Reap =>
      reapStagnantProposals()
  }

  /**
   * Propose a command to the local leader, either from a new Request or due
   * to a triggered reproposal.
   *
   * Has side-effect of adding proposal to proposals map.
   * @param command Command to be proposed
   */
  private def propose(command: Command) {
    val nextSlotNum = nextAvailableSlotNum

    localLeader ! Propose(nextSlotNum, command)
    outstandingProposals.put(nextSlotNum, command)

    logProposal(nextSlotNum, command)
  }

  @tailrec
  private def findNextAvailableSlotNum(minSlotNum: Long): Long = {
    if (outstandingProposals.containsKey(minSlotNum) || decisions.containsKey(minSlotNum)) {
      findNextAvailableSlotNum(minSlotNum + 1)
    } else {
      minSlotNum
    }
  }

  private[paxos] def nextAvailableSlotNum = findNextAvailableSlotNum(slotNum)

  /**
   * Check whether the decided command is one of the following:
   * - our proposal, in which case we can remove it from proposal list (succeeded)
   * - someone else's proposal, in which case we need to repropose our old proposal
   * - for a slot we haven't seen, in which case we do nothing
   *
   * @param slot slot number for this command
   * @param decisionCommand command that has been decided for the slot number
   * @return
   */
  private def reproposeIfClobbered(slot: Long, decisionCommand: Command) {
    outstandingProposals.remove(slot) match {
      case proposalCommand: Command if decisionCommand != proposalCommand =>
        traceLogger.debug("Must repropose, slot {} conflict.  decisionCommand: {}, proposalCommand: {}", slot, decisionCommand.op, proposalCommand.op)
        propose(proposalCommand)
      case _ =>
    }
  }

  private def logProposal(nextSlotNum: Long, command: PaxosMessages.Command) {
    numProposed += 1
    lastProposed = "Proposing slot %s for %s".format(nextSlotNum, command)
    traceLogger.debug(lastProposed)
  }

  private def reapStagnantProposals() {
    val cutoff = System.currentTimeMillis() - reproposalWindowSecs * 1000
    outstandingProposals.filter((_, v) => v.ts >= cutoff)
  }

  /**
   * Monitoring hooks
   */
  trait ReplicaInfoMBean {
    def getProposalsSize: Int
    def getNextAvailableSlotNum: Long
    def getLastProposed: String
    def getNumProposed: Int
    def getLastDuration: Long
    def getLongestDuration: Long
  }

  class ReplicaInfo extends ReplicaInfoMBean {
    def getProposalsSize = outstandingProposals.size
    def getNextAvailableSlotNum = nextAvailableSlotNum
    def getLastProposed = lastProposed
    def getNumProposed = numProposed
    def getLastDuration = lastDuration
    def getLongestDuration = longestDuration
  }
}
