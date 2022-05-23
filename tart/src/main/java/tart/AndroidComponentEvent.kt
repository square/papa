package tart

import android.content.Intent

open class AndroidComponentEvent(
  val name: String,
  val elapsedUptimeMillis: Long
)

class ActivityOnCreateEvent(
  name: String,
  elapsedUptimeMillis: Long,
  val restoredState: Boolean,
  val intent: Intent?
) : AndroidComponentEvent(name, elapsedUptimeMillis)

class ActivityTouchEvent(
  name: String,
  elapsedUptimeMillis: Long,
  val eventSentElapsedMillis: Long,
  val rawX: Float,
  val rawY: Float
) : AndroidComponentEvent(name, elapsedUptimeMillis)