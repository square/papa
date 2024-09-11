package papa

import android.os.Build.VERSION
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object MainThreadMessageSpy {

  fun interface Tracer {
    fun onMessageDispatch(
      messageAsString: String,
      before: Boolean
    )
  }

  private val tracers = CopyOnWriteArrayList<Tracer>()

  var enabled = false
    private set

  var currentMessageAsString: String? = null
    private set
    get() {
      Handlers.checkOnMainThread()
      return field
    }

  fun addTracer(tracer: Tracer) {
    Handlers.checkOnMainThread()
    check(tracers.none { it === tracers }) {
      "Tracer $tracer already in $tracers"
    }
    tracers.add(tracer)
  }

  fun removeTracer(tracer: Tracer) {
    Handlers.checkOnMainThread()
    tracers.removeAll { it === tracer }
  }

  fun onCurrentMessageFinished(block: () -> Unit) {
    if (!enabled) {
      return
    }
    Handlers.checkOnMainThread()
    tracers.add(object : Tracer {
      override fun onMessageDispatch(
        messageAsString: String,
        before: Boolean
      ) {
        tracers.remove(this)
        block()
      }
    })
  }

  fun startSpyingMainThreadDispatching() {
    Handlers.checkOnMainThread()
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
    enabled = true
    // Looper can log to a printer before and after each message. We leverage this to surface the
    // beginning and end of every main thread message in system traces. This costs a few extra string
    // concatenations for each message handling.
    // The printer is called before ('>>' prefix) and after ('<<' prefix) every message.
    Looper.getMainLooper().setMessageLogging { messageAsString ->
      val before = messageAsString.startsWith('>')
      if (before) {
        currentMessageAsString = messageAsString
      }
      for (tracer in tracers) {
        tracer.onMessageDispatch(messageAsString, before = before)
      }
      if (!before) {
        currentMessageAsString = null
      }
    }
  }

  fun stopSpyingMainThreadDispatching() {
    Handlers.checkOnMainThread()
    currentMessageAsString = null
    enabled = false
    Looper.getMainLooper().setMessageLogging(null)
  }
}