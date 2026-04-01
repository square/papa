package papa

import kotlin.time.Duration

object MainThreadTriggerStack {

  val earliestInteractionTrigger: InteractionTrigger?
    get() {
      Handlers.checkOnMainThread()
      var earliestTrigger: InteractionTrigger? = null
      var hasOtherTriggerAtEarliestUptime = false
      interactionTriggerStack.forEach { trigger ->
        when {
          earliestTrigger == null -> earliestTrigger = trigger
          trigger.triggerUptime < earliestTrigger.triggerUptime -> {
            earliestTrigger = trigger
            hasOtherTriggerAtEarliestUptime = false
          }
          trigger.triggerUptime == earliestTrigger.triggerUptime -> {
            hasOtherTriggerAtEarliestUptime = true
          }
        }
      }

      if (earliestTrigger == null || !hasOtherTriggerAtEarliestUptime) {
        return earliestTrigger
      }

      for (index in interactionTriggerStack.lastIndex downTo 0) {
        val trigger = interactionTriggerStack[index]
        if (trigger.triggerUptime == earliestTrigger.triggerUptime &&
          trigger.name == earliestTrigger.name
        ) {
          return trigger
        }
      }

      return earliestTrigger
    }

  val inputEventInteractionTriggers: List<InteractionTriggerWithPayload<InputEventTrigger>>
    get() {
      Handlers.checkOnMainThread()
      var firstInputEventTrigger: InteractionTriggerWithPayload<InputEventTrigger>? = null
      var inputEventTriggers: ArrayList<InteractionTriggerWithPayload<InputEventTrigger>>? = null
      var inputEventTriggersByKey:
        LinkedHashMap<Pair<String, Duration>, InteractionTriggerWithPayload<InputEventTrigger>>? = null

      interactionTriggerStack.forEach { trigger ->
        val inputEventTrigger = trigger.toInputEventTriggerOrNull() ?: return@forEach
        when {
          firstInputEventTrigger == null -> firstInputEventTrigger = inputEventTrigger
          inputEventTriggersByKey != null -> {
            inputEventTriggersByKey[inputEventTrigger.name to inputEventTrigger.triggerUptime] =
              inputEventTrigger
          }
          inputEventTriggers == null -> {
            val firstTrigger = requireNotNull(firstInputEventTrigger)
            if (inputEventTrigger.name == firstTrigger.name &&
              inputEventTrigger.triggerUptime == firstTrigger.triggerUptime
            ) {
              val triggerKey = firstTrigger.name to firstTrigger.triggerUptime
              inputEventTriggersByKey = linkedMapOf(triggerKey to firstTrigger)
              inputEventTriggersByKey[triggerKey] = inputEventTrigger
            } else {
              inputEventTriggers = arrayListOf(firstTrigger, inputEventTrigger)
            }
          }
          else -> {
            val duplicateIndex = inputEventTriggers.indexOfFirst {
              it.name == inputEventTrigger.name && it.triggerUptime == inputEventTrigger.triggerUptime
            }
            if (duplicateIndex == -1) {
              inputEventTriggers.add(inputEventTrigger)
            } else {
              inputEventTriggersByKey = LinkedHashMap(inputEventTriggers.size + 1)
              inputEventTriggers.forEach { existingTrigger ->
                inputEventTriggersByKey[existingTrigger.name to existingTrigger.triggerUptime] =
                  existingTrigger
              }
              inputEventTriggersByKey[inputEventTrigger.name to inputEventTrigger.triggerUptime] =
                inputEventTrigger
              inputEventTriggers = null
            }
          }
        }
      }

      return when {
        inputEventTriggersByKey != null -> inputEventTriggersByKey.values.toList()
        inputEventTriggers != null -> inputEventTriggers
        firstInputEventTrigger != null -> listOf(firstInputEventTrigger)
        else -> emptyList()
      }
    }

  private val interactionTriggerStack = mutableListOf<InteractionTrigger>()

  /**
   * Must be called from the main thread.
   * Returns a copy of the current stack of triggers which were set up up the stack from
   * where this is invoked.
   */
  val currentTriggers: List<InteractionTrigger>
    get() {
      Handlers.checkOnMainThread()
      return ArrayList(interactionTriggerStack)
    }

  /**
   * Must be called from the main thread.
   * Adds [trigger] to the [interactionTriggerStack] for the duration of [block]. Equal-but-
   * distinct triggers intentionally coexist on the stack so callers can forward a copied trigger
   * without evicting the original trigger instance that is still responsible for later cleanup.
   *
   * @param endTraceAfterBlock Finish the interaction trace after [block] runs.
   * @param block The code to run, during whose call stack the trigger added will be available
   *   on the [interactionTriggerStack] and via [earliestInteractionTrigger].
   */
  fun <T> triggeredBy(
    trigger: InteractionTrigger,
    endTraceAfterBlock: Boolean,
    block: () -> T
  ): T {
    Handlers.checkOnMainThread()
    pushTriggeredBy(trigger)
    try {
      return block()
    } finally {
      popTriggeredBy(trigger)
      if (endTraceAfterBlock) {
        trigger.takeOverInteractionTrace()?.endTrace()
      }
    }
  }

  internal fun pushTriggeredBy(trigger: InteractionTrigger) {
    check(interactionTriggerStack.none { it === trigger }) {
      "Trigger $trigger already in the main thread trigger stack"
    }
    interactionTriggerStack.add(trigger)
  }

  internal fun popTriggeredBy(trigger: InteractionTrigger) {
    interactionTriggerStack.removeAll { it === trigger }
  }
}
