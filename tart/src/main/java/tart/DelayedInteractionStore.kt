package tart

import tart.Interaction.Delayed
import tart.TartEvent.UsageError
import tart.internal.EventSender

/**
 * A concurrent store for interactions which will hold a maximum of [bufferSize] interactions.
 * When the max size is reached, the store will trim itself down to size on insertion by removing
 * interactions in a FIFO manner.
 *
 * All public methods are thread safe.
 */
class DelayedInteractionStore internal constructor(
  private val eventSender: EventSender,
  private val bufferSize: Int
) {

  constructor(bufferSize: Int = 100) : this(EventSender, bufferSize)

  // Set an access order map, so we can use LRU strategy to garbage collect. Capacity / load are
  // defaults
  private val delayedInteractions = LinkedHashMap<Class<out Interaction>, Delayed<*>>(
    16, 0.75f, true
  )

  inline fun <reified T : Interaction> get() = get(T::class.java)

  @Synchronized
  operator fun <T : Interaction> get(interactionClass: Class<T>): Delayed<T> {
    val delayed = delayedInteractions[interactionClass]
    if (delayed == null) {
      eventSender.sendEvent(UsageError("DelayedInteractionStore#get could not find interaction ${interactionClass.name}"))
      return Delayed(null)
    }
    @Suppress("UNCHECKED_CAST")
    return delayed as Delayed<T>
  }

  @Synchronized
  operator fun plusAssign(
    delayed: Delayed<*>
  ) {
    val interaction = delayed.interaction

    if (interaction == null) {
      eventSender.sendEvent(UsageError("Delayed interaction passed to DelayedInteractionStore#plusAssign isn't backed by real interaction"))
      return
    }
    val key = interaction::class.java

    delayedInteractions.remove(key)?.let { previousInteraction ->
      eventSender.sendEvent(UsageError("Interaction $${key.name} passed to DelayedInteractionStore#plusAssign already exists in store, canceling the previous one"))
      previousInteraction.cancel()
    }

    delayed.endListeners += {
      delayedInteractions.remove(key)
    }

    delayedInteractions[key] = delayed
    if (delayedInteractions.size > bufferSize) {
      val stalestInteraction = delayedInteractions.iterator().next()
      stalestInteraction.value.cancel()
    }
  }
}
