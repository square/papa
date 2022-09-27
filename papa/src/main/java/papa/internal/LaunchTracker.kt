package papa.internal

import android.app.Activity
import android.os.SystemClock
import papa.SafeTrace
import java.util.concurrent.TimeUnit

internal class LaunchTracker(
  val appLaunchedCallback: (Launch) -> Unit
) {

  private var lastAppBecameInvisibleRealtimeMillis: Long? = null

  private var launchInProgress: LaunchInProgress? = null

  class Launch(
    val trampoline: Boolean,
    val startUptimeMillis: Long,
    val startRealtimeMillis: Long,
    val endUptimeMillis: Long,
    val invisibleDurationRealtimeMillis: Long?,
    val activityStartingTransition: LaunchedActivityStartingTransition
  )

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
          SafeTrace.endAsyncSection(Perfs.LAUNCH_TRACE_NAME)
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
        SafeTrace.beginAsyncSection(Perfs.LAUNCH_TRACE_NAME)
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

  fun onActivityResumed(
    resumedActivity: Activity,
    resumedActivityHash: String,
    activityStartingTransition: LaunchedActivityStartingTransition
  ) {
    if (launchInProgress == null) {
      return
    }

    // We're ending the launch of first frame post draw of this activity. If the activity ends up
    // not drawing and another activity is resumed immediately after, whichever activity draws
    // first will end up being declared as the final launched activity.
    resumedActivity.window.onNextPreDraw {
      onCurrentFrameRendered { frameRenderedUptime ->
        val launchInProgress = launchInProgress ?: return@onCurrentFrameRendered
        this.launchInProgress = null

        // We're ignoring a launch happening less than 500ms after the app became invisible.
        val isRealLaunch = launchInProgress.invisibleDurationRealtimeMillis?.let { it >= 500 }
          ?: true
        // We're cancelling at the end so that all activity lifecycle events in between are still
        // tracked as part of this fluke launch.
        if (!isRealLaunch) {
          return@onCurrentFrameRendered
        }
        val launch = with(launchInProgress) {
          Launch(
            trampoline = activityHash != resumedActivityHash,
            startUptimeMillis = startUptimeMillis,
            startRealtimeMillis = startRealtimeMillis,
            endUptimeMillis = frameRenderedUptime.inWholeMilliseconds,
            invisibleDurationRealtimeMillis = invisibleDurationRealtimeMillis,
            activityStartingTransition = activityStartingTransition
          )
        }
        appLaunchedCallback(launch)
      }
    }
  }
}