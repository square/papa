package papa

import papa.internal.checkMainThread
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

fun interface PapaEventListener {
  /**
   * Invoked on the main thread. Implementations should post any significant work to a background
   * thread.
   */
  fun onEvent(event: PapaEvent)

  companion object : Registry {
    private val listeners = CopyOnWriteArrayList<PapaEventListener>()

    override fun install(
      listener: PapaEventListener
    ): Closeable {
      listeners += listener
      return Closeable {
        listeners -= listener
      }
    }

    internal fun sendEvent(event: PapaEvent) {
      checkMainThread()
      for (listener in listeners) {
        listener.onEvent(event)
      }
    }
  }

  interface Registry {
    fun install(listener: PapaEventListener): Closeable
  }
}