package com.comcast.xfinity.sirius.api.impl.persistence

import akka.actor.Actor
import akka.actor.ActorRef
import com.comcast.xfinity.sirius.writeaheadlog.{LogData, SiriusLog}
import com.comcast.xfinity.sirius.api.impl.{OrderedEvent, Delete, Put}

/**
 * {@link Actor} for persisting data to the write ahead log and forwarding
 * to the state worker.
 */
class SiriusPersistenceActor(val stateWorker: ActorRef, siriusLog: SiriusLog) extends Actor {
  def receive = {
    case OrderedEvent(sequence, timestamp, request) => {
      request match {
        case Put(key, body) => siriusLog.writeEntry(LogData("PUT", key, sequence, timestamp, Some(body)))
        case Delete(key) => siriusLog.writeEntry(LogData("DELETE", key, sequence, timestamp, None))
      }
      stateWorker forward request
    }
  }
}