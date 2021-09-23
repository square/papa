package tart.legacy

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
) {
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
