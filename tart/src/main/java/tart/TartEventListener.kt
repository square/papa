package tart

import tart.internal.checkMainThread
import java.util.concurrent.CopyOnWriteArrayList

fun interface TartEventListener {
  /**
   * Invoked on the main thread. Implementations should post any significant work to a background
   * thread.
   */
  fun onEvent(event: TartEvent)

  companion object : Registry {
    private val listeners = CopyOnWriteArrayList<TartEventListener>()

    override fun install(
      listener: TartEventListener
    ): Registration {
      listeners += listener
      return Registration(listener)
    }

    internal fun sendEvent(event: TartEvent) {
      checkMainThread()
      for (listener in listeners) {
        listener.onEvent(event)
      }
    }
  }

  interface Registry {
    fun install(listener: TartEventListener): Registration
  }

  class Registration(private val listener: TartEventListener) {
    fun dispose() {
      listeners -= listener
    }
  }
}