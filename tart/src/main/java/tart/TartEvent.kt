package tart

// TODO All subclasses should implement toString()
// TODO Offer a listener impl that logs to logcat and can be installed as necessary?
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

  data class InteractionLatency(
    val interaction: Interaction,
    val stateBeforeInteraction: AppState.Value,
    val stateAfterInteraction: AppState.Value,
    val startUptimeMillis: Long,
    val durationFromStartUptimeMillis: Long,
    val triggerData: TriggerData
  ) : TartEvent()
}