package papa.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import curtains.onNextDraw
import papa.ActivityOnCreateEvent
import papa.ActivityTouchEvent
import papa.AndroidComponentEvent
import papa.AppStart.AppStartData
import papa.AppVisibilityState
import papa.AppVisibilityState.INVISIBLE
import papa.AppVisibilityState.VISIBLE
import papa.Handlers
import papa.internal.LaunchTracker.Launch

/**
 * Reports first time occurrences of activity lifecycle related events to [papa.internal.Perfs].
 */
internal class PerfsActivityLifecycleCallbacks private constructor(
  private val appStartUpdateCallback: ((AppStartData) -> AppStartData) -> Unit,
  private val appVisibilityStateCallback: (AppVisibilityState) -> Unit,
  appLaunchedCallback: (Launch) -> Unit
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

  private val startedActivityHashes = mutableSetOf<String>()

  private class OnCreateRecord(
    val sameMessage: Boolean,
    val hasSavedState: Boolean,
  )

  private val launchTracker = LaunchTracker(appLaunchedCallback)

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

  override fun onActivityPreCreated(
    activity: Activity,
    savedInstanceState: Bundle?
  ) {
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
          Handlers.onCurrentMainThreadMessageFinished {
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
    val appWasInvisible = startedActivityHashes.isEmpty()
    launchTracker.pushLaunchInProgressDeadline()
    if (appWasInvisible) {
      launchTracker.appMightBecomeVisible(identityHash)
    }
    val hasSavedStated = savedInstanceState != null
    createdActivityHashes[identityHash] =
      OnCreateRecord(
        true,
        hasSavedStated
      )
    joinPost {
      if (identityHash in createdActivityHashes) {
        createdActivityHashes[identityHash] =
          OnCreateRecord(
            false,
            hasSavedStated
          )
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
    val appWasInvisible = startedActivityHashes.isEmpty()
    launchTracker.pushLaunchInProgressDeadline()
    if (appWasInvisible) {
      launchTracker.appMightBecomeVisible(identityHash)
      appVisibilityStateCallback(VISIBLE)
    }
    startedActivityHashes += identityHash
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

    val onCreateRecord = createdActivityHashes.getValue(identityHash)
    val startingTransition = if (onCreateRecord.sameMessage) {
      if (onCreateRecord.hasSavedState) {
        LaunchedActivityStartingTransition.CREATED_WITH_STATE
      } else {
        LaunchedActivityStartingTransition.CREATED_NO_STATE
      }
    } else {
      // We're bundling together the case where start and resume are in the same main thread
      // message and the case where resume happens later, which generally shouldn't happen for
      // a launch because a single resume would indicate that the app was previously visible
      // and we don't count that as a launch. However we're ready for anything and in case
      // a resume does happen sometimes later but as part of what we initially deemed a launch
      // sequence, then we'll just fallback to STARTED.
      LaunchedActivityStartingTransition.STARTED
    }
    launchTracker.onActivityResumed(activity, identityHash, startingTransition)
  }

  private fun recordActivityResumed(activity: Activity): String {
    val identityHash = Integer.toHexString(System.identityHashCode(activity))
    if (identityHash in resumedActivityHashes) {
      return identityHash
    }
    resumedActivityHashes += identityHash
    launchTracker.pushLaunchInProgressDeadline()
    return identityHash
  }

  override fun onActivityDestroyed(activity: Activity) {
    createdActivityHashes -= Integer.toHexString(System.identityHashCode(activity))
    launchTracker.pushLaunchInProgressDeadline()
  }

  override fun onActivityStopped(activity: Activity) {
    startedActivityHashes -= Integer.toHexString(System.identityHashCode(activity))
    if (startedActivityHashes.isEmpty()) {
      launchTracker.appBecameInvisible()
      appVisibilityStateCallback(INVISIBLE)
    }
    launchTracker.pushLaunchInProgressDeadline()
  }

  override fun onActivityPaused(activity: Activity) {
    resumedActivityHashes -= Integer.toHexString(System.identityHashCode(activity))
    launchTracker.pushLaunchInProgressDeadline()
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
      appVisibilityStateCallback: (AppVisibilityState) -> Unit,
      appLaunchedCallback: (Launch) -> Unit
    ) {
      registerActivityLifecycleCallbacks(
        PerfsActivityLifecycleCallbacks(
          appStartUpdateCallback,
          appVisibilityStateCallback,
          appLaunchedCallback
        )
      )
    }
  }
}
