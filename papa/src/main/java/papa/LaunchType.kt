package papa

/**
 * Also see https://developer.android.com/topic/performance/vitals/launch-time
 */
sealed class LaunchType {

  abstract val slowThresholdMillis: Long

  /**
   * The process was started with a FOREGROUND importance and
   * the launched activity was created, started and resumed before our first post
   * ran.
   * Source: https://support.google.com/googleplay/android-developer/answer/9844486
   */
  class Cold(
    val preLaunchState: PreLaunchState
  ) : LaunchType() {
    override val slowThresholdMillis = 5000L

    enum class PreLaunchState {
      NORMAL,
      /**
       * Same as [NORMAL] but this was the first launch ever,
       * which might trigger first launch additional work.
       */
      FIRST_LAUNCH_AFTER_INSTALL,
      /**
       * Same as [NORMAL] but this was the first launch after the app was upgraded, which might
       * trigger additional migration work. Note that if the upgrade if the first upgrade
       * that introduces this library, the value will be [FIRST_LAUNCH_AFTER_CLEAR_DATA]
       * instead.
       */
      FIRST_LAUNCH_AFTER_UPGRADE,
      /**
       * Same as [NORMAL] but this was either the first launch after a clear data, or
       * this was the first launch after the upgrade that introduced this library.
       */
      FIRST_LAUNCH_AFTER_CLEAR_DATA
    }

    override fun toString(): String {
      return "Cold(preLaunchState=$preLaunchState)"
    }
  }

  /**
   * A warm starts is tracked when the application creates and resumes an activity, if the
   * application process had previously already been started (i.e. this is not a cold start) and
   * there were no activity in created state (the application was in background and came to the
   * foreground)
   *
   * A warm start encompasses a subset of the operations that take place during a cold start; There
   * are many potential states that could be considered warm starts.
   * Source: https://support.google.com/googleplay/android-developer/answer/9844486
   *
   * [savedInstanceState] if true, the task can benefit somewhat from the saved instance state
   * bundle passed into onCreate().
   *
   * [processWasLaunchingInBackground] If true, this is the coldest type of "warm start". The
   * process was not started with a FOREGROUND importance yet the launched activity was created,
   * started and resumed before our first post ran. This means that while the process while
   * starting, the system decided to launch the activity.
   */
  // TODO A launch can involve a series of activities. We might want to move savedInstanceState
  // into a list of activity details.
  class Warm(val savedInstanceState: Boolean, val processWasLaunchingInBackground: Boolean) :
    LaunchType() {
    override val slowThresholdMillis = 2000L
    override fun toString(): String {
      return "Warm(savedInstanceState=$savedInstanceState, processWasLaunchingInBackground=$processWasLaunchingInBackground)"
    }
  }

  /**
   * This is a "hot start", the activity was already created and had been stopped when the app
   * went in background. Bringing it to the foreground means the activity was started and then
   * resumed. Note that there isn't a "ACTIVITY_WAS_PAUSED" entry here. We do not consider
   * going from PAUSE to RESUME to be a launch because the activity was still visible so there
   * is nothing to redraw on resume.
   * A hot start is a warm start where all the system does is bring a stopped activity to the
   * foreground.
   * Note: https://developer.android.com/topic/performance/vitals/launch-time#av reports
   * 1.5 seconds and https://support.google.com/googleplay/android-developer/answer/9844486 reports
   * 1 second.
   */
  class Hot : LaunchType() {
    override val slowThresholdMillis = 1000L
    override fun toString(): String {
      return "Hot"
    }
  }
}