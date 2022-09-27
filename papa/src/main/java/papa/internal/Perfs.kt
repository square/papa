package papa.internal

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StrictMode
import android.os.SystemClock
import papa.AndroidComponentEvent
import papa.AppLaunchType.COLD
import papa.AppStart
import papa.AppStart.AppStartData
import papa.AppStart.NoAppStartData
import papa.AppUpdateData.RealAppUpdateData
import papa.AppUpdateStartStatus.FIRST_START_AFTER_CLEAR_DATA
import papa.AppUpdateStartStatus.FIRST_START_AFTER_FRESH_INSTALL
import papa.AppUpdateStartStatus.FIRST_START_AFTER_UPGRADE
import papa.AppUpdateStartStatus.NORMAL_START
import papa.AppVisibilityState
import papa.AppVisibilityState.INVISIBLE
import papa.AppVisibilityState.VISIBLE
import papa.SafeTrace
import papa.PreLaunchState
import papa.PreLaunchState.ACTIVITY_WAS_STOPPED
import papa.PreLaunchState.NO_ACTIVITY_BUT_SAVED_STATE
import papa.PreLaunchState.NO_ACTIVITY_NO_SAVED_STATE
import papa.PreLaunchState.NO_PROCESS
import papa.PreLaunchState.NO_PROCESS_FIRST_LAUNCH_AFTER_CLEAR_DATA
import papa.PreLaunchState.NO_PROCESS_FIRST_LAUNCH_AFTER_INSTALL
import papa.PreLaunchState.NO_PROCESS_FIRST_LAUNCH_AFTER_UPGRADE
import papa.PreLaunchState.PROCESS_WAS_LAUNCHING_IN_BACKGROUND
import papa.PapaEvent.AppLaunch
import papa.PapaEventListener
import papa.internal.AppUpdateDetector.Companion.trackAppUpgrade
import papa.internal.LaunchTracker.Launch
import papa.internal.LaunchedActivityStartingTransition.CREATED_NO_STATE
import papa.internal.LaunchedActivityStartingTransition.CREATED_WITH_STATE
import papa.internal.LaunchedActivityStartingTransition.STARTED
import papa.internal.MyProcess.ErrorRetrievingMyProcessData
import papa.internal.MyProcess.MyProcessData
import papa.internal.PerfsActivityLifecycleCallbacks.Companion.trackActivityLifecycle
import java.util.concurrent.TimeUnit

/**
 * Singleton object centralizing state for app start and future other perf metrics.
 */
internal object Perfs {

  internal const val LAUNCH_TRACE_NAME = "App Launch"
  internal var isTracingLaunch = false

  // String value kept for backward compat reasons
  private const val LAST_VISIBILITY_STATE = "lastResumedState"

  // String value kept for backward compat reasons
  private const val LAST_VISIBILITY_CHANGE_CURRENT_MILLIS = "lastResumedCurrentMillis"
  private const val LAST_ALIVE_CURRENT_MILLIS = "lastAliveCurrentMillis"

  @Volatile
  private var initialized = false

  @Volatile
  private var notInitializedReason = "Perfs.init() was never called"

  @Volatile
  private lateinit var appStartData: AppStartData

  private val classInitUptimeMillis = SystemClock.uptimeMillis()

  internal var classLoaderInstantiatedUptimeMillis: Long? = null
  internal var applicationInstantiatedUptimeMillis: Long? = null
  internal var firstPostApplicationComponentInstantiated = false

  // Accessed from main thread only.
  internal var firstPostUptimeMillis: Long? = null

  private var reportedFullDrawn = false

