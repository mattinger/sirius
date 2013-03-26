package com.comcast.xfinity.sirius.api.impl.paxos

import com.comcast.xfinity.sirius.api.impl.paxos.PaxosMessages._
import akka.actor.{Props, Actor, ActorRef}
import akka.agent.Agent
import akka.event.Logging
import com.comcast.xfinity.sirius.api.SiriusConfiguration
import com.comcast.xfinity.sirius.admin.MonitoringHooks
import com.comcast.xfinity.sirius.api.impl.paxos.LeaderPinger.{Pong, Ping}
import com.comcast.xfinity.sirius.api.impl.paxos.LeaderWatcher.{SeekLeadership, Close}
import com.comcast.xfinity.sirius.util.{RichJTreeMap, AkkaExternalAddressResolver}

object Leader {
  trait HelperProvider {
    val leaderHelper: LeaderHelper
    def startCommander(pval: PValue, ticks: Int = 0): Unit
    def startScout(): Unit
  }

  def apply(membership: Agent[Set[ActorRef]],
            startingSeqNum: Long,
            config: SiriusConfiguration): Leader = {

    //XXX make configurable!
    val defaultRetries = 2

    new Leader(membership, startingSeqNum)(config) with HelperProvider {
      val leaderHelper = new LeaderHelper()

      def startCommander(pval: PValue, ticks: Int = defaultRetries) {
        // XXX: more members may show up between when acceptors() and replicas(),
        //      we may want to combine the two, and just reference membership
        context.actorOf(Props(new Commander(self, acceptors(), replicas(), pval, ticks)))
      }

      def startScout() {
        context.actorOf(Props(new Scout(self, acceptors(), myBallot, latestDecidedSlot)))
      }
    }
  }
}

