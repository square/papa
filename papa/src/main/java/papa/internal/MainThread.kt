package papa.internal

import android.os.Looper
import papa.Handlers

/**
 * Calls back [callback] from the main thread the next time that the main
 * thread is idle.
 */
internal fun onNextMainThreadIdle(callback: () -> Unit) {
  Handlers.checkOnMainThread()
  Looper.myQueue()
    .addIdleHandler {
      callback()
      false
    }
}