  private val bindApplicationStartUptimeMillis: Long
    get() = if (Build.VERSION.SDK_INT >= 24) {
      val reportedStartUptimeMillis = Process.getStartUptimeMillis()

      val firstPostAtFrontElapsedUptimeMillis =
        appStartData.firstPostAtFrontElapsedUptimeMillis
      if (firstPostAtFrontElapsedUptimeMillis != null) {
        val firstPostAtFrontUptimeMillis =
          firstPostAtFrontElapsedUptimeMillis - appStartData.processStartUptimeMillis
        if (firstPostAtFrontUptimeMillis - reportedStartUptimeMillis < 60_000) {
          reportedStartUptimeMillis
        } else {
          // Reported process start to first post at front is greater than 1 min. That's
          // no good, let's fallback to class init as starting point.
          // https://dev.to/pyricau/android-vitals-when-did-my-app-start-24p4
          classInitUptimeMillis
        }
      } else {
        // Should not happen: first post at front hasn't run yet
        reportedStartUptimeMillis
      }
    } else {
      classInitUptimeMillis
    }

  internal fun firstClassLoaded() {
    // Prior to Android P, PerfsAppStartListener is the first loaded class
    // that we have control over. On Android P+, it's PerfAppComponentFactory.
    // They both call firstClassLoaded() when their class get loaded.
    // This method does nothing but forces a call to this constructor, and classInitUptimeMillis
    // gets set.
  }

  val appStart: AppStart
    get() = if (initialized) {
      appStartData
    } else {
      NoAppStartData(notInitializedReason)
    }

