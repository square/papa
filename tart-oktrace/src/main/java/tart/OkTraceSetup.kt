package tart

import android.app.Application
import tart.internal.TraceMainThreadMessages

object OkTraceSetup {

  @Volatile
  internal lateinit var application: Application

  internal val initDone: Boolean
    get() = ::application.isInitialized

  fun init(application: Application) {
    this.application = application
    TraceMainThreadMessages.enableMainThreadMessageTracing()
  }
}