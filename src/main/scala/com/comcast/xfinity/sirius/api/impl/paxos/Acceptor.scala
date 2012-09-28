package com.comcast.xfinity.sirius.api.impl.paxos

import akka.actor.Actor
import com.comcast.xfinity.sirius.api.impl.paxos.PaxosMessages._
import akka.util.duration._
import akka.event.Logging
import java.util.{TreeMap => JTreeMap}
import scala.util.control.Breaks._
import com.comcast.xfinity.sirius.api.SiriusConfiguration
import collection.immutable.HashSet
import collection.mutable.SetBuilder
import com.comcast.xfinity.sirius.admin.MonitoringHooks

object Acceptor {

  case object Reap

  def apply(startingSeqNum: Long, config: SiriusConfiguration): Acceptor = {
    val reapWindow = config.getProp(SiriusConfiguration.ACCEPTOR_WINDOW, 10 * 60 * 1000L)
    val reapFreqSecs = config.getProp(SiriusConfiguration.ACCEPTOR_CLEANUP_FREQ, 30)

    new Acceptor(startingSeqNum, reapWindow)(config) {
      val reapCancellable =
        context.system.scheduler.schedule(reapFreqSecs seconds, reapFreqSecs seconds, self, Reap)

      override def preStart() {
        registerMonitor(new AcceptorInfo, config)
      }
      override def postStop() {
        unregisterMonitors(config)
        reapCancellable.cancel()
      }
    }
  }
}

class Acceptor(startingSeqNum: Long,
               reapWindow: Long = 10 * 60 * 1000L)
              (implicit config: SiriusConfiguration = new SiriusConfiguration) extends Actor with MonitoringHooks {

  import Acceptor._

  val logger = Logging(context.system, "Sirius")
  val traceLogger = Logging(context.system, "SiriusTrace")

  var ballotNum: Ballot = Ballot.empty

  // XXX for monitoring...
  var lastDuration = 0L
  var longestDuration = 0L

  // slot -> (ts,PValue)
  var accepted = new JTreeMap[Long, Tuple2[Long, PValue]]()

  // if we receive a Phase2A for a slot less than this we refuse to
  // handle it since it is out of date by our terms
  var lowestAcceptableSlotNumber: Long = startingSeqNum

  // Note that Phase1A and Phase2B requests must return their
  // parent's address (their PaxosSup) because this is how
  // the acceptor is known to external nodes
  def receive = {
    // Scout
    case Phase1A(scout, ballot, replyAs, latestDecidedSlot) =>
      if (ballot > ballotNum) {
        ballotNum = ballot
      }

      scout ! Phase1B(replyAs, ballotNum, undecidedAccepted(latestDecidedSlot))

    // Commander
    case Phase2A(commander, pval, replyAs) if pval.slotNum >= lowestAcceptableSlotNumber =>
      if (pval.ballot >= ballotNum) {
        ballotNum = pval.ballot

        // if pval is already accepted on higher ballot number then just update the timestamp
        //    in other words
        // if pval not accepted or accepted and with lower or equal ballot number then replace accepted pval w/ pval
        // also update the ts w/ localtime in our accepted map for reaping
        accepted.get(pval.slotNum) match {
          case (_, oldPval) if oldPval.ballot > pval.ballot =>
            accepted.put(oldPval.slotNum, (System.currentTimeMillis, oldPval))
          case _ =>
            accepted.put(pval.slotNum, (System.currentTimeMillis, pval))
        }
      }
      commander ! Phase2B(replyAs, ballotNum)

    // Periodic cleanup
    case Reap =>
      logger.debug("Accepted count: {}", accepted.size)

      val start = System.currentTimeMillis
      cleanOldAccepted()
      val duration = System.currentTimeMillis - start

      lastDuration = duration
      if (duration > longestDuration)
        longestDuration = duration

      logger.debug("Reaped Old Accpeted in {}ms", System.currentTimeMillis-start)
  }

  /* Remove 'old' pvals from the system.  A pval is old if we got it farther in the past than our reap limit.
   * The timestamp in the tuple with the pval came from localtime when we received it in the Phase2A msg.
   *
   * Note: this method has the side-effect of modifying toReap.
   */
  private def cleanOldAccepted() {
    var highestReapedSlot: Long = lowestAcceptableSlotNumber - 1
    val reapBeforeTs = System.currentTimeMillis - reapWindow
    breakable {
      val keys = accepted.keySet.toArray
      for (i <- 0 to keys.size - 1) {
        val slot = keys(i)
        if (accepted.get(slot)._1 < reapBeforeTs) {
          highestReapedSlot = accepted.get(slot)._2.slotNum
          accepted.remove(slot)
        } else {
          break()
        }
      }
    }
    logger.debug("Reaped PValues for all commands between {} and {}", lowestAcceptableSlotNumber - 1, highestReapedSlot)
    lowestAcceptableSlotNumber = highestReapedSlot + 1
  }

  /**
   * produces an undecided accepted Set of PValues
   *
   */
  private def undecidedAccepted(latestDecidedSlot: Long): Set[PValue] = {
    var undecidedPValues = Set[PValue]()
    val iterator = accepted.keySet().iterator
    while (iterator.hasNext) {
      val slot = iterator.next
      if (slot > latestDecidedSlot) {
        undecidedPValues += accepted.get(slot)._2
      }
    }
    undecidedPValues
  }

  /**
   * Monitoring hooks
   */
  trait AcceptorInfoMBean {
    def getAcceptedSize: Int
    def getLowestAcceptableSlotNum: Long
    def getBallot: Ballot
    def getLastDuration: Long
    def getLongestDuration: Long
  }

  class AcceptorInfo extends AcceptorInfoMBean {
    def getAcceptedSize = accepted.size
    def getLowestAcceptableSlotNum = lowestAcceptableSlotNumber
    def getBallot = ballotNum
    def getLastDuration = lastDuration
    def getLongestDuration = longestDuration
  }
}