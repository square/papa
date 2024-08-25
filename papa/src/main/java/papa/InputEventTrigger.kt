package papa

import android.view.InputEvent
import kotlin.time.Duration

data class InputEventTrigger(
  val inputEvent: InputEvent,
  val deliveryUptime: Duration
)

fun InteractionTrigger.toInputEventTriggerOrNull(): InteractionTriggerWithPayload<InputEventTrigger>? {
  @Suppress("UNCHECKED_CAST")
  return if (this is InteractionTriggerWithPayload<*> && payload is InputEventTrigger) {
    this as InteractionTriggerWithPayload<InputEventTrigger>
  } else {
    null
  }
}