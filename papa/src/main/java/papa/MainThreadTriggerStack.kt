package papa

import papa.internal.checkMainThread

object MainThreadTriggerStack {

  val earliestInteractionTrigger: InteractionTrigger?
    get() {
      checkMainThread()
      return interactionTriggerStack.minByOrNull { it.triggerUptime }
    }

  private val interactionTriggerStack = mutableListOf<InteractionTrigger>()

  /**
   * Must be called from the main thread.
   * Returns a copy of the current stack of triggers which were set up up the stack from
   * where this is invoked.
   */
  val currentTriggers: List<InteractionTrigger>
    get() {
      checkMainThread()
      return ArrayList(interactionTriggerStack)
    }

  fun <T> triggeredBy(
    trigger: InteractionTrigger,
    endTraceAfterBlock: Boolean,
    block: () -> T
  ): T {
    checkMainThread()
    interactionTriggerStack.add(trigger)
    try {
      return block()
    } finally {
      interactionTriggerStack.removeLast()
      if (endTraceAfterBlock) {
        trigger.takeOverInteractionTrace()?.endTrace()
      }
    }
  }

  internal fun pushTriggeredBy(trigger: InteractionTrigger) {
    interactionTriggerStack.add(trigger)
  }

  internal fun popTriggeredBy(trigger: InteractionTrigger) {
    interactionTriggerStack.removeAll { it === trigger }
  }
}
