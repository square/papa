package tart.internal

import android.os.Handler
import android.os.Looper
import android.view.Choreographer

// TODO Merge this with other existing ways to track frames
internal class RealFrameRenderingTracker {

  private val mainAsyncHandler by lazy {
    Handler(Looper.getMainLooper())
  }

  fun postFrameRenderedCallback(block: () -> Unit) {
    // The frame callback runs somewhat in the middle of rendering, so by posting at the front
    // of the queue from there we get the timestamp for right when the next frame is done
    // rendering.
    Choreographer.getInstance().postFrameCallback {
      // The main thread is a single thread, and work always executes one task at a time. We're
      // creating an async handler here which means that messages posted to this handler won't be
      // reordered to execute after sync barriers (which are used for processing input events and
      // rendering frames). "sync barriers" are essentially prioritized messages. When you post, you
      // can post to the front or the back, and you're just inserting in a different place in the
      // queue. When the queue is done with the current message, it looks at its head for the next
      // message. Unless there's a special message enqueued called a sync barrier, which basically has
      // top priority and will run prior to the current head if its time that's gone. Async prevents
      // this behavior.
      mainAsyncHandler.postAtFrontOfQueueAsync {
        block()
      }
    }
  }
}