  internal fun init(context: Context) {
    val initCalledUptimeMillis = SystemClock.uptimeMillis()
    val initCalledCurrentTimeMillis = System.currentTimeMillis()
    val initCalledRealtimeMillis = SystemClock.elapsedRealtime()
    // Should only be init on the main thread, once.
    if (!isMainThread || initialized) {
      return
    }
    if (context !is Application) {
      notInitializedReason =
        "Perfs.init() called with a non Application context: ${context::class.java}"
      return
    }
    val myProcessInfo = when (val myProcessInfo = MyProcess.findMyProcessInfo(context)) {
      is ErrorRetrievingMyProcessData -> {
        notInitializedReason = "Error retrieving process info, " +
          "${myProcessInfo.throwable::class.java.simpleName}: " +
          "${myProcessInfo.throwable.message}"
        return
      }
      is MyProcessData -> {
        myProcessInfo
      }
    }
    initialized = true
    notInitializedReason = ""
    val application: Application = context
    ApplicationHolder.install(application, myProcessInfo.info.importance == IMPORTANCE_FOREGROUND)

    val elapsedSinceProcessStartRealtimeMillis =
      SystemClock.elapsedRealtime() - myProcessInfo.processStartRealtimeMillis
    // We rely on SystemClock.uptimeMillis() for performance related metrics.
    // See https://dev.to/pyricau/android-vitals-what-time-is-it-2oih
    val processStartUptimeMillis =
      SystemClock.uptimeMillis() - elapsedSinceProcessStartRealtimeMillis

    // "handleBindApplication" because Process.setStartTimes is called from
    // ActivityThread.handleBindApplication
    val handleBindApplicationElapsedUptimeMillis = if (Build.VERSION.SDK_INT >= 24) {
      Process.getStartUptimeMillis() - processStartUptimeMillis
    } else {
      null
    }

    val handler = Handler(Looper.getMainLooper())
    handler.post {
      firstPostUptimeMillis = SystemClock.uptimeMillis()
      val firstPost = appStartData.elapsedSinceStart()
      appStartData = appStartData.copy(firstPostElapsedUptimeMillis = firstPost)
    }

    val processInfoAfterFirstPost = ActivityManager.RunningAppProcessInfo()
    try {
      ActivityManager.getMyMemoryState(processInfoAfterFirstPost)
    } catch (ignored: Throwable) {
      // This should never happen, but IPCs like to occasionally fail.
    }

    // TODO Ideally we'd do all startup pref reads from a background thread.
    // Some Android implementations perform a disk read when loading shared prefs async.
    val oldPolicy = StrictMode.allowThreadDiskReads()
    val prefs = try {
      application.getSharedPreferences("Perfs", Context.MODE_PRIVATE)
    } finally {
      StrictMode.setThreadPolicy(oldPolicy)
    }

    val lastAppVisibilityState = prefs.getString(LAST_VISIBILITY_STATE, null)
      ?.let { stateName -> if (stateName == VISIBLE.name) VISIBLE else INVISIBLE }

    val lastVisibilityChangeCurrentTimeMillis =
      prefs.getLong(LAST_VISIBILITY_CHANGE_CURRENT_MILLIS, -1)
    val lastVisibilityChangeElapsedTimeMillis =
      lastVisibilityChangeCurrentTimeMillis.let { lastTime ->
        if (lastTime == -1L) {
          null
        } else {
          initCalledCurrentTimeMillis - lastTime
        }
      }
    val lastAppAliveElapsedTimeMillis =
      prefs.getLong(LAST_ALIVE_CURRENT_MILLIS, -1).let { lastTime ->
        if (lastTime == -1L) {
          null
        } else {
          initCalledCurrentTimeMillis - lastTime
        }
      }
    val processInfo = myProcessInfo.info
    appStartData = AppStartData(
      processStartRealtimeMillis = myProcessInfo.processStartRealtimeMillis,
      processStartUptimeMillis = processStartUptimeMillis,
      handleBindApplicationElapsedUptimeMillis = handleBindApplicationElapsedUptimeMillis,
      firstAppClassLoadElapsedUptimeMillis = classInitUptimeMillis - processStartUptimeMillis,
      perfsInitElapsedUptimeMillis = initCalledUptimeMillis - processStartUptimeMillis,
      importance = processInfo.importance,
      importanceAfterFirstPost = processInfoAfterFirstPost.importance,
      importanceReasonCode = processInfo.importanceReasonCode,
      importanceReasonPid = processInfo.importanceReasonPid,
      startImportanceReasonComponent = processInfo.importanceReasonComponent?.toShortString(),
      lastAppVisibilityState = lastAppVisibilityState,
      lastVisibilityChangeElapsedTimeMillis = lastVisibilityChangeElapsedTimeMillis,
      lastAppAliveElapsedTimeMillis = lastAppAliveElapsedTimeMillis,
      appTasks = myProcessInfo.appTasks,
      classLoaderInstantiatedElapsedUptimeMillis =
      classLoaderInstantiatedUptimeMillis?.let { it - processStartUptimeMillis },
      applicationInstantiatedElapsedUptimeMillis =
      applicationInstantiatedUptimeMillis?.let { it - processStartUptimeMillis }
    )

    object : Runnable {
      override fun run() {
        prefs.edit()
          // We can't use SystemClock.uptimeMillis() as the device might restart in between.
          .putLong(LAST_ALIVE_CURRENT_MILLIS, System.currentTimeMillis())
          .apply()
        handler.postDelayed(this, 1000)
      }
    }.apply { run() }

    Looper.myQueue()
      .addIdleHandler {
        val firstIdle = appStartData.elapsedSinceStart()
        appStartData = appStartData.copy(firstIdleElapsedUptimeMillis = firstIdle)
        false
      }

    val appVisibilityStateCallback: (AppVisibilityState) -> Unit = { state ->
      // Note: we only start tracking app lifecycle state after the first resume. If the app has
      // never been resumed, the last state will stay null.
      prefs.edit()
        .putString(LAST_VISIBILITY_STATE, state.name)
        // We can't use SystemClock.uptimeMillis() as the device might restart in between.
        .putLong(LAST_VISIBILITY_CHANGE_CURRENT_MILLIS, System.currentTimeMillis())
        .apply()
    }

    val appLaunchedCallback: (Launch) -> Unit = { launch ->
      val preLaunchState = computePreLaunchState(launch)

      val (launchStartUptimeMillis, invisibleDurationRealtimeMillis) = computeLaunchTimes(
        preLaunchState,
        lastVisibilityChangeCurrentTimeMillis,
        lastAppVisibilityState,
        initCalledUptimeMillis,
        launch,
        initCalledRealtimeMillis
      )
      if (isTracingLaunch) {
        SafeTrace.endAsyncSection(LAUNCH_TRACE_NAME)
        isTracingLaunch = false
      }
      PapaEventListener.sendEvent(
        AppLaunch(
          preLaunchState = preLaunchState,
          durationUptimeMillis = launch.endUptimeMillis - launchStartUptimeMillis,
          trampolined = launch.trampoline,
          invisibleDurationRealtimeMillis = invisibleDurationRealtimeMillis,
          startUptimeMillis = launchStartUptimeMillis
        )
      )
    }
    application.trackActivityLifecycle(
      { updateAppStartData ->
        appStartData = updateAppStartData(appStartData)
      },
      appVisibilityStateCallback, appLaunchedCallback
    )

    application.trackAppUpgrade { updateAppStartData ->
      appStartData = updateAppStartData(appStartData)
    }
    handler.postAtFrontOfQueueAsync {
      val firstPostAtFront = appStartData.elapsedSinceStart()
      appStartData = appStartData.copy(firstPostAtFrontElapsedUptimeMillis = firstPostAtFront)
    }
  }

