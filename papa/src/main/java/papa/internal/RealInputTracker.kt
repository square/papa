package papa.internal

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import com.squareup.papa.R
import curtains.Curtains
import curtains.KeyEventInterceptor
import curtains.OnRootViewAddedListener
import curtains.TouchEventInterceptor
import curtains.keyEventInterceptors
import curtains.phoneWindow
import curtains.touchEventInterceptors
import curtains.windowAttachCount
import papa.DeliveredInput
import papa.InputTracker
import papa.SafeTrace
import papa.internal.FrozenFrameOnTouchDetector.findPressedView
import papa.safeTrace
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.nanoseconds

internal object RealInputTracker : InputTracker {

  override val motionEventTriggeringClick: DeliveredInput<MotionEvent>?
    get() = motionEventTriggeringClickLocal.get()?.input

  override val currentKeyEvent: DeliveredInput<KeyEvent>?
    get() = currentKeyEventLocal.get()

  private val motionEventTriggeringClickLocal = ThreadLocal<MotionEventHolder>()
  private val currentKeyEventLocal = ThreadLocal<DeliveredInput<KeyEvent>>()

  private val handler = Handler(Looper.getMainLooper())

  class MotionEventHolder(var input: DeliveredInput<MotionEvent>) : Choreographer.FrameCallback {

    private val choreographer = Choreographer.getInstance()

    override fun doFrame(frameTimeNanos: Long) {
      // We increase the counter right after the frame callback. This means we don't count a frame
      // if this event is consumed as part of the frame we did the increment in.
      // There's a slight edge case: if the event consumption triggered in between doFrame and
      // the post at front of queue, the count would be short by 1. We can live with this, it's
      // unlikely to happen unless and even is triggered from a postAtFront.
      mainHandler.postAtFrontOfQueueAsync {
        input = input.increaseFrameCount()
      }
      choreographer.postFrameCallback(this)
    }

    fun startCounting() {
      choreographer.postFrameCallback(this)
    }

    fun stopCounting() {
      choreographer.removeFrameCallback(this)
    }
  }

  private val listener = OnRootViewAddedListener { view ->
    view.phoneWindow?.let { window ->
      if (view.windowAttachCount == 0) {
        window.touchEventInterceptors += TouchEventInterceptor { motionEvent, dispatch ->
          // Note: what if we get 2 taps in a single dispatch loop? Then we're simply posting the
          // following: (recordTouch, onClick, clearTouch, recordTouch, onClick, clearTouch).
          val deliveryUptimeNanos = System.nanoTime()
          val isActionUp = motionEvent.action == MotionEvent.ACTION_UP

          val actionUpEventHolder = if (isActionUp) {
            val cookie = deliveryUptimeNanos.rem(Int.MAX_VALUE).toInt()
            SafeTrace.beginAsyncSection(TAP_INTERACTION_SECTION, cookie)
            MotionEventHolder(
              DeliveredInput(
                MotionEvent.obtain(motionEvent),
                deliveryUptimeNanos.nanoseconds,
                0
              ) {
                SafeTrace.endAsyncSection(TAP_INTERACTION_SECTION, cookie)
              }).also {
              it.startCounting()
            }
          } else {
            null
          }

          val setEventForPostedClick = Runnable {
            motionEventTriggeringClickLocal.set(actionUpEventHolder)
          }

          if (actionUpEventHolder != null) {
            handler.post(setEventForPostedClick)
          }

          val dispatchState = safeTrace({ MotionEvent.actionToString(motionEvent.action) }) {
            // Storing in case the action up is immediately triggering a click.
            motionEventTriggeringClickLocal.set(actionUpEventHolder)
            try {
              dispatch(motionEvent)
            } finally {
              motionEventTriggeringClickLocal.set(null)
            }
          }

          // Android posts onClick callbacks when it receives the up event. So here we leverage
          // afterTouchEvent at which point the onClick has been posted, and by posting then we ensure
          // we're clearing the event right after the onclick is handled.
          if (isActionUp) {
            val clearEventForPostedClick = Runnable {
              actionUpEventHolder!!.stopCounting()
              val actionUpEvent = actionUpEventHolder.input
              actionUpEvent.event.recycle()
              actionUpEvent.takeOverTraceEnd()?.invoke()
              if (motionEventTriggeringClickLocal.get() === actionUpEventHolder) {
                motionEventTriggeringClickLocal.set(null)
              }
            }

            val dispatchEnd = SystemClock.uptimeMillis()
            // AbsListView subclasses post clicks with a delay.
            // https://issuetracker.google.com/issues/232962097

            val viewPressedAfterDispatch = safeTrace("findPressedView()") {
              (window.decorView as? ViewGroup)?.findPressedView()
            }

            // Note: If a listview has no long press item listener, then long press are delivered
            // as a click on UP. In that case the delivery is immediate (no delay) and the post
            // dispatch state is not pressed (so we run into the else case here, which is good)
            if (viewPressedAfterDispatch is AbsListView) {
              val listViewTapDelay = ViewConfiguration.getPressedStateDuration()
              val setEventTime =
                (TimeUnit.NANOSECONDS.toMillis(deliveryUptimeNanos) + listViewTapDelay) - 1
              val clearEventTime = dispatchEnd + listViewTapDelay
              handler.removeCallbacks(setEventForPostedClick)
              handler.postAtTime(setEventForPostedClick, setEventTime)
              handler.postAtTime(clearEventForPostedClick, clearEventTime)
            } else {
              handler.post(clearEventForPostedClick)
            }
          }
          dispatchState
        }
        window.keyEventInterceptors += KeyEventInterceptor { keyEvent, dispatch ->
          val traceSectionName = keyEvent.traceSectionName
          val now = System.nanoTime()
          val cookie = now.rem(Int.MAX_VALUE).toInt()
          SafeTrace.beginAsyncSection(traceSectionName, cookie)
          val input = DeliveredInput(keyEvent, now.nanoseconds, 0) {
            SafeTrace.endAsyncSection(traceSectionName, cookie)
          }
          currentKeyEventLocal.set(input)
          try {
            dispatch(keyEvent)
          } finally {
            currentKeyEventLocal.set(null)
            input.takeOverTraceEnd()?.invoke()
          }
        }
      }
    }
  }

  internal fun install(application: Application) {
    if (application.resources.getBoolean(R.bool.papa_track_input_events)) {
      Curtains.onRootViewsChangedListeners += listener
    }
  }

  private const val INTERACTION_SUFFIX = "Interaction"
  private const val TAP_INTERACTION_SECTION = "Tap $INTERACTION_SUFFIX"

  val KeyEvent.name: String
    get() = "${keyActionToString()} ${KeyEvent.keyCodeToString(keyCode)}"

  private val KeyEvent.traceSectionName: String
    get() = "$name $INTERACTION_SUFFIX"

  private fun KeyEvent.keyActionToString(): String {
    @Suppress("DEPRECATION")
    return when (action) {
      KeyEvent.ACTION_DOWN -> "ACTION_DOWN"
      KeyEvent.ACTION_UP -> "ACTION_UP"
      KeyEvent.ACTION_MULTIPLE -> "ACTION_MULTIPLE"
      else -> action.toString()
    }
  }
}
