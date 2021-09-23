package tart.legacy
import android.os.SystemClock

/**
 * A cold start refers to an app's starting from scratch: the system's process has not, until this
 * start, created the app's process. Cold starts happen in cases such as your app's being launched for
 * the first time since the device booted, or since the system killed the app.
 */
sealed class AppStart {
  data class AppStartData(
    /**
     * Elapsed realtime when the process started, read from "/proc/$pid/stat".
     */
    val processStartRealtimeMillis: Long,
    /**
     * Elapsed uptime when the process started, computed from [processStartUptimeMillis] and
     * [android.os.SystemClock.uptimeMillis]
     */
    val processStartUptimeMillis: Long,

    /**
     * Elapsed uptime (since process fork) when the app starts doing custom work, in
     * `ActivityThread.handleBindApplication()`, as measured by
     * [android.os.Process.getStartUptimeMillis]. Available on API 24+.
     */
    val handleBindApplicationElapsedUptimeMillis: Long?,

    val firstAppClassLoadElapsedUptimeMillis: Long,

    /**
     * Elapsed uptime (since process fork) when [Perfs.init] was called, ie when the
     * PerfsAppStartListener content provider was created.
     */
    val perfsInitElapsedUptimeMillis: Long,

    /**
     * A standard process start to show an activity should have [importance] set to
     * [android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND].
     * @see [android.app.ActivityManager.RunningAppProcessInfo.importance]
     */
    val importance: Int,
    /**
     * Same as [importance] but capture right after the first post. The theory is that if the
     * startup importance wasn't 100 but an activity was posted before the first post, then it
     * must have sneaked in and our importance after first post should have changed to 100.
     */
    val importanceAfterFirstPost: Int,
    /**
     * @see [android.app.ActivityManager.RunningAppProcessInfo.importanceReasonCode]
     */
    val importanceReasonCode: Int,
    /**
     * @see [android.app.ActivityManager.RunningAppProcessInfo.importanceReasonPid]
     */
    val importanceReasonPid: Int,
    /**
     * @see [android.app.ActivityManager.RunningAppProcessInfo.importanceReasonComponent]
     */
    val startImportanceReasonComponent: String?,

    /**
     * The latest [AppLifecycleState] value before the application was killed. Applications with
     * resumed activities are in foreground and therefore this should be [AppLifecycleState.PAUSED]
     * most of the time, except for crashes.
     * null if this is the first start where we're tracking this.
     */
    val lastAppLifecycleState: AppLifecycleState?,

    /**
     * The elapsed time between when [lastAppLifecycleState] was last saved and when the app started.
     * Note: the interval is tracked using [System.currentTimeMillis] because the device could have
     * restarted since. The interval ends when this [AppStart] event is created.
     * null if this is the first start where we're tracking this.
     */
    val lastAppLifecycleStateChangedElapsedTimeMillis: Long?,
    /**
     * The elapsed time between when the previous process was still alive and when the app started.
     * Note: the interval is tracked using [System.currentTimeMillis] because the device could have
     * restarted since. The interval ends when this [AppStart] event is created.
     * null if this is the first start where we're tracking this.
     */
    val lastAppAliveElapsedTimeMillis: Long?,

    /**
     * Maps to [android.app.ActivityManager.getAppTasks] on API 29+, empty list otherwise.
     */
    val appTasks: List<AppTask>,
    /**
     * Elapsed uptime (since process fork) after the app class loader was instantiated
     */
    val classLoaderInstantiatedElapsedUptimeMillis: Long?,
    /**
     * Elapsed uptime (since process fork) after the application class was instantiated
     */
    val applicationInstantiatedElapsedUptimeMillis: Long?,
    /**
     * Elapsed time (since process fork) when the main thread first idles.
     */
    val firstIdleElapsedUptimeMillis: Long? = null,
    /**
     * Details on successive app updates.
     */
    val appUpdateData: AppUpdateData? = null,
    /**
     * Elapsed time (since process fork) when the main thread runs the first app posted message.
     * This should help us (in)validate the hypothesis that the first post runs right after
     * Application.onCreate() and would be a good approximation for it.
     */
    val firstPostElapsedUptimeMillis: Long? = null,
    /**
     * Elapsed time (since process fork) when the main thread runs the first app message,
     * posted at the front of the queue.
     * The theory is that the post will run after the activity is created but postAtFront will
     * run in between Application.onCreate() and Activity.onCreate()
     */
    val firstPostAtFrontElapsedUptimeMillis: Long? = null,
    /**
     * Elapsed time (since process fork) before the first component class was instantiated
     */
    val firstComponentInstantiated: ComponentInstantiatedEvent? = null,
    val firstActivityOnCreate: ActivityOnCreateEvent? = null,
    val firstActivityOnStart: ActivityEvent? = null,
    val firstActivityOnResume: ActivityEvent? = null,
    val firstGlobalLayout: ActivityEvent? = null,
    val firstPreDraw: ActivityEvent? = null,
    val firstDraw: ActivityEvent? = null,
    val firstIdleAfterFirstDraw: ActivityEvent? = null,
    val firstPostAfterFirstDraw: ActivityEvent? = null,
    val firstTouchEvent: ActivityTouchEvent? = null,
    /**
     * Elapsed time (since process fork) before the first frame after [Perfs.reportFullyDrawn] was
     * called.
     */
    val firstFrameAfterFullyDrawnElapsedUptimeMillis: Long? = null,
    val customFirstEvents: Map<String, Pair<Long, Any?>> = emptyMap()
  ) : AppStart() {
    /**
     * Approximate duration of [android.app.Application.onCreate]. This is an upper bound as it also
     * includes some extra work from Android (adds ~100ms).
     * Available on Android P+.
     */
    val applicationOnCreateDurationMillis: Long?
      get() = if (applicationInstantiatedElapsedUptimeMillis != null &&
        firstComponentInstantiated != null
      ) {
        firstComponentInstantiated.elapsedUptimeMillis - applicationInstantiatedElapsedUptimeMillis
      } else {
        null
      }

    fun elapsedSinceStart() = SystemClock.uptimeMillis() - processStartUptimeMillis
  }

  data class NoAppStartData(val reason: String) : AppStart()
}