package papa.internal

import android.os.Looper

/**
 * Returns true if the current thread is the main thread, false otherwise.
 */
internal fun isOnMainThread(): Boolean {
  return Thread.currentThread() === Looper.getMainLooper().thread
}

/**
 * Calls back [callback] from the main thread the next time that the main
 * thread is idle.
 */
internal fun onNextMainThreadIdle(callback: () -> Unit) {
  enforceMainThread()
  Looper.myQueue()
    .addIdleHandler {
      callback()
      false
    }
}

/**
 * @throws UnsupportedOperationException is the current thread is not the main thread.
 */
internal fun enforceMainThread() {
  if (!isOnMainThread()) {
    throw UnsupportedOperationException(
      "Should be called from main thread, not ${Thread.currentThread().name}"
    )
  }
}