package com.comcast.xfinity.sirius.api.impl.paxos

import org.scalatest.BeforeAndAfterAll
import com.comcast.xfinity.sirius.NiceTest
import akka.actor.ActorSystem
import com.comcast.xfinity.sirius.api.impl.paxos.PaxosMessages._
import akka.testkit.{ TestProbe, TestActorRef }
import com.comcast.xfinity.sirius.api.impl.Delete
import scala.collection.immutable.SortedMap
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class AcceptorTest extends NiceTest with BeforeAndAfterAll {

  implicit val actorSystem = ActorSystem("AcceptorTest")

  override def afterAll {
    actorSystem.shutdown()
  }

  describe("An Acceptor") {
    it ("must start off with a clean slate") {
      val acceptor = TestActorRef(new Acceptor(1))
      assert(acceptor.underlyingActor.ballotNum === Ballot.empty)
      assert(acceptor.underlyingActor.accepted === SortedMap[Long, PValue]())
    }

    describe("in response to Phase1A") {
      it ("must retain its ballotNum and respond appropriately if the incoming Ballot " +
          "is lesser than or equal to its own") {
        val acceptor = TestActorRef(new Acceptor(1))
        val ballotNum = Ballot(1, "a")
        val accepted = SortedMap(1L -> PValue(ballotNum, 1, Command(null, 1, Delete("1"))))

        acceptor.underlyingActor.ballotNum = ballotNum
        acceptor.underlyingActor.accepted = accepted

        val scoutProbe = TestProbe()
        acceptor ! Phase1A(scoutProbe.ref, Ballot(0, "a"))
        scoutProbe.expectMsg(Phase1B(acceptor.getParent, ballotNum, accepted.values.toSet))
        assert(acceptor.underlyingActor.ballotNum === ballotNum)
        assert(acceptor.underlyingActor.accepted === accepted)

        acceptor ! Phase1A(scoutProbe.ref, ballotNum)
        scoutProbe.expectMsg(Phase1B(acceptor.getParent, ballotNum, accepted.values.toSet))
        assert(acceptor.underlyingActor.ballotNum === ballotNum)
        assert(acceptor.underlyingActor.accepted === accepted)
      }

      it ("must update its ballotNum and respond appropriately if the incoming " +
          "Ballot is greater than its own") {
        val acceptor = TestActorRef(new Acceptor(1))
        val ballotNum = Ballot(1, "a")
        val accepted = SortedMap(1L -> PValue(ballotNum, 1, Command(null, 1, Delete("1"))))

        acceptor.underlyingActor.ballotNum = ballotNum
        acceptor.underlyingActor.accepted = accepted

        val scoutProbe = TestProbe()
        val biggerBallotNum = Ballot(2, "a")
        acceptor ! Phase1A(scoutProbe.ref, biggerBallotNum)
        scoutProbe.expectMsg(Phase1B(acceptor.getParent, biggerBallotNum, accepted.values.toSet))
        assert(acceptor.underlyingActor.ballotNum === biggerBallotNum)
        assert(acceptor.underlyingActor.accepted === accepted)
      }
    }

    describe("in response to Phase2A") {
      it ("must not update its state if the incoming Ballot is outdated") {
        val acceptor = TestActorRef(new Acceptor(1))
        val ballotNum = Ballot(1, "a")
        val accepted = SortedMap(1L -> PValue(ballotNum, 1, Command(null, 1, Delete("1"))))

        acceptor.underlyingActor.ballotNum = ballotNum
        acceptor.underlyingActor.accepted = accepted

        val commanderProbe = TestProbe()
        acceptor ! Phase2A(commanderProbe.ref, PValue(Ballot(0, "a"), 1, Command(null, 1, Delete("1"))))
        commanderProbe.expectMsg(Phase2B(acceptor.getParent, ballotNum))
        assert(acceptor.underlyingActor.ballotNum === ballotNum)
        assert(acceptor.underlyingActor.accepted === accepted)
      }

      it ("must update its ballotNum and accepted PValues, choosing the one with the most recent ballot, " +
          "if the incoming ballotNum is greater than or equal to its own") {
        val acceptor = TestActorRef(new Acceptor(1))
        val ballotNum = Ballot(1, "a")
        val accepted = SortedMap(1L -> PValue(ballotNum, 1, Command(null, 1, Delete("1"))))

        acceptor.underlyingActor.ballotNum = ballotNum
        acceptor.underlyingActor.accepted = accepted

        val commanderProbe = TestProbe()

        val newPValue1 = PValue(ballotNum, 2, Command(null, 2, Delete("3")))
        acceptor ! Phase2A(commanderProbe.ref, newPValue1)
        commanderProbe.expectMsg(Phase2B(acceptor.getParent, ballotNum))
        assert(acceptor.underlyingActor.ballotNum === ballotNum)
        assert(acceptor.underlyingActor.accepted === accepted + (2L -> newPValue1))

        val biggerBallot = Ballot(2, "a")
        val newPValue2 = PValue(biggerBallot, 3, Command(null, 3, Delete("4")))
        acceptor ! Phase2A(commanderProbe.ref, newPValue2)
        commanderProbe.expectMsg(Phase2B(acceptor.getParent, biggerBallot))
        assert(acceptor.underlyingActor.ballotNum === biggerBallot)
        assert(acceptor.underlyingActor.accepted === accepted + (2L -> newPValue1) + (3L -> newPValue2))

        val evenBiggerBallot = Ballot(3, "a")
        val newPValue3 = PValue(evenBiggerBallot, 3, Command(null, 3, Delete("5")))
        acceptor ! Phase2A(commanderProbe.ref, newPValue3)
        commanderProbe.expectMsg(Phase2B(acceptor.getParent, evenBiggerBallot))
        assert(acceptor.underlyingActor.ballotNum === evenBiggerBallot)
        assert(acceptor.underlyingActor.accepted === accepted + (2L -> newPValue1) + (3L -> newPValue3))
      }

      it ("must ignore the message if the slot is below its dignity") {
        val acceptor = TestActorRef(new Acceptor(5))
        val commanderProbe = TestProbe()

        val unnacceptablePval = PValue(Ballot(1, "a"), 4, Command(null, 1, Delete("3")))

        intercept[MatchError] {
          acceptor.underlyingActor.receive(Phase2A(commanderProbe.ref, unnacceptablePval))
        }
      }
    }

    describe("in response to a Reap message") {
      it ("must truncate the collection of pvalues it contains") {
        val acceptor = TestActorRef(new Acceptor(1))

        val now = System.currentTimeMillis()
        val keepers = SortedMap[Long, PValue](
          4L -> PValue(Ballot(3, "b"), 4, Command(null, now - 1000, Delete("3"))),
          5L -> PValue(Ballot(3, "b"), 5, Command(null, 1L, Delete("Z"))),
          6L -> PValue(Ballot(4, "b"), 6, Command(null, now, Delete("R")))
        )

        val accepted = SortedMap(
          1L -> PValue(Ballot(1, "a"), 1, Command(null, 100L, Delete("1"))),
          2L -> PValue(Ballot(2, "b"), 2, Command(null, 105L, Delete("2")))
        ) ++ keepers

        acceptor.underlyingActor.accepted = accepted

        acceptor ! Acceptor.Reap

        assert(keepers === acceptor.underlyingActor.accepted)
        assert(3L === acceptor.underlyingActor.lowestAcceptableSlotNumber)
      }

      it ("must not update its lowestAcceptableSlotNumber if nothing is reaped") {
        val acceptor = TestActorRef(new Acceptor(10))

        acceptor.underlyingActor.accepted = SortedMap[Long, PValue]()
        acceptor ! Acceptor.Reap
        assert(10 === acceptor.underlyingActor.lowestAcceptableSlotNumber)

        acceptor.underlyingActor.accepted = SortedMap[Long, PValue](
          1L -> PValue(Ballot(1, "A"), 11, Command(null, System.currentTimeMillis(), Delete("Z")))
        )
        acceptor ! Acceptor.Reap
        assert(10 === acceptor.underlyingActor.lowestAcceptableSlotNumber)
      }
    }
  }
}