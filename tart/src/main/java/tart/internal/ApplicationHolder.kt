package tart.internal

import android.app.Application
import tart.OkTrace
import tart.legacy.Perfs

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
      OkTrace.beginAsyncSection(Perfs.FOREGROUND_COLD_START_TRACE_NAME)
    }
    TraceMainThreadMessages.enableMainThreadMessageTracing()
    RealInputTracker.install()
    FrozenFrameOnTouchDetector.install()
  }
}