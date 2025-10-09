package papa

import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.core.os.MessageCompat

object Handlers {

  val isOnMainThread: Boolean get() = Looper.getMainLooper() === Looper.myLooper()

  val mainThreadHandler by lazy {
    Handler(Looper.getMainLooper())
  }

  fun checkOnMainThread() {
    check(isOnMainThread) {
      "Should be called from the main thread, not ${Thread.currentThread()}"
    }
  }

  /**
   * [block] runs when the current main thread message is done running.
   * Must be called on the main thread.
   */
  fun onCurrentMainThreadMessageFinished(block: () -> Unit) {
    checkOnMainThread()
    if (MainThreadMessageSpy.enabled &&
      // if currentMessageAsString is null, we're not in a main thread message, e.g. we
      // could be handling touch inputs in between messages
      // if currentMessageAsString contains "androidx.test.espresso" then this is likely
      // Espresso taking over the main thread queue via Interrogator.loopAndInterrogate() and
      // running main messages at once so we need to fallback.
      MainThreadMessageSpy.currentMessageAsString?.let { "androidx.test.espresso" !in it } == true
    ) {
      MainThreadMessageSpy.onCurrentMessageFinished(block)
    } else {
      mainThreadHandler.postAtFrontOfQueueAsync(block)
    }
  }

  /**
   * The main thread is a single thread, and work always executes one task at a time. We're
   * creating an async message that won't be reordered to execute after sync barriers (which are
   * used for processing input events and rendering frames). "sync barriers" are essentially
   * blocking prioritized messages. When you post, you can post to the front or the back, and
   * you're just inserting in a different place in the
   * queue. When the queue is done with the current message, it looks at its head for the next
   * message. Unless there's a special message enqueued called a sync barrier, which basically has
   * top priority and will block the main thread until it runs. Async prevents this behavior.
   * Thx @chet and @jreck for writing this:
   * https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:metrics/metrics-performance/src/main/java/androidx/metrics/performance/JankStatsApi16Impl.kt;l=66;drc=523d7a11e46390281ed3f77893671730cd6edb98
   */
  private fun Handler.postAtFrontOfQueueAsync(callback: () -> Unit) {
    sendMessageAtFrontOfQueue(
      Message.obtain(this, callback).apply {
        MessageCompat.setAsynchronous(this, true)
      }
    )
  }
}
