package papa

import android.app.Application
import papa.internal.TraceMainThreadMessages

object SafeTraceSetup {

  @Volatile
  internal lateinit var application: Application

  internal val initDone: Boolean
    get() = ::application.isInitialized

  fun init(application: Application) {
    this.application = application
    TraceMainThreadMessages.enableMainThreadMessageTracing()
  }
}