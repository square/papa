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
import android.view.Choreographer
import tart.AppLaunch
import tart.PreLaunchState
import tart.internal.AppUpdateDetector.Companion.trackAppUpgrade
import tart.internal.ApplicationHolder
import tart.internal.MyProcess
import tart.internal.MyProcess.ErrorRetrievingMyProcessData
import tart.internal.MyProcess.MyProcessData
import tart.internal.PerfsActivityLifecycleCallbacks.Companion.trackActivityLifecycle
import tart.internal.enforceMainThread
import tart.internal.isOnMainThread
import tart.internal.lastFrameTimeNanos
import tart.internal.onNextPreDraw
import tart.internal.postAtFrontOfQueueAsync
import tart.legacy.AppLifecycleState.PAUSED
import tart.legacy.AppLifecycleState.RESUMED
import tart.legacy.AppStart.AppStartData
import tart.legacy.AppStart.NoAppStartData
import tart.legacy.AppUpdateData.RealAppUpdateData
import tart.legacy.AppUpdateStartStatus.FIRST_START_AFTER_CLEAR_DATA
import tart.legacy.AppUpdateStartStatus.FIRST_START_AFTER_FRESH_INSTALL
import tart.legacy.AppUpdateStartStatus.FIRST_START_AFTER_UPGRADE
import tart.legacy.AppUpdateStartStatus.NORMAL_START
import tart.legacy.AppWarmStart.Temperature
import tart.legacy.AppWarmStart.Temperature.CREATED_NO_STATE
import tart.legacy.AppWarmStart.Temperature.CREATED_WITH_STATE
import tart.legacy.AppWarmStart.Temperature.STARTED
import tart.onCurrentFrameDisplayed
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Singleton object centralizing state for app start and future other perf metrics.
 */
object Perfs {

  private const val LAST_RESUMED_STATE = "lastResumedState"
  private const val LAST_RESUMED_CURRENT_MILLIS = "lastResumedCurrentMillis"
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

  /**
   * Can be set to listen to app warm starts.
   */
  var appWarmStartListener: ((AppWarmStart) -> Unit)? = null

  internal val appLaunchListeners = CopyOnWriteArrayList<(AppLaunch) -> Unit>()

  private val appLaunchListener: (AppLaunch) -> Unit = { appLaunch ->
    for (listener in appLaunchListeners) {
      listener(appLaunch)
    }
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
    ApplicationHolder.install(application)

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
    var afterFirstPost = false
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
    val lastAppLifecycleStateChangedElapsedTimeMillis =
      prefs.getLong(LAST_RESUMED_CURRENT_MILLIS, -1).let { lastTime ->
        if (lastTime == -1L) {
          null
        } else {
          System.currentTimeMillis() - lastTime
        }
      }
    val lastAppAliveElapsedTimeMillis =
      prefs.getLong(LAST_ALIVE_CURRENT_MILLIS, -1).let { lastTime ->
        if (lastTime == -1L) {
          null
        } else {
          System.currentTimeMillis() - lastTime
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

    var enteredBackgroundForWarmStartUptimeMillis = initCalledUptimeMillis

    application.trackActivityLifecycle(
      { updateAppStartData ->
        appStartData = updateAppStartData(appStartData)
      },
      { state, activity, temperature, resumedUptimeMillis ->
        // Note: we only start tracking app lifecycle state after the first resume. If the app has
        // never been resumed, the last state will stay null.
        prefs.edit()
          .putString(LAST_RESUMED_STATE, state.name)
          // We can't use SystemClock.uptimeMillis() as the device might restart in between.
          .putLong(LAST_RESUMED_CURRENT_MILLIS, System.currentTimeMillis())
          .apply()

        if (state == PAUSED) {
          enteredBackgroundForWarmStartUptimeMillis = SystemClock.uptimeMillis()
        } else if (temperature != Temperature.RESUMED) {
          // We skipped RESUMED because going from pause to resume isn't considered a launch
          val resumedAfterFirstPost = afterFirstPost
          val backgroundElapsedUptimeMillis =
            resumedUptimeMillis - enteredBackgroundForWarmStartUptimeMillis
          activity.window.onNextPreDraw {
            val frameTimeNanos = Choreographer.getInstance().lastFrameTimeNanos
            activity.window.onCurrentFrameDisplayed(frameTimeNanos) { frameEndUptimeMillis ->
              val resumeToNextFrameElapsedUptimeMillis =
                frameEndUptimeMillis - resumedUptimeMillis

              (appStart as? AppStartData)?.let { appStartData ->
                if (resumedAfterFirstPost) {
                  val launchState = when (temperature) {
                    CREATED_NO_STATE -> PreLaunchState.NO_ACTIVITY_NO_SAVED_STATE
                    CREATED_WITH_STATE -> PreLaunchState.NO_ACTIVITY_BUT_SAVED_STATE
                    STARTED -> PreLaunchState.ACTIVITY_WAS_STOPPED
                    Temperature.RESUMED -> error("resumed is skipped")
                  }
                  appLaunchListener(AppLaunch(launchState, resumedUptimeMillis, frameEndUptimeMillis))
                } else {
                  if (appStartData.importance == IMPORTANCE_FOREGROUND) {
                    val preLaunchState = when (val updateData = appStartData.appUpdateData) {
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
                    appLaunchListener(
                      AppLaunch(
                        preLaunchState,
                        bindApplicationStartUptimeMillis,
                        frameEndUptimeMillis
                      )
                    )
                  } else {
                    // TODO this will yield much smaller time than perceived by users
                    // unless we had a way to know when the system changed its mind.
                    appLaunchListener(
                      AppLaunch(
                        PreLaunchState.PROCESS_WAS_LAUNCHING_IN_BACKGROUND,
                        resumedUptimeMillis,
                        frameEndUptimeMillis
                      )
                    )
                  }
                }
              }

              // A change of state before the first post indicates a cold start. This tracks warm
              // and hot starts.
              if (resumedAfterFirstPost) {
                appWarmStartListener?.let { listener ->
                  listener(
                    AppWarmStart(
                      temperature = temperature,
                      backgroundElapsedUptimeMillis = backgroundElapsedUptimeMillis,
                      resumeToNextFrameElapsedUptimeMillis = resumeToNextFrameElapsedUptimeMillis
                    )
                  )
                }
              }
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
      firstComponentInstantiated = ComponentInstantiatedEvent(
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
    Choreographer.getInstance()
      .postFrameCallback {
        appStartData = appStartData.copy(
          firstFrameAfterFullyDrawnElapsedUptimeMillis = appStartData.elapsedSinceStart()
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
