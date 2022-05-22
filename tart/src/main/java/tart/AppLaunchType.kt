package tart

enum class AppLaunchType(val slowThresholdMillis: Long) {
  /**
   * Source: https://support.google.com/googleplay/android-developer/answer/9844486
   */
  COLD(slowThresholdMillis = 5000),

  /**
   * A warm starts is tracked when the application resumes an activity, if the application process had
   * previously already been started (i.e. this is not a cold start) and had no resumed activity (i.e.
   * the application was in background and came to the foregroud)
   *
   * A warm start encompasses a subset of the operations that take place during a cold start; There
   * are many potential states that could be considered warm starts.
   * Source: https://support.google.com/googleplay/android-developer/answer/9844486
   */
  WARM(slowThresholdMillis = 2000L),

  /**
   * A hot start is a warm start where all the system does is bring the activity to the foreground.
   * Note: https://developer.android.com/topic/performance/vitals/launch-time#av reports
   * 1.5 seconds and https://support.google.com/googleplay/android-developer/answer/9844486 reports
   * 1 second.
   */
  HOT(slowThresholdMillis = 1000L);
}