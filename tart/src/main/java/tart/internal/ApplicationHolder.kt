package tart.internal

import android.app.Application

/**
 * Automatically set on app start by [tart.legacy.Perfs]
 */
internal object ApplicationHolder {
  @Volatile
  var application: Application? = null
    private set

  fun install(application: Application) {
    this.application = application
    TraceMainThreadMessages.enableMainThreadMessageTracing()
    RealInputTracker.install()
    FrozenFrameOnTouchDetector.install()
  }
}