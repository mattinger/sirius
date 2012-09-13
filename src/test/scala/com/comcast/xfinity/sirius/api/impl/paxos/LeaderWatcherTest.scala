package com.comcast.xfinity.sirius.api.impl.paxos

import com.comcast.xfinity.sirius.NiceTest
import akka.testkit.{TestActorRef, TestProbe}
import akka.actor.{ActorSystem, ActorRef}
import com.comcast.xfinity.sirius.api.impl.paxos.LeaderWatcher._
import com.comcast.xfinity.sirius.api.impl.paxos.PaxosMessages.Preempted
import com.comcast.xfinity.sirius.api.impl.paxos.LeaderWatcher.DifferentLeader
import org.scalatest.BeforeAndAfterAll

class LeaderWatcherTest extends NiceTest with BeforeAndAfterAll {

  implicit val actorSystem = ActorSystem("LeaderPingerTest")

  case class PingersCreated(num: Int)

  def makeWatcher(ballot: Ballot = Ballot(1, TestProbe().ref.path.toString),
                  pinger: ActorRef = TestProbe().ref,
                  replyTo: ActorRef = TestProbe().ref,
                  pingerCreationNotifier: ActorRef = TestProbe().ref) = {

    TestActorRef(new LeaderWatcher(ballot, replyTo) {
      var pingersCreated = 0
      override def createPinger(expectedBallot: Ballot, replyTo: ActorRef) = {
        pingersCreated += 1
        pingerCreationNotifier ! PingersCreated(pingersCreated)
        pinger
      }
    })
  }

  override def afterAll() {
    actorSystem.shutdown()
  }

  describe ("on instantiation") {
    it ("creates a pinger") {
      val pingerCreationNotifier = TestProbe()
      makeWatcher(pingerCreationNotifier = pingerCreationNotifier.ref)

      pingerCreationNotifier.expectMsg(PingersCreated(1))
    }
  }

  describe ("upon receiving a CheckLeader message") {
    it ("creates a pinger") {
      val pingerCreationNotifier = TestProbe()
      val watcher = makeWatcher(pingerCreationNotifier = pingerCreationNotifier.ref)

      pingerCreationNotifier.expectMsg(PingersCreated(1))
      watcher ! CheckLeader
      pingerCreationNotifier.expectMsg(PingersCreated(2))
    }
  }

  describe ("upon receiving a LeaderGone message") {
    it ("tells replyTo to seek leadership and stops") {
      val replyTo = TestProbe()
      val watcher = makeWatcher(replyTo = replyTo.ref)

      watcher ! LeaderGone

      replyTo.expectMsg(SeekLeadership)
      assert(watcher.isTerminated)
    }
  }

  describe ("upon receiving a DifferentLeader message") {
    it ("preempts replyTo with the new ballot") {
      val replyTo = TestProbe()
      val watcher = makeWatcher(replyTo = replyTo.ref)
      val newBallot = Ballot(1, TestProbe().ref.path.toString)

      watcher ! DifferentLeader(newBallot)

      replyTo.expectMsg(Preempted(newBallot))
      assert(watcher.isTerminated)
    }
  }

  describe ("upon receiving a Close message") {
    it ("dies quietly") {
      val watcher = makeWatcher()

      watcher ! Close

      assert(watcher.isTerminated)
    }
  }
}