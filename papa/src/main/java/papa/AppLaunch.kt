package papa

/**
 * The app came was in the background and came to the foreground. In practice this means the app
 * had 0 activity in started or resumed state, and now has at least one activity in resumed state.
 *
 * Going from "paused" back to "resumed" isn't considered a foregrounding here. This is
 * an intentional decision, as a paused by not stopped activity is still visible and rendering.
 *
 * Currently we report an [AppLaunch] if at any point in time we go from 1 to 0 to 1 resumed
 * activity. This can lead to false positives (e.g. when finishing one activity restarts a new
 * activity stack) or shorter app launches (when using incorrectly written Trampoline activities).
 */
class AppLaunch(
  val launchType: LaunchType,

  /**
   * The app launch duration, also known as the Time To Initial Display (TTID), is the time it
   * takes for an application to produce its first frame, including process initialization (if a
   * cold start), activity creation (if cold/warm), and displaying first frame.
   *
   * https://developer.android.com/topic/performance/vitals/launch-time#time-initial
   */
  val durationUptimeMillis: Long,

  /**
   * True if more than one activity was launched as part of this launch. Trampoline activities
   * are a common pattern where the launcher activity immediately starts another activity based
   * on runtime conditions (for example whether the user is logged in or not).
   * Note that trampoline activities should always start the next activity in their onCreate()
   * method, otherwise the first frame rendered will show the trampoline activity instead of the
   * target activity (in that case, the launch end will be measured at that first frame and
   * [trampolined] will actually be false)
   */
  val trampolined: Boolean,

  /**
   * The elapsed real time millis duration the app spent invisible, or null if the app has
   * never been visible before. This is the elapsed real time duration from when the app
   * became invisible until the start of this launch.
   */
  val invisibleDurationRealtimeMillis: Long?,

  val startUptimeMillis: Long,
) : PapaEvent() {
  /**
   * Whether this launch will be considered a slow launch by the Play Store and is likely to
   * be reported as "bad behavior".
   */
  val isSlowLaunch: Boolean
    get() = durationUptimeMillis >= launchType.slowThresholdMillis

  override fun toString(): String {
    return "AppLaunch(" +
      "launchType=$launchType, " +
      "duration=$durationUptimeMillis ms, " +
      "isSlowLaunch=$isSlowLaunch, " +
      "trampolined=$trampolined, " +
      "backgroundDuration=$invisibleDurationRealtimeMillis ms, " +
      "startUptimeMillis=$startUptimeMillis" +
      ")"
  }
}