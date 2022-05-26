package tart.internal

import android.app.Application
import tart.SafeTrace
import tart.SafeTraceSetup

/**
 * Automatically set on app start by [tart.legacy.Perfs]
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
    RealInputTracker.install(application)
    FrozenFrameOnTouchDetector.install()
  }
}