package tart.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import curtains.onNextDraw
import tart.legacy.ActivityEvent
import tart.legacy.ActivityOnCreateEvent
import tart.legacy.ActivityTouchEvent
import tart.legacy.AppLifecycleState
import tart.legacy.AppLifecycleState.PAUSED
import tart.legacy.AppLifecycleState.RESUMED
import tart.legacy.AppStart.AppStartData

/**
 * Reports first time occurrences of activity lifecycle related events to [tart.Perfs].
 */
internal class PerfsActivityLifecycleCallbacks private constructor(
  private val appStartUpdateCallback: ((AppStartData) -> AppStartData) -> Unit,
  private val appLifecycleCallback: (AppLifecycleState, Activity, StartTemperature, Long) -> Unit
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

  private class OnStartRecord(val sameMessage: Boolean, val startUptimeMillis: Long)

  private val startedActivityHashes = mutableMapOf<String, OnStartRecord>()

  private class OnCreateRecord(
    val sameMessage: Boolean,
    val hasSavedState: Boolean,
    val startUptimeMillis: Long
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
          activityName = activityClassName,
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
                activityName = activityClassName,
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
    val startUptimeMillis = SystemClock.uptimeMillis()
    val identityHash = Integer.toHexString(System.identityHashCode(activity))
    if (identityHash in createdActivityHashes) {
      return
    }
    val hasSavedStated = savedInstanceState != null
    createdActivityHashes[identityHash] = OnCreateRecord(true, hasSavedStated, startUptimeMillis)
    joinPost {
      if (identityHash in createdActivityHashes) {
        createdActivityHashes[identityHash] =
          OnCreateRecord(false, hasSavedStated, startUptimeMillis)
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
    val startUptimeMillis = SystemClock.uptimeMillis()
    val identityHash = Integer.toHexString(System.identityHashCode(activity))
    if (identityHash in startedActivityHashes) {
      return
    }
    startedActivityHashes[identityHash] = OnStartRecord(true, startUptimeMillis)
    joinPost {
      if (identityHash in startedActivityHashes) {
        startedActivityHashes[identityHash] = OnStartRecord(false, startUptimeMillis)
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
      val (warmStartTemperature, startUptimeMillis) = if (onCreateRecord.sameMessage) {
        if (onCreateRecord.hasSavedState) {
          StartTemperature.CREATED_WITH_STATE to onCreateRecord.startUptimeMillis
        } else {
          StartTemperature.CREATED_NO_STATE to onCreateRecord.startUptimeMillis
        }
      } else {
        val onStartRecord = startedActivityHashes.getValue(identityHash)
        if (onStartRecord.sameMessage) {
          StartTemperature.STARTED to onStartRecord.startUptimeMillis
        } else {
          StartTemperature.RESUMED to resumedActivityHashes.getValue(identityHash).startUptimeMillis
        }
      }
      appLifecycleCallback(RESUMED, activity, warmStartTemperature, startUptimeMillis)
    }
  }

  private fun recordActivityResumed(activity: Activity): String {
    val startUptimeMillis = SystemClock.uptimeMillis()
    val identityHash = Integer.toHexString(System.identityHashCode(activity))
    if (identityHash in resumedActivityHashes) {
      return identityHash
    }
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
      // Temperature and start don't matter when pausing. This should be a separate thing.
      appLifecycleCallback(PAUSED, activity, StartTemperature.RESUMED, SystemClock.uptimeMillis())
    }
  }

  private fun updateAppStart(
    activityClassName: String,
    block: (AppStartData, ActivityEvent) -> AppStartData
  ) {
    appStartUpdateCallback { appStart ->
      val elapsedMillis = SystemClock.uptimeMillis() - appStart.processStartUptimeMillis
      val activityEvent = ActivityEvent(activityClassName, elapsedMillis)
      block(appStart, activityEvent)
    }
  }

  companion object {
    internal fun Application.trackActivityLifecycle(
      appStartUpdateCallback: ((AppStartData) -> AppStartData) -> Unit,
      appLifecycleCallback: (AppLifecycleState, Activity, StartTemperature, Long) -> Unit
    ) {
      registerActivityLifecycleCallbacks(
        PerfsActivityLifecycleCallbacks(appStartUpdateCallback, appLifecycleCallback)
      )
    }
  }
}
