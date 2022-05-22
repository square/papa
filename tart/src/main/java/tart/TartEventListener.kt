package tart

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors

fun interface TartEventListener {
  fun onEvent(event: TartEvent)

  companion object {
    private val listeners = CopyOnWriteArrayList<Pair<TartEventListener, Executor>>()

    fun install(
      listenerExecutor: Executor = Executors.newSingleThreadExecutor(),
      listener: TartEventListener
    ): Registration {
      val listenerAndExecutor = listener to listenerExecutor
      listeners += listenerAndExecutor
      return Registration(listenerAndExecutor)
    }

    internal fun sendEvent(event: TartEvent) {
      for ((listener, executor) in listeners) {
        executor.execute {
          listener.onEvent(event)
        }
      }
    }
  }

  class Registration(private val listenerAndExecutor: Pair<TartEventListener, Executor>) {

    fun dispose() {
      listeners -= listenerAndExecutor
    }
  }
}