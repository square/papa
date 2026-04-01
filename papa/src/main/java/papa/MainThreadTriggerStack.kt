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
   * Returns the distinct effective input-event triggers currently visible on the stack,
   * deduplicated by (name, triggerUptime).
   *
   * This is intentionally not a raw stack dump. Forwarding can temporarily place multiple
   * equal-but-distinct copies of the same logical input trigger on the stack at once so cleanup
   * can remain instance-based. Callers of this property care about distinct user inputs, not every
   * forwarded stack entry, so equal copies are collapsed back to a single logical trigger here.
   *
   * When forwarded copies with the same key coexist, the most recently pushed copy is kept so
   * callers see the active forwarded trace while it is in scope. Once that forwarded scope exits,
   * the original copy remains on the stack and becomes visible again.
   *
   * Performance: O(n) single pass over [interactionTriggerStack], with tiered allocation to
   * avoid object creation in the common cases:
   * - **0 input triggers**: returns [emptyList], no allocations.
   * - **1 input trigger** (no duplicates): returns a single-element list, no intermediate
   *   collection.
   * - **2+ distinct input triggers**: allocates an [ArrayList] and deduplicates in-place via
   *   [ArrayList.indexOfFirst]. This is faster than a [LinkedHashMap] for the small stack sizes
   *   seen in practice (typically 1–3 triggers) because it avoids hashing, Pair-key allocation,
   *   and Map.Entry overhead.
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
          // Second input trigger found, still in the single-element fast path.
          inputEventTriggers == null -> {
            val firstTrigger = requireNotNull(firstInputEventTrigger)
            if (inputEventTrigger.name == firstTrigger.name &&
              inputEventTrigger.triggerUptime == firstTrigger.triggerUptime
            ) {
              // Duplicate of first: replace in-place, stay in single-element path.
              firstInputEventTrigger = inputEventTrigger
            } else {
              // Distinct: promote to ArrayList.
              inputEventTriggers = arrayListOf(firstTrigger, inputEventTrigger)
            }
          }
          // 2+ triggers already in the list: deduplicate by in-place replacement.
          else -> {
            val duplicateIndex = inputEventTriggers.indexOfFirst {
              it.name == inputEventTrigger.name && it.triggerUptime == inputEventTrigger.triggerUptime
            }
            if (duplicateIndex == -1) {
              inputEventTriggers.add(inputEventTrigger)
            } else {
              inputEventTriggers[duplicateIndex] = inputEventTrigger
            }
          }
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
