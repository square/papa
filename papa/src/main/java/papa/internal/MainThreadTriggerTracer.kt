package papa.internal

import android.app.Application
import com.squareup.papa.R
import papa.InteractionTrigger
import papa.MainThreadMessageSpy
import papa.MainThreadTriggerStack
import papa.SafeTrace
import papa.SimpleInteractionTrigger
import kotlin.time.Duration.Companion.nanoseconds

internal object MainThreadTriggerTracer {

  private const val ASYNC_SECTION_LABEL = "Main Message Interaction"

  fun install(application: Application) {
    if (!application.resources.getBoolean(R.bool.papa_track_main_thread_triggers)) {
      return
    }
    val asyncTraceCookie = System.nanoTime().toInt()
    lateinit var currentTrigger: InteractionTrigger
    MainThreadMessageSpy.startTracing { _, before ->
      if (before) {
        val dispatchUptime = System.nanoTime().nanoseconds
        SafeTrace.beginAsyncSection(ASYNC_SECTION_LABEL, asyncTraceCookie)
        currentTrigger = SimpleInteractionTrigger(
          triggerUptime = dispatchUptime,
          name = "main-message",
          interactionTrace = {
            SafeTrace.endAsyncSection(ASYNC_SECTION_LABEL, asyncTraceCookie)
          }
        )
        MainThreadTriggerStack.pushTriggeredBy(currentTrigger)
      } else {
        MainThreadTriggerStack.popTriggeredBy(currentTrigger)
        currentTrigger.takeOverInteractionTrace()?.endTrace()
      }
    }
  }
}