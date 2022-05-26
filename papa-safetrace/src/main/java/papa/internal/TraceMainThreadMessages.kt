package papa.internal

import android.os.Build.VERSION
import android.os.Handler
import android.os.Looper
import com.squareup.papa.safetrace.R
import papa.SafeTrace
import papa.SafeTraceSetup

internal object TraceMainThreadMessages {

  private val traceMainThreadMessages: Boolean
    get() {
      if (!SafeTraceSetup.initDone) {
        return false
      }
      val resources = SafeTraceSetup.application.resources
      return resources.getBoolean(R.bool.papa_trace_main_thread)
    }

  @Volatile
  private var enabled = false

  fun enableMainThreadMessageTracing() {
    val mainLooper = Looper.getMainLooper()
    if (mainLooper === Looper.myLooper()) {
      enableOnMainThread()
    } else {
      Handler(mainLooper).post {
        enableOnMainThread()
      }
    }
  }

  private fun enableOnMainThread() {
    // Looper can log to a printer before and after each message. We leverage this to surface the
    // beginning and end of every main thread message in system traces. This costs a few extra string
    // concatenations for each message handling.
    //
    // This is disabled on Android 9 because it can introduce crashes. The log is created by
    // concatenating several values from Message, including toString() from Message.callback, which is
    // the posted runnable. Android 9 introduced lambdas support within AOSP code, which was kept
    // "cheap" by introducing the concept of PooledLambda to avoid one new instance per lambda:
    // https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/com/android/internal/util/function/pooled/PooledLambda.java
    // Unfortunately, calling toString() on a PooledLambda will crash if that lambda has 0 argument
    // and doesn't return one of void, Object or Boolean.
    // The crash was fixed in Android 10:
    // https://cs.android.com/android/_/android/platform/frameworks/base/+/75632d616dbf14b6c71ea0e3a8a55c6fc963ba10
    // It's unclear how much PooledLambda was used in Android 9, but we've found at least one usage
    // that is crashing our UI tests:
    // https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/UiAutomation.java;l=1365-1371;drc=master

    if (VERSION.SDK_INT != 28 && !enabled && SafeTrace.isTraceable && traceMainThreadMessages) {
      enabled = true
      var currentlyTracing = false
      Looper.getMainLooper().setMessageLogging { log ->
        if (!currentlyTracing) {
          if (SafeTrace.isCurrentlyTracing && log.startsWith('>')) {
            val traceSection = buildSectionLabel(log)
            SafeTrace.beginSection(traceSection)
            currentlyTracing = true
          }
        } else {
          currentlyTracing = false
          SafeTrace.endSection()
        }
      }
    }
  }

  private fun buildSectionLabel(log: String): String {
    // Pattern is ">>>>> Dispatching to " + msg.target + " " + msg.callback + ": " + msg.what
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