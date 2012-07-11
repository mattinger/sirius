package com.comcast.xfinity.sirius

import akka.actor.{Actor, Props, ActorRef, ActorSystem}

object TestHelper {
  /**
   * Wraps an actor inside another, for the purposes of testing things sent to context.parent.  Messages sent
   * to this actor from outside are forwarded to the "inner" actor, messages sent to the context.parent
   * from inside are forwarded to the "parent" param, usually a probe.
   * @param inner actor to be wrapped, built by Props(new WhateverActor)
   * @param parent probe for catching messages sent to parent
   * @param actorSystem actor system to use for creating actor
   * @return "wrapped" actor
   */
  def wrapActorWithMockedSupervisor(inner: Props, parent: ActorRef, actorSystem: ActorSystem): ActorRef = {
    actorSystem.actorOf(Props(new Actor {
      val innerRef = context.actorOf(inner)
      def receive = {
        case x => if (sender == innerRef) {
          parent forward x
        } else {
          innerRef forward x
        }
      }
    }))
  }
}