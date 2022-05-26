package tart.internal

import tart.TartEvent
import tart.TartEventListener

internal fun interface EventSender {

  fun sendEvent(event: TartEvent)

  companion object : EventSender {
    override fun sendEvent(event: TartEvent) {
      TartEventListener.sendEvent(event)
    }
  }
}