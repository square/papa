package tart.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import tart.legacy.ActivityEvent
import tart.legacy.ActivityOnCreateEvent
import tart.legacy.ActivityTouchEvent
import tart.legacy.AppLifecycleState
import tart.legacy.AppLifecycleState.PAUSED
import tart.legacy.AppLifecycleState.RESUMED
import tart.legacy.AppStart.AppStartData
import tart.legacy.AppWarmStart
import tart.legacy.AppWarmStart.Temperature
import tart.legacy.AppWarmStart.Temperature.CREATED_NO_STATE
import tart.legacy.AppWarmStart.Temperature.CREATED_WITH_STATE

/**
 * Reports first time occurrences of activity lifecycle related events to [Perfs].
 */
internal class PerfsActivityLifecycleCallbacks private constructor(
  private val appStartUpdateCallback: ((AppStartData) -> AppStartData) -> Unit,
  private val appLifecycleCallback: (AppLifecycleState, AppWarmStart.Temperature) -> Unit
) : ActivityLifecycleCallbacksAdapter {

  private var firstActivityCreated = false
  private var firstActivityStarted = false
  private var firstActivityResumed = false
  private var firstGlobalLayout = false
  private var firstPreDraw = false
  private var firstDraw = false
  private var firstTouchEvent = false

  private val handler = Handler(Looper.getMainLooper())

  private val resumedActivityHashes = mutableSetOf<String>()

  private var warmStartTemperature = Temperature.RESUMED

  override fun onActivityCreated(
    activity: Activity,
    savedInstanceState: Bundle?
  ) {
    warmStartTemperature = if (savedInstanceState != null) {
      CREATED_WITH_STATE
    } else {
      CREATED_NO_STATE
    }
    handler.post {
      warmStartTemperature = Temperature.RESUMED
    }
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
      activity.onNextPreDraw {
        if (!firstPreDraw) {
          firstPreDraw = true
          updateAppStart(activity.javaClass.name) { appStart, activityEvent ->
            appStart.copy(firstPreDraw = activityEvent)
          }
        }
      }
    }
    if (!firstDraw) {
      activity.onNextDraw {
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
          handler.postAtFrontOfQueue {
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

  override fun onActivityStarted(activity: Activity) {
    if (!firstActivityStarted) {
      firstActivityStarted = true
      updateAppStart(activity.javaClass.name) { appStart, activityEvent ->
        appStart.copy(firstActivityOnStart = activityEvent)
      }
    }
  }

  override fun onActivityResumed(activity: Activity) {

    if (!firstActivityResumed) {
      firstActivityResumed = true
      updateAppStart(activity.javaClass.name) { appStart, activityEvent ->
        appStart.copy(firstActivityOnResume = activityEvent)
      }
    }

    val hadResumedActivity = resumedActivityHashes.isNotEmpty()
    resumedActivityHashes += Integer.toHexString(System.identityHashCode(activity))
    val hasResumedActivityNow = resumedActivityHashes.isNotEmpty()
    if (!hadResumedActivity && hasResumedActivityNow) {
      appLifecycleCallback(RESUMED, warmStartTemperature)
    }
  }

  override fun onActivityPaused(activity: Activity) {
    val hadResumedActivity = resumedActivityHashes.isNotEmpty()
    resumedActivityHashes -= Integer.toHexString(System.identityHashCode(activity))
    val hasResumedActivityNow = resumedActivityHashes.isNotEmpty()
    if (hadResumedActivity && !hasResumedActivityNow) {
      // Temperature doesn't matter when pausing.
      appLifecycleCallback(PAUSED, Temperature.RESUMED)
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
      appLifecycleCallback: (AppLifecycleState, Temperature) -> Unit
    ) {
      registerActivityLifecycleCallbacks(
        PerfsActivityLifecycleCallbacks(appStartUpdateCallback, appLifecycleCallback)
      )
    }
  }
}
