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

  fun <T> triggeredBy(
    trigger: InteractionTrigger,
    endTraceAfterBlock: Boolean,
    block: () -> T
  ): T {
    Handlers.checkOnMainThread()
    check(interactionTriggerStack.none { it === trigger }) {
      "Trigger $trigger already in the main thread trigger stack"
    }
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
