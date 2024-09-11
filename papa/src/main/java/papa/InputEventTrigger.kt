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
    mutableListOf(OnFrameRenderedListener { renderedUptime = it })

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
      inputEventWindow.postOnWindowFrameRendered {
        for (callback in trigger.inputEventFrameRenderedCallbacks) {
          callback.onFrameRendered(it)
        }
        trigger.inputEventFrameRenderedCallbacks.clear()
      }
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