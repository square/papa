package papa.internal

import android.app.Application
import com.squareup.papa.R
import papa.MainThreadMessageSpy
import papa.SafeTrace
import papa.SafeTraceSetup

/**
 * Automatically set on app start by [papa.legacy.Perfs]
 */
internal object ApplicationHolder {
  @Volatile
  var application: Application? = null
    private set

  fun install(
    application: Application,
    isForegroundImportance: Boolean
  ) {
    this.application = application
    SafeTraceSetup.init(application)
    if (isForegroundImportance) {
      SafeTrace.beginAsyncSection(Perfs.LAUNCH_TRACE_NAME)
    }
    val resources = application.resources
    if (resources.getBoolean(R.bool.papa_track_input_events)) {
      InputTracker.install()
    }
    FrozenFrameOnTouchDetector.install()
    if (resources.getBoolean(R.bool.papa_spy_main_thread_messages)) {
      MainThreadMessageSpy.startSpyingMainThreadDispatching()
    }
    if (resources.getBoolean(R.bool.papa_track_main_thread_triggers)) {
      MainThreadTriggerTracer.install()
    }
  }
}