package tart

import tart.legacy.Perfs
import java.util.concurrent.TimeUnit.MILLISECONDS

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
  val start: CpuDuration,
  val end: CpuDuration,
) {

  val duration: CpuDuration
    get() = end - start

  /**
   * Whether this launch will be considered a slow launch by the Play Store and is likely to
   * be reported as "bad behavior".
   */
  val isSlowLaunch: Boolean
    get() = duration.uptime(MILLISECONDS) >= preLaunchState.slowThresholdMillis

  override fun toString(): String {
    return "AppLaunch(" +
      "preLaunchState=$preLaunchState, " +
      "start=$start, " +
      "end=$end, " +
      "duration=$duration, " +
      "isSlowLaunch=$isSlowLaunch" +
      ")"
  }

  companion object {
    val onAppLaunchListeners: MutableList<(AppLaunch) -> Unit>
      get() = Perfs.appLaunchListeners
  }
}

/**
 * Source: https://support.google.com/googleplay/android-developer/answer/9844486
 */
private const val SLOW_COLD_LAUNCH_THRESHOLD_MILLIS = 5000L

/**
 * Source: https://support.google.com/googleplay/android-developer/answer/9844486
 */
private const val SLOW_WARM_LAUNCH_THRESHOLD_MILLIS = 2000L

/**
 * Note: https://developer.android.com/topic/performance/vitals/launch-time#av reports
 * 1.5 seconds and https://support.google.com/googleplay/android-developer/answer/9844486 reports
 * 1 second.
 */
private const val SLOW_HOT_LAUNCH_THRESHOLD_MILLIS = 1000L

/**
 * Also see https://developer.android.com/topic/performance/vitals/launch-time
 */
enum class PreLaunchState(val slowThresholdMillis: Long) {
  /**
   * This is typically referred to as a "cold start".
   * The process was started with a FOREGROUND importance and
   * the launched activity was created, started and resumed before our first post
   * ran.
   */
  NO_PROCESS(SLOW_COLD_LAUNCH_THRESHOLD_MILLIS),

  /**
   * Same as [NO_PROCESS] but this was the first launch ever,
   * which might trigger first launch additional work.
   */
  NO_PROCESS_FIRST_LAUNCH_AFTER_INSTALL(SLOW_COLD_LAUNCH_THRESHOLD_MILLIS),

  /**
   * Same as [NO_PROCESS] but this was the first launch after the app was upgraded, which might
   * trigger additional migration work. Note that if the upgrade if the first upgrade
   * that introduces this library, the value will be [NO_PROCESS_FIRST_LAUNCH_AFTER_CLEAR_DATA]
   * instead.
   */
  NO_PROCESS_FIRST_LAUNCH_AFTER_UPGRADE(SLOW_COLD_LAUNCH_THRESHOLD_MILLIS),

  /**
   * Same as [NO_PROCESS] but this was either the first launch after a clear data, or
   * this was the first launch after the upgrade that introduced this library.
   */
  NO_PROCESS_FIRST_LAUNCH_AFTER_CLEAR_DATA(SLOW_COLD_LAUNCH_THRESHOLD_MILLIS),

  /**
   * This is the coldest type of "warm start". The process was not started with
   * a FOREGROUND importance yet the launched activity was created, started and resumed
   * before our first post ran. This means that while the process while starting, the
   * system decided to launch the activity.
   */
  PROCESS_WAS_LAUNCHING_IN_BACKGROUND(SLOW_WARM_LAUNCH_THRESHOLD_MILLIS),

  /**
   * This is a "warm start" where the activity brought to the foreground had to be created,
   * started and resumed, and the task had no saved instance state bundle.
   */
  NO_ACTIVITY_NO_SAVED_STATE(SLOW_WARM_LAUNCH_THRESHOLD_MILLIS),

  /**
   * This is a "warm start" where the activity brought to the foreground had to be created,
   * started and resumed, and the task can benefit somewhat from the saved instance state bundle
   * passed into onCreate().
   */
  NO_ACTIVITY_BUT_SAVED_STATE(SLOW_WARM_LAUNCH_THRESHOLD_MILLIS),

  /**
   * This is a "hot start", the activity was already created and had been stopped when the app
   * went in background. Bringing it to the foreground means the activity was started and then
   * resumed. Note that there isn't a "ACTIVITY_WAS_PAUSED" entry here. We do not consider
   * going from PAUSE to RESUME to be a launch because the activity was still visible so there
   * is nothing to redraw on resume.
   */
  ACTIVITY_WAS_STOPPED(SLOW_HOT_LAUNCH_THRESHOLD_MILLIS),
}