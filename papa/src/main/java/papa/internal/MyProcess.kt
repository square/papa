package papa.internal

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import papa.AppTask
import papa.internal.MyProcess.Companion.findMyProcessInfo
import papa.internal.MyProcess.MyProcessData
import papa.internal.MyProcess.ErrorRetrievingMyProcessData

/**
 * [findMyProcessInfo] captures and returns information about the current process as
 * [MyProcessData] or [ErrorRetrievingMyProcessData] if there was an error.
 */
internal sealed class MyProcess {
  class MyProcessData(
    val info: RunningAppProcessInfo,
    val processStartRealtimeMillis: Long,
    val appTasks: List<AppTask>
  ) : MyProcess()

  class ErrorRetrievingMyProcessData(val throwable: Throwable) : MyProcess()

  companion object {
    @Suppress("TooGenericExceptionCaught")
    fun findMyProcessInfo(context: Context): MyProcess {
      try {
        val myPid = android.os.Process.myPid()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
          // Note: we could use ActivityManager.getMyMemoryState but that wouldn't give us
          // importanceReasonComponent importanceReasonPid.
          // We might eventually decide we don't need those, but for now let's get data from the
          // field.
          return activityManager.runningAppProcesses?.let { runningProcesses ->
            for (process in runningProcesses) {
              if (process.pid == myPid) {
                val appTasks = activityManager.appTasks.toAppTasks()
                val processStartRealtimeMillis = Processes.readProcessStartRealtimeMillis(myPid)
                return@let MyProcessData(process, processStartRealtimeMillis, appTasks)
              }
            }
            val processIds = runningProcesses.map { it.pid }
            return ErrorRetrievingMyProcessData(RuntimeException(
              "ActivityManager.getRunningAppProcesses() returned $processIds, " +
                "no process matching myPid() $myPid")
            )
          } ?: ErrorRetrievingMyProcessData(RuntimeException("ActivityManager.getRunningAppProcesses() returned null"))
        } catch (exception: SecurityException) {
          // This is a known possible error for isolated processes.
          // https://github.com/square/leakcanary/issues/948
          return ErrorRetrievingMyProcessData(exception)
        }
      } catch (throwable: Throwable) {
        // This should never happen, but we're touching risky APIs very early in the app lifecycle
        // prior to even having crash reporting set up (which is also why we don't report the
        // stacktrace).
        return ErrorRetrievingMyProcessData(throwable)
      }
    }

    private fun List<ActivityManager.AppTask>.toAppTasks(): List<AppTask> {
      return if (Build.VERSION.SDK_INT >= 29) {
        mapNotNull {
          val taskInfo = try {
            it.taskInfo
          } catch (ignored: IllegalArgumentException) {
            // android.app.ActivityManager.AppTask.getTaskInfo sometimes throws an
            // IllegalArgumentException "Unable to find task ID"
            // https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com
            // /android/server/wm/AppTaskImpl.java#84
            return@mapNotNull null
          }
          val elapsedSinceLastActiveMillis = try {
            // Not exposed via an API but available via toString() ;)
            val lastActiveTime = taskInfo.toString()
              .substringAfter("lastActiveTime=", "")
              .substringBefore(" ", "")
              .toLong()
            // Using SystemClock.elapsedRealtime() here as the sources indicate this is
            // the last time this task was active since boot (including time spent in sleep).
            SystemClock.elapsedRealtime() - lastActiveTime
          } catch (ignored: NumberFormatException) {
            0L
          }
          AppTask(
            topActivity = taskInfo.topActivity?.toShortString(),
            baseIntent = taskInfo.baseIntent.toString(),
            elapsedSinceLastActiveRealtimeMillis = elapsedSinceLastActiveMillis,
            numActivities = taskInfo.numActivities
          )
        }
      } else {
        emptyList()
      }
    }
  }
}