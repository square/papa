package tart

enum class AppLaunchType(val slowThresholdMillis: Long) {
  /**
   * Source: https://support.google.com/googleplay/android-developer/answer/9844486
   */
  COLD(slowThresholdMillis = 5000),

  /**
   * Source: https://support.google.com/googleplay/android-developer/answer/9844486
   */
  WARM(slowThresholdMillis = 2000L),

  /**
   * Note: https://developer.android.com/topic/performance/vitals/launch-time#av reports
   * 1.5 seconds and https://support.google.com/googleplay/android-developer/answer/9844486 reports
   * 1 second.
   */
  HOT(slowThresholdMillis = 1000L);
}