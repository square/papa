package tart.legacy

/**
 * On Android Q+, captures information from [android.app.ActivityManager.AppTask] returned by
 * [android.app.ActivityManager.getAppTasks].
 */
data class AppTask(
  val topActivity: String?,
  val elapsedSinceLastActiveRealtimeMillis: Long?,
  val numActivities: Int?,
  val baseIntent: String?
)