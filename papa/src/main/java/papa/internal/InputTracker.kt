package papa.internal

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import papa.InputEventTrigger
import papa.InteractionTriggerWithPayload
import papa.MainThreadTriggerStack
import papa.SafeTrace
import papa.internal.FrozenFrameOnTouchDetector.findPressedView
import papa.safeTrace
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

internal object InputTracker {

  private val handler = Handler(Looper.getMainLooper())

  private val listener = OnRootViewAddedListener { view ->
    view.phoneWindow?.let { window ->
      if (view.windowAttachCount == 0) {
        window.touchEventInterceptors += TouchEventInterceptor { motionEvent, dispatch ->
          // Note: what if we get 2 taps in a single dispatch loop? Then we're simply posting the
          // following: (recordTouch, onClick, clearTouch, recordTouch, onClick, clearTouch).
          val deliveryUptimeNanos = System.nanoTime()
          val deliveryUptime = deliveryUptimeNanos.nanoseconds
          val isActionUp = motionEvent.action == MotionEvent.ACTION_UP

          //  We wrap the event in a holder so that we can actually replace the event within the
          //  holder. Why replace it? Because we want to increase the frame count over time, but we
          //  want to do that by swapping an immutable event, so that if we capture such event at
          //  time N and then the count gets updated at N + 1, the count update isn't reflected in
          //  the code that captured the event at time N.
          val actionUpTrigger = if (isActionUp) {
            val cookie = deliveryUptimeNanos.rem(Int.MAX_VALUE).toInt()
            SafeTrace.beginAsyncSection(TAP_INTERACTION_SECTION, cookie)
            val eventUptime = motionEvent.eventTime.milliseconds
            // Event bugfix: if event time is after delivery time, use delivery time as trigger time.
            val triggerUptime = if (eventUptime > deliveryUptime) deliveryUptime else eventUptime

            InteractionTriggerWithPayload(
              triggerUptime = triggerUptime,
              name = "tap",
              interactionTrace = {
                SafeTrace.endAsyncSection(TAP_INTERACTION_SECTION, cookie)
              },
              payload = InputEventTrigger(
                // Making a copy as motionEvent will get cleared once dispatched.
                inputEvent = MotionEvent.obtain(motionEvent),
                deliveryUptime = deliveryUptimeNanos.nanoseconds
              )
            )
          } else {
            null
          }

          val setEventForPostedClick = Runnable {
            MainThreadTriggerStack.pushTriggeredBy(actionUpTrigger!!)
          }

          if (actionUpTrigger != null) {
            handler.post(setEventForPostedClick)
          }

          val dispatchState = safeTrace({ MotionEvent.actionToString(motionEvent.action) }) {
            if (actionUpTrigger != null) {
              // In case the action up is immediately triggering a click (e.g. Compose)
              MainThreadTriggerStack.triggeredBy(actionUpTrigger, endTraceAfterBlock = false) {
                dispatch(motionEvent)
              }
            } else {
              dispatch(motionEvent)
            }
          }

          // Android posts onClick callbacks when it receives the up event. So here we leverage
          // afterTouchEvent at which point the onClick has been posted, and by posting then we ensure
          // we're clearing the event right after the onclick is handled.
          if (isActionUp) {
            val clearEventForPostedClick = Runnable {
              actionUpTrigger!!.takeOverInteractionTrace()?.endTrace()
              MainThreadTriggerStack.popTriggeredBy(actionUpTrigger)
            }

            val dispatchEnd = SystemClock.uptimeMillis()
            val viewPressedAfterDispatch = safeTrace("findPressedView()") {
              (window.decorView as? ViewGroup)?.findPressedView()
            }
            // AbsListView subclasses post clicks with a delay.
            // https://issuetracker.google.com/issues/232962097
            // Note: If a listview has no long press item listener, then long press are delivered
            // as a click on UP. In that case the delivery is immediate (no delay) and the post
            // dispatch state is not pressed (so we run into the else case here, which is good)
            if (viewPressedAfterDispatch is AbsListView) {
              val listViewTapDelayMillis = ViewConfiguration.getPressedStateDuration()
              val setEventTime =
                (deliveryUptime.inWholeMilliseconds + listViewTapDelayMillis) - 1
              val clearEventTime = dispatchEnd + listViewTapDelayMillis
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
          val deliveryUptimeNanos = System.nanoTime()
          val traceSectionName = keyEvent.traceSectionName
          val cookie = deliveryUptimeNanos.rem(Int.MAX_VALUE).toInt()
          SafeTrace.beginAsyncSection(traceSectionName, cookie)
          val deliveryUptime = deliveryUptimeNanos.nanoseconds
          val eventUptime = keyEvent.eventTime.milliseconds

          // Event bugfix: if event time is after delivery time, use delivery time as trigger time.
          val triggerUptime = if (eventUptime > deliveryUptime) deliveryUptime else eventUptime

          val trigger = InteractionTriggerWithPayload(
            triggerUptime = triggerUptime,
            name = "key ${keyEvent.name}",
            interactionTrace = {
              SafeTrace.endAsyncSection(traceSectionName, cookie)
            },
            payload = InputEventTrigger(
              inputEvent = keyEvent,
              deliveryUptime = deliveryUptimeNanos.nanoseconds
            )
          )
          MainThreadTriggerStack.triggeredBy(trigger, endTraceAfterBlock = true) {
            dispatch(keyEvent)
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