  private fun computeLaunchTimes(
    preLaunchState: PreLaunchState,
    lastVisibilityChangeCurrentTimeMillis: Long,
    lastAppVisibilityState: AppVisibilityState?,
    initCalledUptimeMillis: Long,
    launch: Launch,
    initCalledRealtimeMillis: Long
  ) = if (preLaunchState.launchType == COLD) {
    val launchStartUptimeMillis = bindApplicationStartUptimeMillis
    val invisibleDurationRealtimeMillis =
      if (lastVisibilityChangeCurrentTimeMillis != -1L) {
        if (lastAppVisibilityState == INVISIBLE) {
          val millisSinceForegroundStart =
            SystemClock.uptimeMillis() - launchStartUptimeMillis
          val currentTimeMillisAtForegroundStart =
            System.currentTimeMillis() - millisSinceForegroundStart
          currentTimeMillisAtForegroundStart - lastVisibilityChangeCurrentTimeMillis
        } else {
          // In the cold start case, launchStartUptimeMillis is before
          // initCalledUptimeMillis. lastAppAliveElapsedTimeMillis is computed from
          // initCalled, so we need to remove the time between start and init.
          appStartData.lastAppAliveElapsedTimeMillis?.let { lastAppAliveElapsedTimeMillis ->
            val startToInitUptimeMillis = initCalledUptimeMillis - launchStartUptimeMillis
            lastAppAliveElapsedTimeMillis - startToInitUptimeMillis
          }
        }
      } else {
        null
      }
    launchStartUptimeMillis to invisibleDurationRealtimeMillis
  } else {
    val launchStartUptimeMillis = launch.startUptimeMillis
    val invisibleDurationRealtimeMillis = launch.invisibleDurationRealtimeMillis
      ?: if (lastVisibilityChangeCurrentTimeMillis != -1L) {
        if (lastAppVisibilityState == INVISIBLE) {
          // Compute the clock time that has passed from the last time the app went from
          // foreground to background until the launch start.
          val millisSinceLaunch =
            SystemClock.uptimeMillis() - launchStartUptimeMillis
          val currentTimeMillisAtLaunch =
            System.currentTimeMillis() - millisSinceLaunch
          currentTimeMillisAtLaunch - lastVisibilityChangeCurrentTimeMillis
        } else {
          // If the last known app lifecycle state change was visible,
          // then it's probably that the app got killed while visible.
          // We have a tick updated every second while the app is alive, so we can use
          // that to figure out when the app was last alive in foreground.
          appStartData.lastAppAliveElapsedTimeMillis?.let { lastAppAliveElapsedTimeMillis ->
            val initToLaunchRealtimeMillis =
              launch.startRealtimeMillis - initCalledRealtimeMillis
            // lastAppAliveElapsedTimeMillis is the time from the last save until init.
            lastAppAliveElapsedTimeMillis + initToLaunchRealtimeMillis
          }
        }
      } else {
        // The app never entered foreground before.
        null
      }
    launch.startUptimeMillis to invisibleDurationRealtimeMillis
  }

