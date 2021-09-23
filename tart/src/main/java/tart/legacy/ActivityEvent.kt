package tart.legacy

/**
 * Tracks first time occurrences of activity lifecycle related events.
 */
data class ActivityEvent(
  val activityName: String,
  val elapsedUptimeMillis: Long
)
