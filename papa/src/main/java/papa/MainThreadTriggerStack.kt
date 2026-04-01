package papa

object MainThreadTriggerStack {

  val earliestInteractionTrigger: InteractionTrigger?
    get() {
      Handlers.checkOnMainThread()
      return interactionTriggerStack.reduceOrNull { earliestTrigger, trigger ->
        when {
          trigger.triggerUptime < earliestTrigger.triggerUptime -> trigger
          trigger.triggerUptime == earliestTrigger.triggerUptime &&
            trigger.name == earliestTrigger.name -> trigger
          else -> earliestTrigger
        }
      }
    }

  val inputEventInteractionTriggers: List<InteractionTriggerWithPayload<InputEventTrigger>>
    get() {
      Handlers.checkOnMainThread()
      val inputEventTriggersByKey =
        linkedMapOf<Pair<String, kotlin.time.Duration>, InteractionTriggerWithPayload<InputEventTrigger>>()
      interactionTriggerStack.forEach { trigger ->
        trigger.toInputEventTriggerOrNull()?.let {
          inputEventTriggersByKey[it.name to it.triggerUptime] = it
        }
      }
      return inputEventTriggersByKey.values.toList()
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
