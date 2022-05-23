package tart.legacy

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
import tart.AndroidComponentEvent
import tart.AppLaunchType.COLD
import tart.AppLaunchType.HOT
import tart.AppLaunchType.WARM
import tart.AppLifecycleState.PAUSED
import tart.AppLifecycleState.RESUMED
import tart.AppStart
import tart.AppStart.AppStartData
import tart.AppStart.NoAppStartData
import tart.AppUpdateData.RealAppUpdateData
import tart.AppUpdateStartStatus.FIRST_START_AFTER_CLEAR_DATA
import tart.AppUpdateStartStatus.FIRST_START_AFTER_FRESH_INSTALL
import tart.AppUpdateStartStatus.FIRST_START_AFTER_UPGRADE
import tart.AppUpdateStartStatus.NORMAL_START
import tart.OkTrace
import tart.PreLaunchState
import tart.TartEvent.AppLaunch
import tart.TartEventListener
import tart.internal.AppUpdateDetector.Companion.trackAppUpgrade
import tart.internal.ApplicationHolder
import tart.internal.MyProcess
import tart.internal.MyProcess.ErrorRetrievingMyProcessData
import tart.internal.MyProcess.MyProcessData
import tart.internal.PerfsActivityLifecycleCallbacks.Companion.trackActivityLifecycle
import tart.internal.WarmPrelaunchState
import tart.internal.WarmPrelaunchState.CREATED_NO_STATE
import tart.internal.WarmPrelaunchState.CREATED_WITH_STATE
import tart.internal.WarmPrelaunchState.STARTED
import tart.internal.enforceMainThread
import tart.internal.isOnMainThread
import tart.internal.onCurrentFrameRendered
import tart.internal.onNextFrameRendered
import tart.internal.onNextPreDraw
import tart.internal.postAtFrontOfQueueAsync

/**
 * Singleton object centralizing state for app start and future other perf metrics.
 */
object Perfs {

  internal const val FOREGROUND_COLD_START_TRACE_NAME = "Class Load To Initial Display"
  internal const val FOREGROUND_WARM_START_TRACE_NAME = "Warm Start To Display"
  internal const val FOREGROUND_HOT_START_TRACE_NAME = "Hot Start To Display"
  private const val LAST_RESUMED_STATE = "lastResumedState"

  // String value kept for backward compat reasons
  private const val LAST_RESUMED_STATE_CHANGE_CURRENT_MILLIS = "lastResumedCurrentMillis"
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
  internal var afterFirstPost = false

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

