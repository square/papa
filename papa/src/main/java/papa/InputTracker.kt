package papa

import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import papa.internal.RealInputTracker

interface InputTracker {

  /**
   * When called from the main thread down the stack of an onclick listener callback, returns the
   * action up motion event that originally led to the click being performed.
   */
  val motionEventTriggeringClick: DeliveredInput<MotionEvent>?

  /**
   * When called from the main thread down the stack from a dispatchTouchEvent() call for a pressed,
   * returns the current key event. Returns null otherwise.
   *
   * As the back button is also a key, this will be non null and reference a KEYCODE_BACK
   * event down the stack of any onBackPressed() callback.
   */
  val currentKeyEvent: DeliveredInput<KeyEvent>?

  val triggerEvent: DeliveredInput<out InputEvent>?
    get() {
      val keyPressed = currentKeyEvent
      if (keyPressed != null) {
        return keyPressed
      }
      return motionEventTriggeringClick
    }

  companion object : InputTracker by RealInputTracker
}

class DeliveredInput<T : InputEvent>(
  val event: T,
  val deliveryUptimeMillis: Long
)
