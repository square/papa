package papa

import android.os.Build.VERSION
import android.os.Looper

object MainThreadMessageSpy {

  fun interface Tracer {
    fun onMessageDispatch(
      messageAsString: String,
      before: Boolean
    )
  }

  private val tracers = mutableListOf<Tracer>()

  fun startTracing(tracer: Tracer) {
    checkMainThread()
    if (VERSION.SDK_INT == 28) {
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
      return
    }
    check(tracers.none { it === tracers }) {
      "Tracer $tracer already in $tracers"
    }
    tracers.add(tracer)
    if (tracers.size == 1) {
      startSpyingMainThreadDispatching()
    }
  }

  fun stopTracing(tracer: Tracer) {
    checkMainThread()
    val singleTracerLeft = tracers.size == 1
    tracers.removeAll { it === tracer }
    if (singleTracerLeft && tracers.isEmpty()) {
      stopSpyingMainThreadDispatching()
    }
  }

  private fun startSpyingMainThreadDispatching() {
    // Looper can log to a printer before and after each message. We leverage this to surface the
    // beginning and end of every main thread message in system traces. This costs a few extra string
    // concatenations for each message handling.
    // The printer is called before ('>>' prefix) and after ('<<' prefix) every message.
    Looper.getMainLooper().setMessageLogging { messageAsString ->
      if (messageAsString.startsWith('>')) {
        for (tracer in tracers) {
          tracer.onMessageDispatch(messageAsString, before = true)
        }
      } else {
        for (tracer in tracers) {
          tracer.onMessageDispatch(messageAsString, before = false)
        }
      }
    }
  }

  private fun stopSpyingMainThreadDispatching() {
    Looper.getMainLooper().setMessageLogging(null)
  }

  private fun checkMainThread() {
    check(Looper.getMainLooper() === Looper.myLooper()) {
      "Should be called from the main thread, not ${Thread.currentThread()}"
    }
  }
}