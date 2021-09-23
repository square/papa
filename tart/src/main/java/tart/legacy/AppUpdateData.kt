package tart.legacy
data class AppUpdateData(
  val status: AppUpdateStartStatus,
  /**
   * See [android.content.pm.PackageInfo.firstInstallTime]
   */
  val firstInstallTimeMillis: Long,
  /**
   * See [android.content.pm.PackageInfo.lastUpdateTime]
   */
  val lastUpdateTimeMillis: Long,
  /**
   * List of all [android.content.pm.PackageInfo.versionName] values for all installs of the app,
   * most recent first.
   */
  val allInstalledVersionNames: List<String>,
  /**
   * List of all [android.content.pm.PackageInfo.versionCode] values for all installs of the app,
   * most recent first.
   */
  val allInstalledVersionCodes: List<Int>,
  /**
   * Whether the device OS was updated since the last app start, ie whether
   * [android.os.Build.FINGERPRINT] changed.
   *
   * Always false when [status] is [AppUpdateStartStatus.FIRST_START_AFTER_FRESH_INSTALL] (no prior
   * app start).
   *
   * Null if we hadn't saved the fingerprint in the last app start.
   */
  val updatedOsSinceLastStart: Boolean?,

  /**
   * Whether the device rebooted since the last app start, computed by comparing how
   * [android.os.SystemClock.elapsedRealtime] moved vs [System.currentTimeMillis]. This is
   * imperfect as [System.currentTimeMillis] can be set by the user or the phone network and may
   * jump backwards or forwards unpredictably.
   *
   * Always false when [status] is [AppUpdateStartStatus.FIRST_START_AFTER_FRESH_INSTALL] (no prior
   * app start).
   *
   * Null if we couldn't determine when the device last rebooted.
   */
  val rebootedSinceLastStart: Boolean?,

  /**
   * Whether the app ran into Java crash after the last app start.
   *
   * Always false when [status] is [AppUpdateStartStatus.FIRST_START_AFTER_FRESH_INSTALL] (no prior
   * app start).
   *
   * Null if we couldn't determine when the app last crashed.
   */
  val crashedInLastProcess: Boolean?,

  /**
   * Elapsed real time between when the app last crashed as reported by a default
   * [Thread.UncaughtExceptionHandler] and when the app next started as reported by
   * [AppStart.AppStartData.processStartRealtimeMillis].
   *
   * This can help understand whether the app was restarted soon after it crashes. The time
   * measurement is based on [android.os.SystemClock.elapsedRealtime] because it's not performance
   * related.
   *
   * Null when [status] is [AppUpdateStartStatus.FIRST_START_AFTER_FRESH_INSTALL] (no prior
   * app start), when [crashedInLastProcess] is false or when [rebootedSinceLastStart] is true.
   */
  val elapsedRealtimeSinceCrash: Long?
)