  /**
   * Provides [AppStart] filled in with the latest information. Can be called from any thread.
   */
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
    if (!isOnMainThread() || initialized) {
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
      afterFirstPost = true
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

    val lastAppLifecycleState = prefs.getString(LAST_RESUMED_STATE, null)
      ?.let { stateName -> if (stateName == RESUMED.name) RESUMED else PAUSED }

    val lastAppLifecycleStateChangedCurrentTimeMillis =
      prefs.getLong(LAST_RESUMED_STATE_CHANGE_CURRENT_MILLIS, -1)
    val lastAppLifecycleStateChangedElapsedTimeMillis =
      lastAppLifecycleStateChangedCurrentTimeMillis.let { lastTime ->
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
      lastAppLifecycleState = lastAppLifecycleState,
      lastAppLifecycleStateChangedElapsedTimeMillis = lastAppLifecycleStateChangedElapsedTimeMillis,
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

    var enteredBackgroundForWarmStartRealtimeMillis: Long? = null

    application.trackActivityLifecycle(
      { updateAppStartData ->
        appStartData = updateAppStartData(appStartData)
      },
      { state, activity, warmPrelaunchState, startUptimeMillis, startRealtimeMillis ->
        // Note: we only start tracking app lifecycle state after the first resume. If the app has
        // never been resumed, the last state will stay null.
        prefs.edit()
          .putString(LAST_RESUMED_STATE, state.name)
          // We can't use SystemClock.uptimeMillis() as the device might restart in between.
          .putLong(LAST_RESUMED_STATE_CHANGE_CURRENT_MILLIS, System.currentTimeMillis())
          .apply()

        // state is either RESUMED (1 activity resumed) or PAUSED (0 activity resumed). When
        // PAUSED, we might still have an activity in started state rendering behind another
        // foreground app. At that stage, we consider the app to have entered background and
        // will use the PAUSE time for the background duration. However, if the app becomes resumed
        // again without first being stopped, we won't report an app launch. So this breaks down
        // a bit if the app is paused, then stopped somewhat later then resumed, as the total
        // time will include the paused time instead of just the stopped time.
        if (state == PAUSED) {
          enteredBackgroundForWarmStartRealtimeMillis = startRealtimeMillis
        } else if (warmPrelaunchState != WarmPrelaunchState.RESUMED) {
          // We skipped RESUMED because going from pause to resume isn't considered a launch, only
          // going from STOPPED to STARTED.
          val resumedAfterFirstPost = afterFirstPost

          val preLaunchState = when {
            resumedAfterFirstPost -> {
              when (warmPrelaunchState) {
                CREATED_NO_STATE -> PreLaunchState.NO_ACTIVITY_NO_SAVED_STATE
                CREATED_WITH_STATE -> PreLaunchState.NO_ACTIVITY_BUT_SAVED_STATE
                STARTED -> PreLaunchState.ACTIVITY_WAS_STOPPED
                WarmPrelaunchState.RESUMED -> error("resumed is skipped")
              }
            }
            appStartData.importance == IMPORTANCE_FOREGROUND -> {
              // Note: this relies on appUpdateData which is computed on a background thread
              // on app start, so reading this at the latest possible point is best.
              when (val updateData = appStartData.appUpdateData) {
                is RealAppUpdateData -> {
                  when (updateData.status) {
                    FIRST_START_AFTER_CLEAR_DATA -> PreLaunchState.NO_PROCESS_FIRST_LAUNCH_AFTER_CLEAR_DATA
                    FIRST_START_AFTER_FRESH_INSTALL -> PreLaunchState.NO_PROCESS_FIRST_LAUNCH_AFTER_INSTALL
                    FIRST_START_AFTER_UPGRADE -> PreLaunchState.NO_PROCESS_FIRST_LAUNCH_AFTER_UPGRADE
                    NORMAL_START -> PreLaunchState.NO_PROCESS
                  }
                }
                else -> PreLaunchState.NO_PROCESS
              }
            }
            else -> {
              // We got resumed before the first post but process importance wasn't
              // foreground. This means the process was started for another reason but while
              // starting the process the activity manager then decided to foreground the app.
              // We're therefore classifying this launch as a warm start, which means we'll use
              // startUptimeMillis as its start time, which could yield much a  time than perceived
              // by users. Would be nice if we had a way to know when the system changed its
              // mind.
              PreLaunchState.PROCESS_WAS_LAUNCHING_IN_BACKGROUND
            }
          }

          val (launchStartUptimeMillis, backgroundDurationRealtimeMillis) = if (preLaunchState.launchType == COLD) {
            val launchStartUptimeMillis = bindApplicationStartUptimeMillis
            val backgroundDurationRealtimeMillis =
              if (lastAppLifecycleStateChangedCurrentTimeMillis != -1L) {
                if (lastAppLifecycleState == PAUSED) {
                  val millisSinceForegroundStart =
                    SystemClock.uptimeMillis() - launchStartUptimeMillis
                  val currentTimeMillisAtForegroundStart =
                    System.currentTimeMillis() - millisSinceForegroundStart
                  currentTimeMillisAtForegroundStart - lastAppLifecycleStateChangedCurrentTimeMillis
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
            launchStartUptimeMillis to backgroundDurationRealtimeMillis
          } else {
            val launchStartUptimeMillis = startUptimeMillis
            val backgroundDurationRealtimeMillis =
              enteredBackgroundForWarmStartRealtimeMillis?.let {
                // The process entered foreground then background and now foreground again. We
                // can use realtime to compute the exact time spent in background.
                launchStartUptimeMillis - it
              } ?: if (lastAppLifecycleStateChangedCurrentTimeMillis != -1L) {
                if (lastAppLifecycleState == PAUSED) {
                  // Compute the clock time that has passed from the last time the app went from
                  // foreground to background until the launch start.
                  val millisSinceForegroundStart =
                    SystemClock.uptimeMillis() - launchStartUptimeMillis
                  val currentTimeMillisAtForegroundStart =
                    System.currentTimeMillis() - millisSinceForegroundStart
                  currentTimeMillisAtForegroundStart - lastAppLifecycleStateChangedCurrentTimeMillis
                } else {
                  // If the last known app lifecycle state change was entering foreground,
                  // then it's probably that the app got killed while in foreground.
                  // We have a tick updated every second while the app is alive, so we can use
                  // that to figure out when the app was last alive in foreground.
                  appStartData.lastAppAliveElapsedTimeMillis?.let { lastAppAliveElapsedTimeMillis ->
                    val initToStartRealtimeMillis = startRealtimeMillis - initCalledRealtimeMillis
                    // lastAppAliveElapsedTimeMillis is the time from the last save until init.
                    lastAppAliveElapsedTimeMillis + initToStartRealtimeMillis
                  }
                }
              } else {
                // The app never entered foreground before.
                null
              }
            startUptimeMillis to backgroundDurationRealtimeMillis
          }

          activity.window.onNextPreDraw {
            onCurrentFrameRendered { frameRenderedUptimeMillis ->
              val sectionName = when (preLaunchState.launchType) {
                COLD -> FOREGROUND_COLD_START_TRACE_NAME
                WARM -> FOREGROUND_WARM_START_TRACE_NAME
                HOT -> FOREGROUND_HOT_START_TRACE_NAME
              }
              OkTrace.endAsyncSection(sectionName)
              TartEventListener.sendEvent(
                AppLaunch(
                  preLaunchState = preLaunchState,
                  startUptimeMillis = launchStartUptimeMillis,
                  endUptimeMillis = frameRenderedUptimeMillis,
                  backgroundDurationRealtimeMillis = backgroundDurationRealtimeMillis
                )
              )
            }
          }
        }
      }
    )

    application.trackAppUpgrade { updateAppStartData ->
      appStartData = updateAppStartData(appStartData)
    }
    handler.postAtFrontOfQueueAsync {
      val firstPostAtFront = appStartData.elapsedSinceStart()
      appStartData = appStartData.copy(firstPostAtFrontElapsedUptimeMillis = firstPostAtFront)
    }
  }

  internal fun firstComponentInstantiated(componentName: String) {
    enforceMainThread()
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

  /**
   * Functional equivalent to [android.app.Activity.reportFullyDrawn]. This lets app report when
   * they consider themselves ready, regardless of any prior view traversal and rendering.
   * The first call to [reportFullyDrawn] will update
   * [AppStartData.firstFrameAfterFullyDrawnElapsedUptimeMillis] on the next frame.
   */
  fun reportFullyDrawn() {
    enforceMainThread()
    if (!initialized || reportedFullDrawn) {
      return
    }
    reportedFullDrawn = true
    onNextFrameRendered { frameRenderedUptimeMillis ->
      appStartData = appStartData.copy(
        firstFrameAfterFullyDrawnElapsedUptimeMillis = frameRenderedUptimeMillis - appStartData.processStartUptimeMillis
      )
    }
  }

  @JvmStatic
  @JvmOverloads
  fun customFirstEvent(
    eventName: String,
    extra: Any? = null
  ) {
    enforceMainThread()
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
