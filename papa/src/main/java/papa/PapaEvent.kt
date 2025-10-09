package papa

import papa.PapaEvent.FrozenFrameOnTouch.Companion.FROZEN_FRAME_THRESHOLD

sealed class PapaEvent {

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
    val preLaunchState: PreLaunchState,

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

    val startUptimeMillis: Long
  ) : PapaEvent() {
    /**
     * Whether this launch will be considered a slow launch by the Play Store and is likely to
     * be reported as "bad behavior".
     */
    val isSlowLaunch: Boolean
      get() = durationUptimeMillis >= preLaunchState.launchType.slowThresholdMillis

    override fun toString(): String {
      return "AppLaunch(" +
        "preLaunchState=$preLaunchState, " +
        "duration=$durationUptimeMillis ms, " +
        "isSlowLaunch=$isSlowLaunch, " +
        "trampolined=$trampolined, " +
        "backgroundDuration=$invisibleDurationRealtimeMillis ms, " +
        "startUptimeMillis=$startUptimeMillis" +
        ")"
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
     * Elapsed uptime between when the touch down event was sent by the display and when it was
     * received by the activity.
     */
    val deliverDurationUptimeMillis: Long,
    /**
     * Elapsed uptime between when the touch down event was received by the activity and when the
     * next frame was displayed.
     */
    val dislayDurationUptimeMillis: Long,
    val pressedView: String?
  ) : PapaEvent() {

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

    override fun toString(): String {
      return "FrozenFrameOnTouch(" +
        "activityName='$activityName', " +
        "repeatTouchDownCount=$repeatTouchDownCount, " +
        "handledElapsed=$deliverDurationUptimeMillis ms, " +
        "frameElapsed=$dislayDurationUptimeMillis ms, " +
        "pressedView='$pressedView')"
    }
  }

  class UsageError(val debugMessage: String) : PapaEvent() {
    override fun toString(): String {
      return "Usage error: $debugMessage"
    }
  }
}
