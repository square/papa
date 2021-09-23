package tart.legacy

import android.view.MotionEvent

interface TouchMetrics {

  /**
   * Returns the last [MotionEvent] sent to any window with [MotionEvent.getAction] equal
   * to [MotionEvent.ACTION_UP], paired with the uptime millis at which it was
   * delivered.
   */
  val lastTouchUpEvent: Pair<MotionEvent, Long>?
}
