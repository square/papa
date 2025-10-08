package papa

object MainThreadTriggerStack {

  val earliestInteractionTrigger: InteractionTrigger?
    get() {
      Handlers.checkOnMainThread()
      return interactionTriggerStack.minByOrNull { it.triggerUptime }
    }

  val inputEventInteractionTriggers: List<InteractionTriggerWithPayload<InputEventTrigger>>
    get() {
      Handlers.checkOnMainThread()
      return interactionTriggerStack.mapNotNull {
        it.toInputEventTriggerOrNull()
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
   * Adds [trigger] to the [interactionTriggerStack], it will replace any existing trigger with
   * the same [InteractionTrigger.name] and [InteractionTrigger.triggerUptime].
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
    // First, remove based on object equality (which uses name/triggerUptime). This has the effect
    // of replacing any existing same-named, same-timed triggers.
    // After the block() we remove just this instance from the stack.
    interactionTriggerStack.removeAll { it == trigger }
    interactionTriggerStack.add(trigger)
    try {
      return block()
    } finally {
      interactionTriggerStack.removeAll { it === trigger }
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
