package papa.internal

import android.app.Application
import papa.SafeTrace
import papa.SafeTraceSetup

/**
 * Automatically set on app start by [papa.legacy.Perfs]
 */
internal object ApplicationHolder {
  @Volatile
  var application: Application? = null
    private set

  fun install(application: Application, isForegroundImportance: Boolean) {
    this.application = application
    SafeTraceSetup.init(application)
    if (isForegroundImportance) {
      SafeTrace.beginAsyncSection(Perfs.LAUNCH_TRACE_NAME)
    }
    InputTracker.install(application)
    FrozenFrameOnTouchDetector.install()
  }
}