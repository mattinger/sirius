package com.comcast.xfinity.sirius.api.impl

import membership._
import org.mockito.Mockito._
import com.typesafe.config.ConfigFactory
import com.comcast.xfinity.sirius.api.RequestHandler
import com.comcast.xfinity.sirius.writeaheadlog.SiriusLog
import com.comcast.xfinity.sirius.admin.SiriusAdmin
import com.comcast.xfinity.sirius.NiceTest
import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestProbe, TestActor, TestActorRef}
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout
import com.comcast.xfinity.sirius.info.SiriusInfo
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.agent.Agent
import com.comcast.xfinity.sirius.api.SiriusResult

object SiriusSupervisorTest {

  def createProbedTestSupervisor(admin: SiriusAdmin,
      handler: RequestHandler,
      siriusLog: SiriusLog,
      stateProbe: TestProbe,
      persistenceProbe: TestProbe,
      paxosProbe: TestProbe,
      membershipProbe: TestProbe,
      membershipAgent: Agent[MembershipMap])(implicit as: ActorSystem) = {
    TestActorRef(new SiriusSupervisor(admin, handler, siriusLog, membershipAgent) {
      override def createStateActor(_handler: RequestHandler) = stateProbe.ref

      override def createPersistenceActor(_state: ActorRef, _writer: SiriusLog) = persistenceProbe.ref

      override def createPaxosActor(_persistence: ActorRef) = paxosProbe.ref

      override def createMembershipActor(_membershipAgent: Agent[MembershipMap]) = membershipProbe.ref
    })
  }
}


@RunWith(classOf[JUnitRunner])
class SiriusSupervisorTest() extends NiceTest {

  var actorSystem: ActorSystem = _

  var paxosProbe: TestProbe = _
  var persistenceProbe: TestProbe = _
  var stateProbe: TestProbe = _
  var membershipProbe: TestProbe = _
  var nodeToJoinProbe: TestProbe = _

  var membershipAgent: Agent[Map[SiriusInfo,MembershipData]] = _

  var handler: RequestHandler = _
  var admin: SiriusAdmin = _
  var siriusLog: SiriusLog = _
  var siriusInfo: SiriusInfo = _

  var supervisor: TestActorRef[SiriusSupervisor] = _
  implicit val timeout: Timeout = (5 seconds)

  before {
    actorSystem = ActorSystem("testsystem", ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    """))

    //setup mocks
    handler = mock[RequestHandler]
    admin = mock[SiriusAdmin]
    siriusLog = mock[SiriusLog]
    siriusInfo = mock[SiriusInfo]

    membershipProbe = TestProbe()(actorSystem)
    membershipProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): Option[TestActor.AutoPilot] = msg match {
        case msg: MembershipMessage => Some(this)
      }
    })

    paxosProbe = TestProbe()(actorSystem)
    paxosProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): Option[TestActor.AutoPilot] = msg match {
        case Delete(_) =>
          sender ! SiriusResult.some("Delete it".getBytes)
          Some(this)
        case Put(_, _) =>
          sender ! SiriusResult.some("Put it".getBytes)
          Some(this)
      }
    })

    stateProbe = TestProbe()(actorSystem)
    stateProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): Option[TestActor.AutoPilot] = msg match {
        case Get(_) =>
          sender ! SiriusResult.some("Got it".getBytes)
          Some(this)
      }
    })

    persistenceProbe = TestProbe()(actorSystem)

    membershipAgent = mock[Agent[MembershipMap]]

    supervisor = SiriusSupervisorTest.createProbedTestSupervisor(
        admin, handler, siriusLog, stateProbe, persistenceProbe, paxosProbe,
        membershipProbe, membershipAgent)(actorSystem)
  }

  after {
    actorSystem.shutdown()
  }

  describe("a SiriusSupervisor") {
    it("should forward MembershipMessages to the membershipActor") {
      val membershipMessage: MembershipMessage = GetMembershipData
      supervisor ! membershipMessage
      membershipProbe.expectMsg(membershipMessage)
    }

    it("should forward GET messages to the stateActor") {
      val get = Get("1")
      val getAskFuture = supervisor ? get
      val expected = SiriusResult.some("Got it".getBytes)
      assert(expected === Await.result(getAskFuture, timeout.duration))
      stateProbe.expectMsg(get)
      noMoreMsgs()
    }
    
    it("should forward DELETE messages to the paxosActor") {
      val delete = Delete("1")
      val deleteAskFuture = supervisor ? delete
      val expected = SiriusResult.some("Delete it".getBytes)
      assert(expected === Await.result(deleteAskFuture, timeout.duration))
      paxosProbe.expectMsg(delete)
      noMoreMsgs()
    }

    it("should forward PUT messages to the paxosActor") {
      val put = Put("1", "someBody".getBytes)
      val putAskFuture = supervisor ? put
      val expected = SiriusResult.some("Put it".getBytes)
      assert(expected === Await.result(putAskFuture, timeout.duration))
      paxosProbe.expectMsg(put)
      noMoreMsgs()
    }

  }

  def noMoreMsgs() {
    membershipProbe.expectNoMsg((100 millis))
    paxosProbe.expectNoMsg(100 millis)
    persistenceProbe.expectNoMsg((100 millis))
    stateProbe.expectNoMsg((100 millis))
  }

}