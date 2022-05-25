package tart.internal

import android.app.Activity
import android.os.SystemClock
import tart.OkTrace

internal class LaunchTracker {

  private var lastAppBecameInvisibleRealtimeMillis: Long? = null

  private var launchInProgress: LaunchInProgress? = null

  class Launch(
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
    private var lastLifecycleChangeUptimeMillis: Long
  ) {
    fun updateLastLifecycleChangeTime() {
      lastLifecycleChangeUptimeMillis = SystemClock.uptimeMillis()
    }

    /**
     * Stale if the last lifecycle update for the launch in progress was one second ago.
     */
    val isStale: Boolean
      get() = (SystemClock.uptimeMillis() - lastLifecycleChangeUptimeMillis) > 1000
  }

  // TODO I wonder if we should handle the deadline in a smarter way. E.g.
  // when onCreate+onStop+onResume (or a subset) happen all within the same post then
  // we don't want it to become stale no matter the duration.
  // Need to think a bit more about this.
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

  fun appMightBecomeVisible() {
    if (launchInProgress == null) {
      // Check to handle the cold start case where we're already tracing a launch.
      if (!Perfs.isTracingLaunch) {
        OkTrace.beginAsyncSection(Perfs.LAUNCH_TRACE_NAME)
        Perfs.isTracingLaunch = true
      }

      val nowUptimeMillis = SystemClock.uptimeMillis()

      val invisibleDurationRealtimeMillis =
        lastAppBecameInvisibleRealtimeMillis?.let { SystemClock.elapsedRealtime() - it }

      launchInProgress = LaunchInProgress(
        startUptimeMillis = nowUptimeMillis,
        startRealtimeMillis = SystemClock.elapsedRealtime(),
        invisibleDurationRealtimeMillis = invisibleDurationRealtimeMillis,
        lastLifecycleChangeUptimeMillis = nowUptimeMillis
      )
    }
  }

  fun appEnteredForeground(
    resumedActivity: Activity,
    activityStartingTransition: LaunchedActivityStartingTransition
  ): Launch? {
    return launchInProgress?.run {
      launchInProgress = null
      Launch(
        resumedActivity = resumedActivity,
        startUptimeMillis = startUptimeMillis,
        startRealtimeMillis = startRealtimeMillis,
        invisibleDurationRealtimeMillis = invisibleDurationRealtimeMillis,
        activityStartingTransition = activityStartingTransition
      )
    }
  }
}