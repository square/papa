package tart.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import curtains.onNextDraw
import tart.ActivityOnCreateEvent
import tart.ActivityTouchEvent
import tart.AndroidComponentEvent
import tart.AppLifecycleState
import tart.AppLifecycleState.PAUSED
import tart.AppLifecycleState.RESUMED
import tart.AppStart.AppStartData
import tart.OkTrace
import tart.internal.Perfs.FOREGROUND_HOT_START_TRACE_NAME
import tart.internal.Perfs.FOREGROUND_WARM_START_TRACE_NAME

/**
 * Reports first time occurrences of activity lifecycle related events to [tart.legacy.Perfs].
 */
internal class PerfsActivityLifecycleCallbacks private constructor(
  private val appStartUpdateCallback: ((AppStartData) -> AppStartData) -> Unit,
  private val appLifecycleCallback: (AppLifecycleState, Activity, WarmPrelaunchState, Long, Long) -> Unit
) : ActivityLifecycleCallbacksAdapter {

  private var firstActivityCreated = false
  private var firstActivityStarted = false
  private var firstActivityResumed = false
  private var firstGlobalLayout = false
  private var firstPreDraw = false
  private var firstDraw = false
  private var firstTouchEvent = false

  private val handler = Handler(Looper.getMainLooper())

  private class OnResumeRecord(val startUptimeMillis: Long)

  private val resumedActivityHashes = mutableMapOf<String, OnResumeRecord>()

  private class OnStartRecord(
    val sameMessage: Boolean,
    val startUptimeMillis: Long,
    val startRealtimeMillis: Long
  )

  private val startedActivityHashes = mutableMapOf<String, OnStartRecord>()

  private class OnCreateRecord(
    val sameMessage: Boolean,
    val hasSavedState: Boolean,
    val startUptimeMillis: Long,
    val startRealtimeMillis: Long
  )

  private val createdActivityHashes = mutableMapOf<String, OnCreateRecord>()

  private val joinedPosts = mutableListOf<() -> Unit>()

  private fun joinPost(post: () -> Unit) {
    val scheduled = joinedPosts.isNotEmpty()
    joinedPosts += post
    if (!scheduled) {
      handler.post {
        for (joinedPost in joinedPosts) {
          joinedPost()
        }
        joinedPosts.clear()
      }
    }
  }

  override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
    // Record timestamp earlier on Android versions that support it.
    recordActivityCreated(activity, savedInstanceState)
  }

  override fun onActivityCreated(
    activity: Activity,
    savedInstanceState: Bundle?
  ) {
    recordActivityCreated(activity, savedInstanceState)
    if (!firstActivityCreated) {
      firstActivityCreated = true
      val activityClassName = activity.javaClass.name
      val restoredState = savedInstanceState != null
      appStartUpdateCallback { appStart ->
        val elapsedMillis = SystemClock.uptimeMillis() - appStart.processStartUptimeMillis
        val activityEvent = ActivityOnCreateEvent(
          name = activityClassName,
          restoredState = restoredState,
          elapsedUptimeMillis = elapsedMillis,
          intent = activity.intent
        )
        appStart.copy(firstActivityOnCreate = activityEvent)
      }
    }
    if (!firstGlobalLayout) {
      activity.onNextGlobalLayout {
        if (!firstGlobalLayout) {
          firstGlobalLayout = true
          updateAppStart(activity.javaClass.name) { appStart, activityEvent ->
            appStart.copy(firstGlobalLayout = activityEvent)
          }
        }
      }
    }
    if (!firstPreDraw) {
      activity.window.onNextPreDraw {
        if (!firstPreDraw) {
          firstPreDraw = true
          updateAppStart(activity.javaClass.name) { appStart, activityEvent ->
            appStart.copy(firstPreDraw = activityEvent)
          }
        }
      }
    }
    if (!firstDraw) {
      activity.window.onNextDraw {
        if (!firstDraw) {
          firstDraw = true
          val activityClassName = activity.javaClass.name
          updateAppStart(activityClassName) { appStart, activityEvent ->
            appStart.copy(firstDraw = activityEvent)
          }
          onNextMainThreadIdle {
            updateAppStart(activityClassName) { appStart, activityEvent ->
              appStart.copy(firstIdleAfterFirstDraw = activityEvent)
            }
          }
          handler.postAtFrontOfQueueAsync {
            updateAppStart(activityClassName) { appStart, activityEvent ->
              appStart.copy(firstPostAfterFirstDraw = activityEvent)
            }
          }
        }
      }
    }
    if (!firstTouchEvent) {
      activity.onNextTouchEvent { motionEvent ->
        if (!firstTouchEvent) {
          firstTouchEvent = true
          val activityClassName = activity.javaClass.name
          appStartUpdateCallback { appStart ->
            val elapsedMillis = SystemClock.uptimeMillis() - appStart.processStartUptimeMillis
            val eventSentElapsedMillis = motionEvent.eventTime - appStart.processStartUptimeMillis
            appStart.copy(
              firstTouchEvent = ActivityTouchEvent(
                name = activityClassName,
                elapsedUptimeMillis = elapsedMillis,
                eventSentElapsedMillis = eventSentElapsedMillis,
                rawX = motionEvent.rawX,
                rawY = motionEvent.rawY
              )
            )
          }
        }
      }
    }
  }

  private fun recordActivityCreated(
    activity: Activity,
    savedInstanceState: Bundle?
  ) {
    val identityHash = Integer.toHexString(System.identityHashCode(activity))
    if (identityHash in createdActivityHashes) {
      return
    }
    val startUptimeMillis = SystemClock.uptimeMillis()
    val startRealtimeMillis = SystemClock.elapsedRealtime()
    if (resumedActivityHashes.isEmpty() && Perfs.afterFirstPost) {
      // We're entering foreground for a warm startup
      OkTrace.beginAsyncSection(FOREGROUND_WARM_START_TRACE_NAME)
    }
    val hasSavedStated = savedInstanceState != null
    createdActivityHashes[identityHash] =
      OnCreateRecord(true, hasSavedStated, startUptimeMillis, startRealtimeMillis)
    joinPost {
      if (identityHash in createdActivityHashes) {
        createdActivityHashes[identityHash] =
          OnCreateRecord(false, hasSavedStated, startUptimeMillis, startRealtimeMillis)
      }
    }
  }

  override fun onActivityPreStarted(activity: Activity) {
    recordActivityStarted(activity)
  }

  override fun onActivityStarted(activity: Activity) {
    recordActivityStarted(activity)
    if (!firstActivityStarted) {
      firstActivityStarted = true
      updateAppStart(activity.javaClass.name) { appStart, activityEvent ->
        appStart.copy(firstActivityOnStart = activityEvent)
      }
    }
  }

  private fun recordActivityStarted(activity: Activity) {
    val identityHash = Integer.toHexString(System.identityHashCode(activity))
    if (identityHash in startedActivityHashes) {
      return
    }
    val startUptimeMillis = SystemClock.uptimeMillis()
    val startRealtimeMillis = SystemClock.elapsedRealtime()
    if (resumedActivityHashes.isEmpty()
      && Perfs.afterFirstPost
      // Warm startup not already started by onCreate()
      && !createdActivityHashes.getValue(identityHash).sameMessage
    ) {
      // We're entering foreground for a warm startup
      OkTrace.beginAsyncSection(FOREGROUND_HOT_START_TRACE_NAME)
    }

    startedActivityHashes[identityHash] =
      OnStartRecord(true, startUptimeMillis, startRealtimeMillis)
    joinPost {
      if (identityHash in startedActivityHashes) {
        startedActivityHashes[identityHash] =
          OnStartRecord(false, startUptimeMillis, startRealtimeMillis)
      }
    }
  }

  override fun onActivityPreResumed(activity: Activity) {
    recordActivityResumed(activity)
  }

  override fun onActivityResumed(activity: Activity) {
    val identityHash = recordActivityResumed(activity)
    if (!firstActivityResumed) {
      firstActivityResumed = true
      updateAppStart(activity.javaClass.name) { appStart, activityEvent ->
        appStart.copy(firstActivityOnResume = activityEvent)
      }
    }
    val hadResumedActivity = resumedActivityHashes.size > 1
    if (!hadResumedActivity) {
      val onCreateRecord = createdActivityHashes.getValue(identityHash)
      val (warmStartTemperature, startUptimeMillis, startRealtimeMillis) = if (onCreateRecord.sameMessage) {
        if (onCreateRecord.hasSavedState) {
          Triple(
            WarmPrelaunchState.CREATED_WITH_STATE,
            onCreateRecord.startUptimeMillis,
            onCreateRecord.startRealtimeMillis
          )
        } else {
          Triple(
            WarmPrelaunchState.CREATED_NO_STATE,
            onCreateRecord.startUptimeMillis,
            onCreateRecord.startRealtimeMillis
          )
        }
      } else {
        val onStartRecord = startedActivityHashes.getValue(identityHash)
        if (onStartRecord.sameMessage) {
          Triple(
            WarmPrelaunchState.STARTED,
            onStartRecord.startUptimeMillis,
            onStartRecord.startRealtimeMillis
          )
        } else {
          Triple(
            WarmPrelaunchState.RESUMED,
            resumedActivityHashes.getValue(identityHash).startUptimeMillis,
            0L
          )
        }
      }
      appLifecycleCallback(
        RESUMED,
        activity,
        warmStartTemperature,
        startUptimeMillis,
        startRealtimeMillis
      )
    }
  }

  private fun recordActivityResumed(activity: Activity): String {
    val identityHash = Integer.toHexString(System.identityHashCode(activity))
    if (identityHash in resumedActivityHashes) {
      return identityHash
    }
    val startUptimeMillis = SystemClock.uptimeMillis()
    resumedActivityHashes[identityHash] = OnResumeRecord(startUptimeMillis)
    return identityHash
  }

  override fun onActivityDestroyed(activity: Activity) {
    createdActivityHashes -= Integer.toHexString(System.identityHashCode(activity))
  }

  override fun onActivityStopped(activity: Activity) {
    startedActivityHashes -= Integer.toHexString(System.identityHashCode(activity))
  }

  override fun onActivityPaused(activity: Activity) {
    val hadResumedActivity = resumedActivityHashes.isNotEmpty()
    resumedActivityHashes -= Integer.toHexString(System.identityHashCode(activity))
    val hasResumedActivityNow = resumedActivityHashes.isNotEmpty()
    if (hadResumedActivity && !hasResumedActivityNow) {
      val pauseUptimeMillis = SystemClock.uptimeMillis()
      val pauseRealtimeMillis = SystemClock.elapsedRealtime()
      // Temperature don't matter when pausing. This should be a separate thing.
      appLifecycleCallback(
        PAUSED,
        activity,
        WarmPrelaunchState.RESUMED,
        pauseUptimeMillis,
        pauseRealtimeMillis
      )
    }
  }

  private fun updateAppStart(
    activityClassName: String,
    block: (AppStartData, AndroidComponentEvent) -> AppStartData
  ) {
    appStartUpdateCallback { appStart ->
      val elapsedMillis = SystemClock.uptimeMillis() - appStart.processStartUptimeMillis
      val activityEvent = AndroidComponentEvent(activityClassName, elapsedMillis)
      block(appStart, activityEvent)
    }
  }

  companion object {
    internal fun Application.trackActivityLifecycle(
      appStartUpdateCallback: ((AppStartData) -> AppStartData) -> Unit,
      appLifecycleCallback: (AppLifecycleState, Activity, WarmPrelaunchState, Long, Long) -> Unit
    ) {
      registerActivityLifecycleCallbacks(
        PerfsActivityLifecycleCallbacks(appStartUpdateCallback, appLifecycleCallback)
      )
    }
  }
}
