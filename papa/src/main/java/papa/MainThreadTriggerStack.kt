package papa

object MainThreadTriggerStack {

  /**
   * Returns the trigger with the earliest (minimum) [InteractionTrigger.triggerUptime], or `null`
   * if the stack is empty.
   *
   * When forwarded trigger copies coexist on the stack (same name + triggerUptime), the most
   * recently pushed copy is preferred so that the active forwarded trace wins while it is in
   * scope.
   *
   * Uses [reduceOrNull] for a single O(n) pass with zero allocations. On a strictly smaller
   * uptime the new trigger wins outright; on an equal name + uptime tie the later element wins
   * (most recently pushed). This is optimal — finding a minimum in an unsorted collection
   * requires examining every element at least once.
   */
  val earliestInteractionTrigger: InteractionTrigger?
    get() {
      Handlers.checkOnMainThread()
      return interactionTriggerStack.reduceOrNull { acc, trigger ->
        if (trigger.triggerUptime < acc.triggerUptime ||
          (trigger.triggerUptime == acc.triggerUptime && trigger.name == acc.name)
        ) {
          trigger
        } else {
          acc
        }
      }
    }

  /**
   * Returns the input-event triggers currently visible on the stack in stack order.
   *
   * This is a filtered view of [interactionTriggerStack], not a deduplicated projection. If
   * forwarding temporarily places equal-but-distinct copies of the same logical input trigger on
   * the stack, both copies are returned here in their current stack order.
   *
   * Performance: O(n) single pass over [interactionTriggerStack], with tiered allocation to
   * avoid object creation in the common cases:
   * - **0 input triggers**: returns [emptyList], no allocations.
   * - **1 input trigger**: returns a single-element list, no intermediate collection.
   * - **2+ input triggers**: allocates an [ArrayList] and appends matches in a single pass.
   */
  val inputEventInteractionTriggers: List<InteractionTriggerWithPayload<InputEventTrigger>>
    get() {
      Handlers.checkOnMainThread()
      var firstInputEventTrigger: InteractionTriggerWithPayload<InputEventTrigger>? = null
      var inputEventTriggers: ArrayList<InteractionTriggerWithPayload<InputEventTrigger>>? = null

      interactionTriggerStack.forEach { trigger ->
        val inputEventTrigger = trigger.toInputEventTriggerOrNull() ?: return@forEach
        when {
          // First input trigger found: track it without allocating a list.
          firstInputEventTrigger == null -> firstInputEventTrigger = inputEventTrigger
          // Second input trigger found, promote to ArrayList.
          inputEventTriggers == null -> {
            val firstTrigger = requireNotNull(firstInputEventTrigger)
            inputEventTriggers = arrayListOf(firstTrigger, inputEventTrigger)
          }
          // 2+ triggers already in the list: append in stack order.
          else -> inputEventTriggers.add(inputEventTrigger)
        }
      }

      return when {
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
