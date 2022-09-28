package papa.internal

import android.os.SystemClock
import android.view.Choreographer

private val pendingRenderedCallbacks = mutableListOf<(Long) -> Unit>()

internal fun onCurrentOrNextFrameRendered(callback: (Long) -> Unit) {
  if (isChoreographerDoingFrame()) {
    onCurrentFrameRendered(callback)
  } else {
    Choreographer.getInstance().postFrameCallback {
      onCurrentFrameRendered(callback)
    }
  }
}

/**
 * Note: this is somewhat slow and fragile.
 */
internal fun isChoreographerDoingFrame(): Boolean {
  if (!isOnMainThread()) {
    return false
  }
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
 * Should be called from within a choreographer frame callback
 */
internal fun onCurrentFrameRendered(callback: (Long) -> Unit) {
  val alreadyScheduled = pendingRenderedCallbacks.isNotEmpty()
  pendingRenderedCallbacks += callback
  if (alreadyScheduled) {
    return
  }
  // The frame callback runs somewhat in the middle of rendering, so by posting at the front
  // of the queue from there we get the timestamp for right when the next frame is done
  // rendering.
  // The main thread is a single thread, and work always executes one task at a time. We're
  // creating an async message that won't be reordered to execute after sync barriers (which are
  // used for processing input events and rendering frames). "sync barriers" are essentially
  // prioritized messages. When you post, you
  // can post to the front or the back, and you're just inserting in a different place in the
  // queue. When the queue is done with the current message, it looks at its head for the next
  // message. Unless there's a special message enqueued called a sync barrier, which basically has
  // top priority and will run prior to the current head if its time that's gone. Async prevents
  // this behavior.
  mainHandler.postAtFrontOfQueueAsync {
    val frameRenderedUptimeMillis = SystemClock.uptimeMillis()
    for (pendingCallback in pendingRenderedCallbacks) {
      pendingCallback(frameRenderedUptimeMillis)
    }
    pendingRenderedCallbacks.clear()
  }
}
