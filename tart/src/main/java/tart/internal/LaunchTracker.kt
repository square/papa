package tart.internal

import android.app.Activity
import android.os.SystemClock
import tart.OkTrace

internal class LaunchTracker {

  private var lastAppBecameInvisibleRealtimeMillis: Long? = null

  private var launchInProgress: LaunchInProgress? = null

  class Launch(
    val trampoline: Boolean,
    val startUptimeMillis: Long,
    val startRealtimeMillis: Long,
    val invisibleDurationRealtimeMillis: Long?,
    val resumedActivity: Activity,
    val activityStartingTransition: LaunchedActivityStartingTransition
  ) {

    /**
     * Not a real launch if we've been invisible for less than 500 ms
     */
    val isRealLaunch: Boolean
      get() = invisibleDurationRealtimeMillis?.let { invisibleDurationRealtimeMillis >= 500 }
        ?: true
  }

  private class LaunchInProgress(
    val startUptimeMillis: Long,
    val startRealtimeMillis: Long,
    val invisibleDurationRealtimeMillis: Long?,
    val activityHash: String
  ) {

    val updateLastLifecycleChangeUptimeMillis = Runnable {
      lastLifecycleChangeDoneUptimeMillis = SystemClock.uptimeMillis()
    }

    // null means the latest lifecycle change hasn't cleared.
    var lastLifecycleChangeDoneUptimeMillis: Long? = null

    fun updateLastLifecycleChangeTime() {
      lastLifecycleChangeDoneUptimeMillis = null
      mainHandler.removeCallbacks(updateLastLifecycleChangeUptimeMillis)
      mainHandler.post(updateLastLifecycleChangeUptimeMillis)
    }

    /**
     * Stale if the last lifecycle update for the launch in progress was done more than 500ms
     * ago.
     */
    val isStale: Boolean
      get() = lastLifecycleChangeDoneUptimeMillis?.let { (SystemClock.uptimeMillis() - it) > 500 }
        ?: false
  }

  fun pushLaunchInProgressDeadline() {
    launchInProgress?.let { launch ->
      if (launch.isStale) {
        if (Perfs.isTracingLaunch) {
          OkTrace.endAsyncSection(Perfs.LAUNCH_TRACE_NAME)
          Perfs.isTracingLaunch = false
        }
        launchInProgress = null
      } else {
        launch.updateLastLifecycleChangeTime()
      }
    }
  }

  fun appBecameInvisible() {
    lastAppBecameInvisibleRealtimeMillis = SystemClock.elapsedRealtime()
  }

  fun appMightBecomeVisible(activityHash: String) {
    if (launchInProgress == null) {
      // Check to handle the cold start case where we're already tracing a launch.
      if (!Perfs.isTracingLaunch) {
        OkTrace.beginAsyncSection(Perfs.LAUNCH_TRACE_NAME)
        Perfs.isTracingLaunch = true
      }

      val invisibleDurationRealtimeMillis =
        lastAppBecameInvisibleRealtimeMillis?.let { SystemClock.elapsedRealtime() - it }

      launchInProgress = LaunchInProgress(
        activityHash = activityHash,
        startUptimeMillis = SystemClock.uptimeMillis(),
        startRealtimeMillis = SystemClock.elapsedRealtime(),
        invisibleDurationRealtimeMillis = invisibleDurationRealtimeMillis,
      )
    }
  }

  fun appEnteredForeground(
    resumedActivity: Activity,
    resumedActivityHash: String,
    activityStartingTransition: LaunchedActivityStartingTransition
  ): Launch? {
    return launchInProgress?.run {
      launchInProgress = null
      Launch(
        trampoline = activityHash != resumedActivityHash,
        resumedActivity = resumedActivity,
        startUptimeMillis = startUptimeMillis,
        startRealtimeMillis = startRealtimeMillis,
        invisibleDurationRealtimeMillis = invisibleDurationRealtimeMillis,
        activityStartingTransition = activityStartingTransition
      )
    }
  }
}