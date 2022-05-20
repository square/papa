package tart.internal

import android.app.Application

/**
 * Automatically set on app start by [tart.legacy.Perfs]
 */
internal object ApplicationHolder {

  val application: Application?
    get() = _application

  @Volatile
  private var _application: Application? = null

  fun install(application: Application) {
    _application = application
    TraceMainThreadMessages.enableMainThreadMessageTracing()
    RealInputTracker.install()
  }
}