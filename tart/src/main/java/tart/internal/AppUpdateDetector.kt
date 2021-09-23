package tart.internal

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import tart.legacy.AppStart.AppStartData
import tart.legacy.AppUpdateData
import tart.legacy.AppUpdateStartStatus
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Detects when the app starts after first install, an update, or a crash.
 */
internal class AppUpdateDetector private constructor(
  private val application: Application,
  private val appStartUpdateCallback: ((AppStartData) -> AppStartData) -> Unit
) {

  private val handler = Handler(Looper.getMainLooper())

  /**
   * Note: initialization here isn't normally a blocking operation, it just starts an async load.
   * The first read will be blocking until the shared preferences are loaded in memory, which is
   * why [readAndUpdate] is called from a background thread.
   *
   * However some versions of Android trigger strict mode IO on shared pref retrieval, hence why
   * this is a lazy.
   */
  private val preferences by lazy {
    application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
  }

  /**
   * This is called from a background thread because shared preferences reads are blocking
   * until loaded.
   */
  private fun readAndUpdate() {
    val packageManager = application.packageManager
    val packageName = application.packageName
    // API 30 limits package visibility when calling getPackageInfo, but the specific usage here
    // references the calling application's package which is automatically visible by default.
    // See: https://developer.android.com/training/package-visibility/automatic
    val appPackageInfo = packageManager.getPackageInfo(packageName, 0)!!

    val currentElapsedRealtime = SystemClock.elapsedRealtime()
    val currentTimeMillis = System.currentTimeMillis()

    var allVersionNamesString: String
    var allVersionCodesString: String
    val status: AppUpdateStartStatus
    // Null when we don't know
    val rebootedSinceLastStart: Boolean?
    // Null when we don't know
    val updatedOsSinceLastStart: Boolean?
    // Null when we don't know
    val crashedInLastProcess: Boolean?
    val lastProcessCrashElapsedRealtime: Long?

    // This was null once when deploying from AS on a API 21 emulator.
    val versionName = appPackageInfo.versionName ?: "null"
    val longVersionCode = if (Build.VERSION.SDK_INT >= 28) {
      appPackageInfo.longVersionCode
    } else {
      appPackageInfo.versionCode.toLong()
    }
    val longVersionCodeString = longVersionCode.toString()

    if (!preferences.contains(VERSION_NAME_KEY)) {
      status = if (appPackageInfo.firstInstallTime != appPackageInfo.lastUpdateTime) {
        crashedInLastProcess = null
        updatedOsSinceLastStart = null
        rebootedSinceLastStart = null
        // This is an upgrade from a version that didn't have AppUpgradeDetector.
        // Since there is no file, it's also the first launch of this upgrade.
        AppUpdateStartStatus.FIRST_START_AFTER_UPGRADE
      } else {
        crashedInLastProcess = false
        updatedOsSinceLastStart = false
        rebootedSinceLastStart = false
        AppUpdateStartStatus.FIRST_START_AFTER_FRESH_INSTALL
      }
      allVersionNamesString = versionName
      allVersionCodesString = longVersionCodeString
      lastProcessCrashElapsedRealtime = null
    } else {
      val previousLongVersionCode = if (preferences.contains(LONG_VERSION_CODE_KEY)) {
        preferences.getLong(LONG_VERSION_CODE_KEY, -1)
      } else {
        preferences.getInt(VERSION_CODE_KEY, -1)
      }
      allVersionNamesString =
        preferences.getString(ALL_VERSION_NAMES_KEY, versionName)!!
      allVersionCodesString =
        preferences.getString(ALL_VERSION_CODES_KEY, longVersionCodeString)!!

      if (previousLongVersionCode != longVersionCode) {
        status = AppUpdateStartStatus.FIRST_START_AFTER_UPGRADE
        allVersionNamesString = "$versionName, $allVersionNamesString"
        allVersionCodesString = "$longVersionCodeString, $allVersionCodesString"
      } else {
        status = AppUpdateStartStatus.NORMAL_START
      }

      updatedOsSinceLastStart =
        preferences.getString(BUILD_FINGERPRINT_KEY, UNKNOWN_BUILD_FINGERPRINT)!!
          .let { fingerprint ->
            if (fingerprint == UNKNOWN_BUILD_FINGERPRINT) {
              null
            } else fingerprint != Build.FINGERPRINT
          }

      val previousElapsedRealtime =
        preferences.getLong(ELAPSED_REALTIME_KEY, UNKNOWN_ELAPSED_REALTIME)
      rebootedSinceLastStart = if (previousElapsedRealtime != UNKNOWN_ELAPSED_REALTIME) {
        val elapsedRealtimeDifference = currentElapsedRealtime - previousElapsedRealtime
        if (elapsedRealtimeDifference <= 0) {
          // SystemClock.elapsedRealtime() moved backward => reboot
          true
        } else {
          val previousTime = preferences.getLong(CURRENT_TIME_KEY, currentTimeMillis)
          val timeDifference = currentTimeMillis - previousTime
          if (timeDifference <= 0) {
            // System.currentTimeMillis() moved back => we can't assess if reboot
            null
          } else {
            val clockDifference = timeDifference - elapsedRealtimeDifference
            when {
              abs(clockDifference) < 30_000 -> {
                // SystemClock.elapsedRealtime() and System.currentTimeMillis() ~ moved in sync
                // => no reboot
                false
              }
              clockDifference > 0 -> {
                // System.currentTimeMillis() increased more than SystemClock.elapsedRealtime()
                // by 30+ seconds => reboot
                true
              }
              else -> {
                // SystemClock.elapsedRealtime() increased more than System.currentTimeMillis()
                // by 30+ seconds => System.currentTimeMillis() moved back => we can't assess if
                // reboot
                null
              }
            }
          }
        }
      } else {
        null
      }

      lastProcessCrashElapsedRealtime = preferences.getLong(CRASH_REALTIME_KEY, UNKNOWN_CRASH)

      crashedInLastProcess = if (lastProcessCrashElapsedRealtime == UNKNOWN_CRASH) {
        null
      } else {
        lastProcessCrashElapsedRealtime != NO_CRASH
      }
    }

    preferences.edit()
      .putLong(LONG_VERSION_CODE_KEY, longVersionCode)
      .putString(VERSION_NAME_KEY, versionName)
      .putString(ALL_VERSION_NAMES_KEY, allVersionNamesString)
      .putString(ALL_VERSION_CODES_KEY, allVersionCodesString)
      .putLong(ELAPSED_REALTIME_KEY, currentElapsedRealtime)
      .putLong(CURRENT_TIME_KEY, currentTimeMillis)
      .putLong(CRASH_REALTIME_KEY, NO_CRASH)
      .putString(BUILD_FINGERPRINT_KEY, Build.FINGERPRINT)
      .apply()

    val allVersionNames = allVersionNamesString.split(", ")
    val allVersionCodes = allVersionCodesString.split(", ")
      .map { it.toInt() }

    handler.post {
      appStartUpdateCallback { appStartData ->
        val elapsedRealtimeSinceCrash = if (crashedInLastProcess == true &&
          rebootedSinceLastStart == false
        ) {
          appStartData.processStartRealtimeMillis - lastProcessCrashElapsedRealtime!!
        } else {
          null
        }
        appStartData.copy(
          appUpdateData = AppUpdateData(
            status = status,
            firstInstallTimeMillis = appPackageInfo.firstInstallTime,
            lastUpdateTimeMillis = appPackageInfo.lastUpdateTime,
            allInstalledVersionNames = allVersionNames,
            allInstalledVersionCodes = allVersionCodes,
            updatedOsSinceLastStart = updatedOsSinceLastStart,
            rebootedSinceLastStart = rebootedSinceLastStart,
            crashedInLastProcess = crashedInLastProcess,
            elapsedRealtimeSinceCrash = elapsedRealtimeSinceCrash
          )
        )
      }
    }
  }

  private fun onAppCrashing() {
    preferences.edit()
      .putLong(CRASH_REALTIME_KEY, SystemClock.elapsedRealtime())
      .commit()
  }

  companion object {
    // Note: avoid renaming these constants otherwise the update data will be post.
    private const val PREF_NAME = "AppUpgradeDetector"
    private const val VERSION_CODE_KEY = "app_version_code"
    private const val LONG_VERSION_CODE_KEY = "app_long_version_code"
    private const val VERSION_NAME_KEY = "app_version_name"
    private const val ALL_VERSION_NAMES_KEY = "app_all_version_names"
    private const val ALL_VERSION_CODES_KEY = "app_all_version_codes"
    private const val ELAPSED_REALTIME_KEY = "elapsed_realtime"
    private const val CURRENT_TIME_KEY = "current_time"
    private const val CRASH_REALTIME_KEY = "crash_realtime"
    private const val BUILD_FINGERPRINT_KEY = "build_fingerprint"
    private const val UNKNOWN_ELAPSED_REALTIME = -1L
    private const val NO_CRASH = -1L
    private const val UNKNOWN_CRASH = -2L
    private const val UNKNOWN_BUILD_FINGERPRINT = "UNKNOWN_BUILD_FINGERPRINT"

    fun Application.trackAppUpgrade(
      block: ((AppStartData) -> AppStartData) -> Unit
    ) {
      val detector = AppUpdateDetector(this, block)
      val executorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable).apply {
          name = "app-upgrade-detector"
        }
      }
      executorService.execute { detector.readAndUpdate() }
      val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
      Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
        detector.onAppCrashing()
        defaultExceptionHandler?.uncaughtException(thread, exception)
      }
    }
  }
}
