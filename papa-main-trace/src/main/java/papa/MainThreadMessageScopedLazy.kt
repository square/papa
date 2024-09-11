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
    check(MainThreadMessageSpy.enabled) {
      "Can't use a MainThreadMessageScopedLazy when MainThreadMessageSpy is not enabled."
    }
    val value = provider()
    valueOrNull = value
    MainThreadMessageSpy.onCurrentMessageFinished {
      valueOrNull = null
    }
    return value
  }
}

fun <T> mainThreadMessageScopedLazy(provider: () -> T) = MainThreadMessageScopedLazy(provider)