class Leader(membership: Agent[Set[ActorRef]],
             startingSeqNum: Long)
            (implicit config: SiriusConfiguration = new SiriusConfiguration)
      extends Actor with MonitoringHooks {
    this: Leader.HelperProvider =>

  val logger = Logging(context.system, "Sirius")
  val traceLogger = Logging(context.system, "SiriusTrace")

  val acceptors = membership
  val replicas = membership

  val myLeaderId = AkkaExternalAddressResolver(context.system).externalAddressFor(self)
  var myBallot = Ballot(0, myLeaderId)
  var proposals = RichJTreeMap[Long, Command]()

  var latestDecidedSlot: Long = startingSeqNum - 1

  var electedLeaderBallot: Option[Ballot] = None
  var currentLeaderWatcher: Option[ActorRef] = None

  // XXX for monitoring...
  var longestReapDuration = 0L
  var lastReapDuration = 0L
  var currentLeaderElectedSince = 0L
  var commanderTimeoutCount = 0L
  var lastTimedOutPValue: Option[PValue] = None

  startScout()

  override def preStart() {
    registerMonitor(new LeaderInfo, config)
  }

  override def postStop() {
    unregisterMonitors(config)
  }

  def receive = {
    case propose @ Propose(slotNum, command) if !proposals.containsKey(slotNum) && slotNum > latestDecidedSlot =>
      electedLeaderBallot match {
        // I'm the leader
        case Some(electedBallot) if (myBallot == electedBallot) =>
          proposals.put(slotNum, command)
          startCommander(PValue(myBallot, slotNum, command))

        // someone else is the leader
        case Some(electedBallot @ Ballot(_, leaderId)) if (myBallot != electedBallot) =>
          context.actorFor(leaderId) forward propose

        // the leader is unknown, stash proposals until we know
        case None =>
          proposals.put(slotNum, command)
      }


    // A majority of the Acceptors have accepted myBallot, become leader
    case Adopted(newBallot, pvals) if myBallot == newBallot =>
      logger.debug("Assuming leadership using {}", myBallot)

      // XXX: update actually has side effects, however this assignment
      //      is necessary for testing, we use it so that we can mock
      //      the leaderHelper without needing to use "andAnswer", or whatever.
      //      Eventually we should consider moving the leaderHelper stuff into
      //      the leader itself again...
      proposals = leaderHelper.update(proposals, leaderHelper.pmax(pvals))
      proposals.foreach(
        (slot, command) => startCommander(PValue(myBallot, slot, command))
      )
      currentLeaderElectedSince = System.currentTimeMillis()
      electedLeaderBallot = Some(myBallot)


    // phantom ballot from the future- this node was the leader in some previous
    // life and other nodes still believe it, try to become leader again but using
    // a bigger ballot
    case Preempted(newBallot) if newBallot > myBallot && newBallot.leaderId == myLeaderId =>
      seekLeadership(Some(newBallot))

    // there's a new leader, update electedLeaderBallot and start a new watcher accordingly
    case Preempted(newBallot) if newBallot > myBallot =>
      logger.debug("Becoming subservient to new leader with ballot {}", newBallot)
      currentLeaderElectedSince = System.currentTimeMillis()
      electedLeaderBallot = Some(newBallot)
      val electedLeader = context.actorFor(newBallot.leaderId)
      proposals.foreach(
        (slot, command) => electedLeader ! Propose(slot, command)
      )
      startLeaderWatcher(newBallot)


    // try to become the new leader; old leader has gone MIA
    case SeekLeadership => seekLeadership(electedLeaderBallot)


    // respond to Ping from LeaderPinger with our current leader ballot information
    case Ping => sender ! Pong(electedLeaderBallot)


    // if our scout fails to make progress, and we have not since elected a leader,
    //  try again
    case ScoutTimeout if electedLeaderBallot == None => startScout()


    // if the commander times out we nullify it's slot in our proposals
    //  and let someone else try out
    case Commander.CommanderTimeout(pvalue, ticks) =>
      traceLogger.debug("Commander timed out for {}", pvalue)

      if (ticks > 0) {
        traceLogger.debug("Restarting commander for {}, {} ticks left", pvalue, ticks - 1)
        startCommander(pvalue, ticks - 1)
      } else {
        proposals.remove(pvalue.slotNum)
      }

      // some record keeping
      commanderTimeoutCount += 1
      lastTimedOutPValue = Some(pvalue)


    // the SirusPaxosBridge will notify the Leader of the last decision.  We can then use this to reduce the number
    // of accepted decisions we need from the Acceptor
    case DecisionHint(lastSlot) =>
      latestDecidedSlot = lastSlot
      reapProposals()

  }

  private def seekLeadership(ballotToTrump: Option[Ballot] = None) {
    myBallot = ballotToTrump match {
      case Some(Ballot(seq, _)) => myBallot.copy(seq = seq + 1)
      case _ => myBallot.copy(seq = myBallot.seq + 1)
    }

    electedLeaderBallot = None

    startScout()
  }

  private def stopLeaderWatcher() {
    currentLeaderWatcher match {
      case Some(ref) if (!ref.isTerminated) => ref ! LeaderWatcher.Close
      case _ => // no-op
    }
    currentLeaderWatcher = None
  }

  private def startLeaderWatcher(ballotToWatch: Ballot) {
    stopLeaderWatcher()
    currentLeaderWatcher = Some(context.actorOf(Props(new LeaderWatcher(ballotToWatch, self))))
  }

  // drops all proposals held locally whos slot is <= latestDecidedSlot
  private def reapProposals() {
    val start = System.currentTimeMillis
    proposals.dropWhile(
      (slot, _) => slot <= latestDecidedSlot
    )
    val duration = System.currentTimeMillis() - start

    logger.debug("Reaped old proposals up to {} in {}ms", latestDecidedSlot, duration)

    lastReapDuration = duration
    if (duration > longestReapDuration)
      longestReapDuration = duration
  }

  // monitoring hooks, to close over the scope of the class, it has to be this way
  //  because of jmx
  trait LeaderInfoMBean {
    def getBallot: String
    def getLatestDecidedSlot: Long
    def getProposalCount: Int
    def getElectedLeaderBallot: String
    def getCurrentLeaderElectedSince: Long
    def getLongestReapDuration: Long
    def getLastReapDuration: Long
    def getCommanderTimeoutCount: Long
    def getLastTimedOutPValue: String
  }

  class LeaderInfo extends LeaderInfoMBean{
    def getBallot = myBallot.toString
    def getLatestDecidedSlot = latestDecidedSlot
    def getProposalCount = proposals.size
    def getElectedLeaderBallot = electedLeaderBallot.toString
    def getCurrentLeaderElectedSince = currentLeaderElectedSince
    def getLongestReapDuration = longestReapDuration
    def getLastReapDuration = lastReapDuration
    def getCommanderTimeoutCount = commanderTimeoutCount
    def getLastTimedOutPValue = lastTimedOutPValue.toString
  }
}