  private fun computePreLaunchState(launch: Launch): PreLaunchState {
    val launchStartedAfterFirstPost = firstPostUptimeMillis?.let {
      launch.startUptimeMillis > it
    } ?: false

    val preLaunchState = when {
      // launch started after first post => warm or hot launch
      launchStartedAfterFirstPost -> {
        when (launch.activityStartingTransition) {
          CREATED_NO_STATE -> NO_ACTIVITY_NO_SAVED_STATE
          CREATED_WITH_STATE -> NO_ACTIVITY_BUT_SAVED_STATE
          STARTED -> ACTIVITY_WAS_STOPPED
        }
      }
      // launch started before first post AND importance foreground => cold launch
      appStartData.importance == IMPORTANCE_FOREGROUND -> {
        // Note: this relies on appUpdateData which is computed on a background thread
        // on app start, so reading this at the latest possible point is best.
        when (val updateData = appStartData.appUpdateData) {
          is RealAppUpdateData -> {
            when (updateData.status) {
              FIRST_START_AFTER_CLEAR_DATA -> NO_PROCESS_FIRST_LAUNCH_AFTER_CLEAR_DATA
              FIRST_START_AFTER_FRESH_INSTALL -> NO_PROCESS_FIRST_LAUNCH_AFTER_INSTALL
              FIRST_START_AFTER_UPGRADE -> NO_PROCESS_FIRST_LAUNCH_AFTER_UPGRADE
              NORMAL_START -> NO_PROCESS
            }
          }
          else -> NO_PROCESS
        }
      }
      // launch started before first post but importance not foreground => lukewarm launch
      else -> {
        // Launch started before the first post but process importance wasn't
        // foreground. This means the process was started for another reason but while
        // starting the process the activity manager then decided to foreground the app.
        // We're therefore classifying this launch as a warm start, which means we'll use
        // startUptimeMillis as its start time, which could yield much a  time than perceived
        // by users. Would be nice if we had a way to know when the system changed its
        // mind.
        PROCESS_WAS_LAUNCHING_IN_BACKGROUND
      }
    }
    return preLaunchState
  }

  internal fun firstComponentInstantiated(componentName: String) {
    checkMainThread()
    if (!initialized) {
      return
    }
    appStartData = appStartData.copy(
      firstComponentInstantiated = AndroidComponentEvent(
        componentName,
        appStartData.elapsedSinceStart()
      )
    )
  }

  fun reportFullyDrawn() {
    checkMainThread()
    if (!initialized || reportedFullDrawn) {
      return
    }
    reportedFullDrawn = true
    onCurrentOrNextFrameRendered { frameRenderedUptime ->
      appStartData = appStartData.copy(
        firstFrameAfterFullyDrawnElapsedUptimeMillis = frameRenderedUptime.inWholeMilliseconds - appStartData.processStartUptimeMillis
      )
    }
  }

  fun customFirstEvent(
    eventName: String,
    extra: Any? = null
  ) {
    checkMainThread()
    if (!initialized || eventName in appStartData.customFirstEvents) {
      return
    }
    val elapsedUptimeMillis = appStartData.elapsedSinceStart()
    appStartData = appStartData.copy(
      customFirstEvents = appStartData.customFirstEvents + mapOf(
        eventName to (elapsedUptimeMillis to extra)
      )
    )
  }
}
