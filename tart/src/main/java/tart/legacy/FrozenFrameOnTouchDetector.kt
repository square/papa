package tart.legacy

import android.content.res.Resources.NotFoundException
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import curtains.Curtains
import curtains.OnRootViewAddedListener
import curtains.TouchEventInterceptor
import curtains.phoneWindow
import curtains.touchEventInterceptors
import curtains.windowAttachCount
import tart.legacy.FrozenFrameOnTouchDetector.install
import tart.onNextFrameDisplayed

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
    var touchDownWaitingRender: MotionEvent? = null
    var repeatTouchDownCount = 0
    var pressedViewName: String? = null

    Curtains.onRootViewsChangedListeners += OnRootViewAddedListener { view ->
      view.phoneWindow?.let { window ->
        if (view.windowAttachCount == 0) {
          window.touchEventInterceptors += TouchEventInterceptor { motionEvent, dispatch ->
            if (motionEvent.action == MotionEvent.ACTION_DOWN) {
              if (touchDownWaitingRender != null) {
                repeatTouchDownCount++
              } else {
                val handledTime = SystemClock.uptimeMillis()
                if (handledTime - motionEvent.eventTime > FrozenFrameOnTouch.FROZEN_FRAME_THRESHOLD) {
                  val windowTitle = window.attributes.title.toString().substringAfter("/")
                  touchDownWaitingRender = MotionEvent.obtain(motionEvent)
                  window.onNextFrameDisplayed { frameDisplayedUptimeMillis ->
                    val localTouchDownWaitingRender = touchDownWaitingRender!!
                    val sentTime = localTouchDownWaitingRender.eventTime
                    val sentToReceive = handledTime - sentTime
                    val receiveToFrame = frameDisplayedUptimeMillis - handledTime
                    listener(
                      FrozenFrameOnTouch(
                        activityName = windowTitle,
                        repeatTouchDownCount = repeatTouchDownCount,
                        handledElapsedUptimeMillis = sentToReceive,
                        frameElapsedUptimeMillis = receiveToFrame,
                        pressedView = pressedViewName
                      )
                    )
                    localTouchDownWaitingRender.recycle()
                    touchDownWaitingRender = null
                    repeatTouchDownCount = 0
                    pressedViewName = null
                  }
                }
              }
            }
            val dispatchState = dispatch(motionEvent)

            // Clickable views become pressed when they receive ACTION_DOWN, unless they're in a
            // scrollable container, in which case Android waits 100ms (ViewConfiguration#TAP_TIMEOUT)
            // before setting it to pressed in case it's a scroll motion rather than a tap. If
            // ACTION_UP happens before the waiting is done, then the view is set pressed when
            // ACTION_UP is processed.
            // When the main thread is blocked, we expect ACTION_DOWN and ACTION_UP to be handled one
            // after the other so checking for a pressed view after either event should work.
            val action = motionEvent.action

            val processedFrozenDown =
              (touchDownWaitingRender != null &&
                action == MotionEvent.ACTION_DOWN &&
                repeatTouchDownCount == 0)

            val processedUpForFrozenDown = touchDownWaitingRender != null &&
              action == MotionEvent.ACTION_UP &&
              motionEvent.downTime == touchDownWaitingRender!!.eventTime

            if (processedFrozenDown || processedUpForFrozenDown) {
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
  }

  internal fun ViewGroup.findPressedView(): View? {
    if (isPressed) {
      return this
    }
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
