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

  val isInMainThreadMessage: Boolean
    // if currentMessageAsString is null, we're not in a main thread message, e.g. we
    // could be handling touch inputs in between messages.
    get() = enabled && currentMessageAsString != null

  /**
   * Must be called only from the main thread.
   * Null if [enabled] is false or if the code calling this (from the main thread) is running
   * from outside the dispatching of a main thread message. For example,
   * [MessageQueue.nativePollOnce] may invoke input event dispatching code directly.
   */
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

    // Looper.mLogging is extracted to a local variable inside the message loop, and the same
    // reference is used for before and after. We're setting Looper.mLogging in the middle of a
    // message but won't get the "finish" callback because that local variable still references
    // null at that point.
    var before = true
    Looper.getMainLooper().setMessageLogging { messageAsString ->
      if (!enabled) {
        // We still get called here for the last message finishing after we called
        // stopSpyingMainThreadDispatching which called Looper.setMessageLogging(null)
        return@setMessageLogging
      }
      if (before) {
        currentMessageAsString = messageAsString
      }
      for (tracer in tracers) {
        tracer.onMessageDispatch(messageAsString, before = before)
      }
      if (!before) {
        currentMessageAsString = null
      }
      before = !before
    }
  }

  fun stopSpyingMainThreadDispatching() {
    Handlers.checkOnMainThread()
    currentMessageAsString = null
    enabled = false
    Looper.getMainLooper().setMessageLogging(null)
  }

  /**
   * Internal, we expect callers to have checked that we're on the
   * main thread and that enabled is true.
   */
  internal fun onCurrentMessageFinished(block: () -> Unit) {
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
}