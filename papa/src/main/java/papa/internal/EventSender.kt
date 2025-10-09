package papa.internal

import papa.PapaEvent
import papa.PapaEventListener

internal fun interface EventSender {

  fun sendEvent(event: PapaEvent)

  companion object : EventSender {
    override fun sendEvent(event: PapaEvent) {
      PapaEventListener.sendEvent(event)
    }
  }
}
