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

  var mainThreadSectionNameMapper: (String) -> String = :: cleanUpMainThreadSectionName

  /**
   * Cleans up a log string that matches the following pattern:
   * ">>>>> Dispatching to " + msg.target + " " + msg.callback + ": " + msg.what
   *
   * The goal is to make the label more legible and also more likely to fit in 127 chars.
   *
   * If msg.callback is null then the result is msg.target + msg.what
   * If msg.callback is not null then the result is msg.callback + msg.target + msg.what.
   *
   * Any "Continuation at " prefix in msg.callback is removed.
   */
  fun cleanUpMainThreadSectionName(log: String): String {
    val logNoPrefix = log.removePrefix(">>>>> Dispatching to ")
    val indexOfWhat = logNoPrefix.lastIndexOf(": ")
    val indexOfCallback = logNoPrefix.indexOf("} ")

    val targetHandler = logNoPrefix.substring(0, indexOfCallback + 1)
    val what = logNoPrefix.substring(indexOfWhat + 2)

    val callback = logNoPrefix.substring(indexOfCallback + 2, indexOfWhat)

    if (callback == "null") {
      return "$targetHandler $what"
    }

    val continuationString = "Continuation at "
    val indexOfContinuation = callback.indexOf(continuationString)
    val callbackNoContinuation = if (indexOfContinuation != -1) {
      callback.substring(indexOfContinuation + continuationString.length)
    } else {
      callback
    }

    // We're shuffling the string around because it gets truncating and callback
    // is usually more interesting.
    return "$callbackNoContinuation $targetHandler $what"
  }
}