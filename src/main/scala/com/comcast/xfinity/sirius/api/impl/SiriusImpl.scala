package com.comcast.xfinity.sirius.api.impl

import com.comcast.xfinity.sirius.api.RequestHandler
import com.comcast.xfinity.sirius.api.RequestMethod
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.ask
import akka.dispatch.Await
import com.comcast.xfinity.sirius.api.Sirius
import akka.dispatch.Future

/**
 * A Sirius implementation implemented in Scala using Akka actors
 */
class SiriusImpl(val requestHandler: RequestHandler, val actorSystem: ActorSystem) extends Sirius with AkkaConfig {

  val stateWorker = actorSystem.actorOf(Props(new SiriusStateWorker(requestHandler)), SIRIUS_STATE_WORKER_NAME)

  /**
   * Enqueue a PUT for processing
   */
  def enqueuePut(key: String, body: Array[Byte]) = {
    (stateWorker ? (RequestMethod.PUT, key, body)).asInstanceOf[Future[Array[Byte]]]
  }

  /**
   * Enqueue a GET for processing
   */
  def enqueueGet(key: String) = {
    (stateWorker ? (RequestMethod.GET, key)).asInstanceOf[Future[Array[Byte]]]
  }
}