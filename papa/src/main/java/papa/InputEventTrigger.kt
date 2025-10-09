package papa

import android.view.InputEvent
import android.view.Window
import papa.Choreographers.postOnWindowFrameRendered
import kotlin.time.Duration

class InputEventTrigger private constructor(
  val inputEvent: InputEvent,
  val deliveryUptime: Duration
) {
  var renderedUptime: Duration? = null
    private set
  val rendered: Boolean
    get() = renderedUptime != null

  private val inputEventFrameRenderedCallbacks =
    mutableListOf<OnFrameRenderedListener>(object : OnFrameRenderedListener {
      override fun onFrameRendered(frameRenderedUptime: Duration) {
        renderedUptime = frameRenderedUptime
      }
    })

  fun onInputEventFrameRendered(listener: OnFrameRenderedListener) {
    Handlers.checkOnMainThread()
    renderedUptime?.let {
      listener.onFrameRendered(it)
      return
    }
    inputEventFrameRenderedCallbacks.add(listener)
  }

  companion object {
    fun createTrackingWhenFrameRendered(
      inputEventWindow: Window,
      inputEvent: InputEvent,
      deliveryUptime: Duration
    ): InputEventTrigger {
      val trigger = InputEventTrigger(inputEvent, deliveryUptime)
      // When compiling with Java11 we get AbstractMethodError at runtime when this is a lambda.
      @Suppress("ObjectLiteralToLambda")
      inputEventWindow.postOnWindowFrameRendered(object : OnFrameRenderedListener {
        override fun onFrameRendered(frameRenderedUptime: Duration) {
          for (callback in trigger.inputEventFrameRenderedCallbacks) {
            callback.onFrameRendered(frameRenderedUptime)
          }
          trigger.inputEventFrameRenderedCallbacks.clear()
        }
      })
      return trigger
    }
  }
}

fun InteractionTrigger.toInputEventTriggerOrNull(): InteractionTriggerWithPayload<InputEventTrigger>? {
  @Suppress("UNCHECKED_CAST")
  return if (this is InteractionTriggerWithPayload<*> && payload is InputEventTrigger) {
    this as InteractionTriggerWithPayload<InputEventTrigger>
  } else {
    null
  }
}
