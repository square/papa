package papa

import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import papa.internal.RealInputTracker
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit.MILLISECONDS

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

/**
 * [InputEventType] is either a [MotionEvent] or a [KeyEvent].
 */
class DeliveredInput<InputEventType : InputEvent>(
  val event: InputEventType,
  val deliveryUptime: Duration,
  val framesSinceDelivery: Int,
  private var endTrace: (() -> Unit)?
) {

  val eventUptime: Duration
    get() = event.eventTime.milliseconds

  fun takeOverTraceEnd(): (() -> Unit)? {
    val transferedEndTrace = endTrace
    endTrace = null
    return transferedEndTrace
  }

  internal fun increaseFrameCount(): DeliveredInput<InputEventType> {
    val copy = DeliveredInput(
      event = event,
      deliveryUptime = deliveryUptime,
      framesSinceDelivery = framesSinceDelivery + 1,
      endTrace = endTrace
    )
    endTrace = null
    return copy
  }

  override fun toString(): String {
    return "DeliveredInput(" +
      "deliveryUptime=${deliveryUptime.toString(MILLISECONDS)}, " +
      "framesSinceDelivery=$framesSinceDelivery, " +
      "event=$event" +
      ")"
  }
}
