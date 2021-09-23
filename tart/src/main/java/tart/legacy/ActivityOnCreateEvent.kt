package tart.legacy

import android.content.Intent

/**
 * Tracks the first time [android.app.Activity.onCreate] occurs.
 */
data class ActivityOnCreateEvent(
  val activityName: String,
  val restoredState: Boolean,
  val elapsedUptimeMillis: Long,
  val intent: Intent?
)
