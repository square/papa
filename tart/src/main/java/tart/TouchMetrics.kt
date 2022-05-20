package tart

import android.view.MotionEvent
import tart.internal.RealTouchMetrics

interface TouchMetrics {

  /**
   * Returns the last [MotionEvent] sent to any window with [MotionEvent.getAction] equal
   * to [MotionEvent.ACTION_UP], paired with the uptime millis at which it was
   * delivered.
   */
  val lastTouchUpEvent: Pair<MotionEvent, Long>?

  /**
   * Returns a pair of [android.view.KeyEvent.getEventTime] and the uptime millis at which it was
   * delivered, if called from the main thread while the BACK key event is being delivered
   * (e.g. this is non null if called from within an onBackPressed() callback).
   */
  val lastBackKeyEvent: Pair<Long, Long>?

  companion object : TouchMetrics by RealTouchMetrics
}
