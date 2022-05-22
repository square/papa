package tart

import tart.AppState.Value.NoValue

// TODO All events should have a similar shape
// TODO Implement a consistent toString()
sealed class TartEvent {

  /**
   * Usage:
   * ```
   * AppLaunch.onAppLaunchListeners += { appLaunch ->
   *   val startType = when(appLaunch.preLaunchState) {
   *     NO_PROCESS -> "cold start"
   *     NO_PROCESS_FIRST_LAUNCH_AFTER_INSTALL -> "cold start"
   *     NO_PROCESS_FIRST_LAUNCH_AFTER_UPGRADE -> "cold start"
   *     NO_PROCESS_FIRST_LAUNCH_AFTER_CLEAR_DATA -> "cold start"
   *     PROCESS_WAS_LAUNCHING_IN_BACKGROUND -> "warm start"
   *     NO_ACTIVITY_NO_SAVED_STATE -> "warm start"
   *     NO_ACTIVITY_BUT_SAVED_STATE -> "warm start"
   *     ACTIVITY_WAS_STOPPED -> "hot start"
   *   }
   *   val durationMillis = appLaunch.duration.uptimeMillis
   *   println("$startType launch: $durationMillis ms")
   * }
   * ```
   *
   * TODO: Figure out the right terminology. "app launch" here means that previously
   * the process had no resumed activity, and now it has at least one resumed activity.
   * Note: that means that if the process had one paused activity that then gets resumed,
   * it'll still be considered an app launch. This would typically happen if showing a dialog
   * activity from another process and then coming back (so the current process was still visible
   * but not in foreground, and then comes back to foreground / resumed). Is it a "launch"? A "start"?
   * A "Foregrounding"? A "Resuming"? Feedback welcome! Also, is an enum good enough or do we want
   * more info? How do we want it?
   */
  class AppLaunch(
    val preLaunchState: PreLaunchState,
    val startUptimeMillis: Long,
    val endUptimeMillis: Long,
  ) : TartEvent() {

    val durationUptimeMillis: Long
      get() = endUptimeMillis - startUptimeMillis

    /**
     * Whether this launch will be considered a slow launch by the Play Store and is likely to
     * be reported as "bad behavior".
     */
    val isSlowLaunch: Boolean
      get() = durationUptimeMillis >= preLaunchState.slowThresholdMillis

    override fun toString(): String {
      return "AppLaunch(" +
        "preLaunchState=$preLaunchState, " +
        "startUptimeMillis=$startUptimeMillis, " +
        "endUptimeMillis=$endUptimeMillis, " +
        "durationUptimeMillis=$durationUptimeMillis, " +
        "isSlowLaunch=$isSlowLaunch" +
        ")"
    }
  }

  class InteractionLatency(
    val interaction: Interaction,
    val stateBeforeInteraction: AppState.Value,
    val stateAfterInteraction: AppState.Value,
    val startUptimeMillis: Long,
    val durationFromStartUptimeMillis: Long,
    val triggerData: TriggerData
  ) : TartEvent() {
    override fun toString(): String {
      val totalDurationUptimeMillis =
        durationFromStartUptimeMillis + triggerData.triggerDurationUptimeMillis
      val stateLog = when {
        stateBeforeInteraction != NoValue && stateAfterInteraction != NoValue -> {
          " (before='$stateBeforeInteraction', after='$stateAfterInteraction')"
        }
        stateBeforeInteraction != NoValue && stateAfterInteraction is NoValue -> {
          " (before='$stateBeforeInteraction')"
        }
        stateBeforeInteraction is NoValue && stateAfterInteraction != NoValue -> {
          " (after='$stateAfterInteraction')"
        }
        else -> ""
      }
      val duration =
        "$totalDurationUptimeMillis ms: ${
          triggerData.triggerDurationUptimeMillis
        } (${triggerData.triggerName}) + $durationFromStartUptimeMillis"
      return "${interaction.description} took $duration$stateLog"
    }
  }

  /**
   * Event sent when there was more than [FROZEN_FRAME_THRESHOLD] of uptime between when a touch down
   * event was sent by the display and the next frame after it was handled.
   *
   * This is sent at most once per window per frame, i.e. if there are more touch down events before the
   * next frame then only one [FrozenFrameOnTouch] will be sent, with [repeatTouchDownCount] > 0.
   */
  data class FrozenFrameOnTouch(
    val activityName: String,
    /**
     * How many other touch down happened between the first touch down and the next frame.
     * This is useful to detect rage tapping.
     */
    val repeatTouchDownCount: Int,
    /**
     * Elapsed uptime between when the touch down event was sent by the display and when it was handled
     * by the activity.
     */
    val handledElapsedUptimeMillis: Long,
    /**
     * Elapsed uptime between when the touch down event was handled by the activity and when the
     * next frame was displayed.
     */
    val frameElapsedUptimeMillis: Long,
    val pressedView: String?
  ) : TartEvent() {
    companion object {
      /**
       * According to https://www.nngroup.com/articles/response-times-3-important-limits 1 second is
       * about the limit for the user's flow of thought to stay uninterrupted, even though the user will
       * notice the delay.
       *
       * However Android Vitals states that frozen frames are UI frames that take longer than 700ms to
       * render. This is a problem because your app appears to be stuck and is unresponsive to user input
       * for almost a full second while the frame is rendering. No frames in your app should ever take
       * longer than 700ms to render.
       *
       * https://developer.android.com/topic/performance/vitals/frozen
       */
      const val FROZEN_FRAME_THRESHOLD = 700
    }
  }
}