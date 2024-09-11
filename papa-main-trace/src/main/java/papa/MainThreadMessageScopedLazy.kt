package papa

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class MainThreadMessageScopedLazy<T>(val provider: () -> T) : ReadOnlyProperty<Any?, T> {

  private var valueOrNull: T? = null

  override fun getValue(
    thisRef: Any?,
    property: KProperty<*>
  ): T {
    valueOrNull?.let {
      return it
    }
    val value = provider()
    valueOrNull = value
    Handlers.onCurrentMainThreadMessageFinished {
      valueOrNull = null
    }
    return value
  }
}

fun <T> mainThreadMessageScopedLazy(provider: () -> T) = MainThreadMessageScopedLazy(provider)
