package papa

import papa.AppState.Value.NumberValue
import papa.AppState.Value.SerializedAsync
import papa.AppState.Value.StringValue

sealed class AppState {

  sealed class Value : AppState() {
    class StringValue(val string: String) : Value() {
      override fun toString(): String {
        return string
      }
    }
    class NumberValue(val number: Number) : Value() {
      override fun toString(): String {
        return number.toString()
      }
    }

    class SerializedAsync(val value: Any) : Value() {
      override fun toString(): String {
        return value.toString()
      }
    }
    object NoValue : Value()
  }

  class ValueOnFrameRendered(val onFrameRendered: () -> Value) : AppState()

  companion object {
    fun value(string: String) = StringValue(string)
    fun value(number: Number) = NumberValue(number)

    /**
     * Serialized to a format appropriate for analytics, typically json.
     */
    fun serializedAsync(serializedAsync: Any) = SerializedAsync(serializedAsync)

    /**
     * Delay retrieving the value until immediately after the frame has been rendered. This allows
     * providing app state such as the number of recycler view rows rendered.
     */
    fun valueOnFrameRendered(onFrameRendered: () -> Value) = ValueOnFrameRendered(onFrameRendered)
  }
}
