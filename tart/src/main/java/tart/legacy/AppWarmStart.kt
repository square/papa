package tart.legacy

/**
 * A warm starts is tracked when the application resumes an activity, if the application process had
 * previously already been started (i.e. this is not a cold start) and had no resumed activity (i.e.
 * the application was in background and came to the foregroud)
 *
 * A warm start encompasses a subset of the operations that take place during a cold start; There
 * are many potential states that could be considered warm starts.
 *
 * A hot start is a warm start where all the system does is bring the activity to the foreground.
 */
data class AppWarmStart(
  val temperature: Temperature,
  /**
   * The uptime millis duration the app spent in background, i.e. the elapsed time between
   * when the pause lifecycle change was dequeued to when the resume lifecycle change was dequeued.
   */
  val backgroundElapsedUptimeMillis: Long,
  /**
   * The uptime millis duration from when the activity called super.onResume() to when the next
   * frame was done displaying.
   */
  val resumeToNextFrameElapsedUptimeMillis: Long
) {

  enum class Temperature {
    /**
     * Warm start: the activity was created with no state bundle and then resumed.
     */
    CREATED_NO_STATE,

    /**
     * Warm start: the activity was created with a state bundle and then resumed.
     */
    CREATED_WITH_STATE,

    /**
     * Warm start: the activity was started and then resumed
     */
    STARTED,

    /**
     * A hot start: the activity was resumed.
     */
    RESUMED
  }
}
