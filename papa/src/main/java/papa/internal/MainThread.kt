package papa.internal

import android.os.Looper

/**
 * Calls back [callback] from the main thread the next time that the main
 * thread is idle.
 */
internal fun onNextMainThreadIdle(callback: () -> Unit) {
  checkMainThread()
  Looper.myQueue()
    .addIdleHandler {
      callback()
      false
    }
}
