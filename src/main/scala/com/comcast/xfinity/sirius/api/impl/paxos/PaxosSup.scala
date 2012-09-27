package com.comcast.xfinity.sirius.api.impl.paxos

import akka.agent.Agent
import com.comcast.xfinity.sirius.api.impl.paxos.PaxosMessages._
import akka.actor.{Props, ActorRef, Actor}
import com.comcast.xfinity.sirius.api.impl.NonCommutativeSiriusRequest
import akka.event.Logging
import com.comcast.xfinity.sirius.api.SiriusConfiguration

object PaxosSup {

  /**
   * A class for injecting children into a PaxosSup
   */
  trait ChildProvider {
    val leader: ActorRef
    val acceptor: ActorRef
    val replica: ActorRef
  }

  /**
   * Create a PaxosSup instance.  Note this should be called from within a Props
   * factory on Actor creation due to the requirements of Akka.
   *
   * @param membership an {@link akka.agent.Agent} tracking the membership of the cluster
   * @param startingSeqNum the sequence number at which this node will begin issuing/acknowledging
   * @param performFun function specified by
   *          [[com.comcast.xfinity.sirius.api.impl.paxos.Replica.PerformFun]], applied to
   *          decisions as they arrive
   */
  def apply(membership: Agent[Set[ActorRef]],
            startingSeqNum: Long,
            performFun: Replica.PerformFun,
            config: SiriusConfiguration): PaxosSup = {
    new PaxosSup with ChildProvider {
      val leader = context.actorOf(Props(
        Leader(membership, startingSeqNum, config)), "leader"
      )
      val acceptor = context.actorOf(Props(
        Acceptor(startingSeqNum, config)), "acceptor"
      )
      val replica = context.actorOf(Props(
        Replica(leader, startingSeqNum, performFun, config)), "replica"
      )
    }
  }
}

class PaxosSup extends Actor {
  this: PaxosSup.ChildProvider =>

  val traceLog = Logging(context.system, "SiriusTrace")

  def receive = {
    // Replica messages
    case req: NonCommutativeSiriusRequest =>
      traceLog.debug("Received event for submission {}", req)
      val command = Command(sender, System.currentTimeMillis(), req)
      replica forward Request(command)
    case d: Decision => replica forward d
    case dh: DecisionHint => replica forward  dh

    // Leader messages
    case p: Propose => leader forward p
    // Adopted and Preempted are internal
    // Acceptor messages
    case p1a: Phase1A => acceptor forward p1a
    case p2A: Phase2A => acceptor forward p2A
    // Phase1B and Phase2B are direct addressed
  }
}
