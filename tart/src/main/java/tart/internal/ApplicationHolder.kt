package tart.internal

import android.app.Application
import tart.OkTrace

/**
 * Automatically set on app start by [tart.legacy.Perfs]
 */
internal object ApplicationHolder {
  @Volatile
  var application: Application? = null
    private set

  fun install(application: Application, isForegroundImportance: Boolean) {
    this.application = application
    if (isForegroundImportance) {
      OkTrace.beginAsyncSection(Perfs.LAUNCH_TRACE_NAME)
    }
    TraceMainThreadMessages.enableMainThreadMessageTracing()
    RealInputTracker.install()
    FrozenFrameOnTouchDetector.install()
  }
}