package papa.internal

import android.os.Handler
import android.os.Looper
import com.squareup.papa.safetrace.R
import papa.MainThreadMessageSpy
import papa.SafeTrace
import papa.SafeTraceSetup

internal object SafeTraceMainThreadMessages {

  private val traceMainThreadMessages: Boolean
    get() {
      if (!SafeTraceSetup.initDone) {
        return false
      }
      val resources = SafeTraceSetup.application.resources
      return resources.getBoolean(R.bool.papa_trace_main_thread)
    }

  @Volatile
  private var enabled = false

  fun enableMainThreadMessageTracing() {
    val mainLooper = Looper.getMainLooper()
    if (mainLooper === Looper.myLooper()) {
      enableOnMainThread()
    } else {
      Handler(mainLooper).post {
        enableOnMainThread()
      }
    }
  }

  private fun enableOnMainThread() {
    if (!enabled && SafeTrace.isTraceable && traceMainThreadMessages) {
      enabled = true
      var currentlyTracing = false
      MainThreadMessageSpy.startTracing { messageAsString, before ->
        if (!currentlyTracing) {
          if (SafeTrace.isCurrentlyTracing &&
            before &&
            // Don't add a trace section for Choreographer#doFrame, as that messes up
            // Macrobenchmark: https://issuetracker.google.com/issues/340206285
            "android.view.Choreographer\$FrameDisplayEventReceiver" !in messageAsString) {
            val traceSection = SafeTraceSetup.mainThreadSectionNameMapper.mapSectionName(messageAsString)
            SafeTrace.beginSection(traceSection)
            currentlyTracing = true
          }
        } else {
          currentlyTracing = false
          SafeTrace.endSection()
        }
      }
    }
  }
}