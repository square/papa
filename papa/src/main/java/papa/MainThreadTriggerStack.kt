package papa

object MainThreadTriggerStack {

  /**
   * Returns the trigger with the earliest (minimum) [InteractionTrigger.triggerUptime], or `null`
   * if the stack is empty.
   *
   * When duplicate triggers with identical uptime coexist on the stack, the most recently pushed
   * one is preferred.
   *
   * Uses [reduceOrNull] for a single O(n) pass with zero allocations. On an equal-uptime tie the
   * later element wins. This is optimal - finding a minimum in an unsorted collection requires
   * examining every element at least once.
   */
  val earliestInteractionTrigger: InteractionTrigger?
    get() {
      Handlers.checkOnMainThread()
      return interactionTriggerStack.reduceOrNull { acc, trigger ->
        if (trigger.triggerUptime <= acc.triggerUptime) {
          trigger
        } else {
          acc
        }
      }
    }

  /**
   * Returns the input-event triggers currently visible on the stack in stack order.
   *
   * This is a filtered view of [interactionTriggerStack]. Duplicate triggers are returned in
   * their current stack order.
   */
  val inputEventInteractionTriggers: List<InteractionTriggerWithPayload<InputEventTrigger>>
    get() {
      Handlers.checkOnMainThread()
      return interactionTriggerStack.mapNotNull { it.toInputEventTriggerOrNull() }
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
   * Adds [trigger] to the [interactionTriggerStack] for the duration of [block]. Duplicate
   * trigger instances intentionally coexist on the stack so nested scopes do not evict an earlier
   * instance that is still responsible for later cleanup.
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
