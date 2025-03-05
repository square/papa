package papa.internal

import papa.InteractionTrigger
import papa.MainThreadMessageSpy
import papa.MainThreadTriggerStack
import papa.SimpleInteractionTrigger
import kotlin.time.Duration.Companion.nanoseconds

internal object MainThreadTriggerTracer {

  fun install() {
    lateinit var currentTrigger: InteractionTrigger
    MainThreadMessageSpy.addTracer { _, before ->
      if (before) {
        currentTrigger = SimpleInteractionTrigger(
          triggerUptime = System.nanoTime().nanoseconds,
          name = "main-message",
          // This would create way too many async sections, making that not very useful.
          interactionTrace = null
        )
        MainThreadTriggerStack.pushTriggeredBy(currentTrigger)
      } else {
        MainThreadTriggerStack.popTriggeredBy(currentTrigger)
        currentTrigger.takeOverInteractionTrace()?.endTrace()
      }
    }
  }
}