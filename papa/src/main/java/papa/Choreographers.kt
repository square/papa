package papa

import android.view.Choreographer
import android.view.Window
import papa.internal.onNextPreDraw
import kotlin.time.Duration.Companion.nanoseconds

object Choreographers {

  private val pendingRenderedCallbacks = mutableListOf<OnFrameRenderedListener>()

  private val isInChoreographerFrameMessage by mainThreadMessageScopedLazy {
    "android.view.Choreographer\$FrameDisplayEventReceiver" in MainThreadMessageSpy.currentMessageAsString!!
  }

  /**
   * Whether the current call stack is rooted by a Choreographer#doFrame
   *
   * When [MainThreadMessageSpy.enabled] is true, we rely on the toString of the current main
   * thread message to check this, and we cache the result for the duration of the current main
   * thread message. When [MainThreadMessageSpy.enabled] is false, we capture a stacktrace (which is
   * slower) and look for Choreographer#doFrame in the stacktrace.
   *
   * This approach is overall fragile and needs to be tested against new versions of Android.
   */
  fun isInChoreographerFrame(): Boolean {
    if (!Handlers.isOnMainThread) {
      return false
    }
    if (MainThreadMessageSpy.enabled) {
      return isInChoreographerFrameMessage
    }
    // Fallback to slower method: capturing a stacktrace.
    val stackTrace = RuntimeException().stackTrace
    for (i in stackTrace.lastIndex downTo 0) {
      val element = stackTrace[i]
      if (element.className == "android.view.Choreographer" &&
        element.methodName == "doFrame"
      ) {
        return true
      }
    }
    return false
  }

  /**
   * Runs [callback] after the current or next call to Choreographer#doFrame is done. This
   * leverages [isInChoreographerFrame] to the same warnings apply.
   */
  fun postOnFrameRendered(callback: OnFrameRenderedListener) {
    if (isInChoreographerFrame()) {
      postOnCurrentFrameRendered(callback)
    } else {
      Choreographer.getInstance().postFrameCallback {
        postOnCurrentFrameRendered(callback)
      }
    }
  }

  /**
   * Should be called from within a choreographer frame callback.
   */
  internal fun postOnCurrentFrameRendered(callback: OnFrameRenderedListener) {
    val alreadyScheduled = pendingRenderedCallbacks.isNotEmpty()
    pendingRenderedCallbacks += callback
    if (alreadyScheduled) {
      return
    }
    val runPendingCallbacks = {
      val frameRenderedUptime = System.nanoTime().nanoseconds
      for (pendingCallback in pendingRenderedCallbacks) {
        try {
          pendingCallback.onFrameRendered(frameRenderedUptime)
        } catch (e: AbstractMethodError) {
          throw RuntimeException(
            "Lambda $pendingCallback does not implement " +
              "${OnFrameRenderedListener::class.java}, try declaring it with " +
              "${OnFrameRenderedListener::class.simpleName} { }", e
          )
        }
      }
      pendingRenderedCallbacks.clear()
    }
    // The frame callback runs somewhat in the middle of rendering, so by posting at the front
    // of the queue from there we get the timestamp for right when the next frame is done
    // rendering.
    Handlers.onCurrentMainThreadMessageFinished(runPendingCallbacks)
  }

  fun Window.postOnWindowFrameRendered(callback: OnFrameRenderedListener) {
    onNextPreDraw { postOnCurrentFrameRendered(callback) }
  }
}
