package tart.internal

import android.content.res.Resources.NotFoundException
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import curtains.Curtains
import curtains.OnRootViewAddedListener
import curtains.TouchEventInterceptor
import curtains.phoneWindow
import curtains.touchEventInterceptors
import tart.internal.FrozenFrameOnTouchDetector.install
import tart.legacy.FrozenFrameOnTouch

/**
 * Detects when the interval of time between when a touch event is issued and the next frame is
 * greater than [FrozenFrameOnTouch.FROZEN_FRAME_THRESHOLD], leading to a bad experience (frozen
 * frame).
 *
 * [install] will install a [Curtains.onRootViewsChangedListeners] listener and then a touch
 * interceptor for each window.
 */
object FrozenFrameOnTouchDetector {

  fun install(listener: ((FrozenFrameOnTouch) -> Unit)) {
    val handler = Handler(Looper.getMainLooper())
    Curtains.onRootViewsChangedListeners += OnRootViewAddedListener { view ->
      view.phoneWindow?.let { window ->
        var touchDownWaitingRender: MotionEvent? = null
        var repeatTouchDownCount = 0
        var pressedViewName: String? = null

        val windowTitle = window.attributes.title.toString().substringAfter("/")

        window.touchEventInterceptors += TouchEventInterceptor { motionEvent, dispatch ->
          if (motionEvent.action == MotionEvent.ACTION_DOWN) {
            if (touchDownWaitingRender != null) {
              repeatTouchDownCount++
            } else {
              val handledTime = SystemClock.uptimeMillis()
              touchDownWaitingRender = MotionEvent.obtain(motionEvent)

              Choreographer.getInstance().postFrameCallback {
                // When there's a large batch of touch events in the queue, the choreographer frame will
                // execute after a few events are consumed but before they're all consumed, which could lead
                // to sending multiple FrozenFrameOnTouch events for a single frozen frame occurrence.
                // So here we post to a handler before reporting, which ensures that all events are consumed
                // only one event is sent and repeatTouchDownCount is accurate.
                // TODO this is the old way to measure frame end.
                handler.postAtFrontOfQueueAsync {
                  // By posting at the front of the queue we make sure that this message happens right
                  // after the frame, so we get full time the frame took.
                  val endOfFrameTime = SystemClock.uptimeMillis()
                  val localTouchDownWaitingRender = touchDownWaitingRender!!
                  val sentTime = localTouchDownWaitingRender.eventTime
                  if (endOfFrameTime - sentTime > FrozenFrameOnTouch.FROZEN_FRAME_THRESHOLD) {
                    val sentToReceive = handledTime - sentTime
                    val receiveToFrame = endOfFrameTime - handledTime
                    listener(
                      FrozenFrameOnTouch(
                        activityName = windowTitle,
                        repeatTouchDownCount = repeatTouchDownCount,
                        handledElapsedUptimeMillis = sentToReceive,
                        frameElapsedUptimeMillis = receiveToFrame,
                        pressedView = pressedViewName
                      )
                    )
                  }
                  localTouchDownWaitingRender.recycle()
                  touchDownWaitingRender = null
                  repeatTouchDownCount = 0
                  pressedViewName = null
                }
              }
            }
          }
          val dispatchState = dispatch(motionEvent)

          // Clickable views become pressed when they receive ACTION_DOWN, unless they're in a scrollable
          // container, in which case Android waits a bit in case it's a scroll motion rather than a tap.
          // If ACTION_UP happens before the waiting is done, then the view is show as pressed for a bit.
          // When the main thread is blocked, we expect ACTION_DOWN and ACTION_UP to be handled one
          // after the other so checking for a pressed view after either event should work.
          // If ACTION_DOWN is enqueued for a while but then the main thread unblocks, ACTION_DOWN
          // is processed and ACTION_UP enqueued, then ACTION_UP will be processed in the next
          // event batch which will happen after Handler().postAtFrontOfQueueAsync() runs which
          // means the touched view isn't in pressed state yet.
          val action = motionEvent.action
          if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) &&
            touchDownWaitingRender != null &&
            pressedViewName == null &&
            repeatTouchDownCount == 0
          ) {
            val pressedView = (window.decorView as? ViewGroup)?.findPressedView()
            if (pressedView != null) {
              pressedViewName = "${pressedView::class.java.name} ${pressedView.idResourceName()}"
            }
          }
          dispatchState
        }
      }
    }
  }

  private fun ViewGroup.findPressedView(): View? {
    for (i in 0 until childCount) {
      val child = getChildAt(i)
      if (child.isPressed) {
        return child
      }
      if (child is ViewGroup) {
        val pressed = child.findPressedView()
        if (pressed != null) {
          return pressed
        }
      }
    }
    return null
  }

  /**
   * Returns a human readable string representation of the view id.
   * Implementation is based on View.toString().
   */
  private fun View.idResourceName(): String {
    val viewId = id
    if (viewId == View.NO_ID) {
      return "NO_ID"
    }
    val resources =
      resources ?: return "UNKNOWN_ID_NO_RESOURCES $viewId #${Integer.toHexString(viewId)}"

    if (viewId <= 0) {
      return "UNKNOWN_ID_NEGATIVE $viewId #${Integer.toHexString(viewId)}"
    }

    if (viewId ushr 24 == 0) {
      return "UNKNOWN_ID_NO_PACKAGE $viewId #${Integer.toHexString(viewId)}"
    }
    return try {
      val packageName = when (viewId and -0x1000000) {
        0x7f000000 -> "app"
        0x01000000 -> "android"
        else -> resources.getResourcePackageName(viewId)
      }
      val typeName = resources.getResourceTypeName(viewId)
      val entryName = resources.getResourceEntryName(viewId)
      "$packageName:$typeName/$entryName"
    } catch (e: NotFoundException) {
      "UNKNOWN_ID_NOT_FOUND $viewId #${Integer.toHexString(viewId)}"
    }
  }
}
