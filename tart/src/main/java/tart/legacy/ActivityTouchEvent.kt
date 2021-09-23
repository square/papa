package tart.legacy

/**
 * Tracks the first time a touch event occurs.
 */
data class ActivityTouchEvent(
  val activityName: String,
  val elapsedUptimeMillis: Long,
  val eventSentElapsedMillis: Long,
  val rawX: Float,
  val rawY: Float
)
