package papa

import androidx.tracing.Trace

fun interface InteractionTrace {
  fun endTrace()

  companion object {
    fun startNow(
      name: String
    ): InteractionTrace {
      val cookie = System.nanoTime().rem(Int.MAX_VALUE).toInt()
      Trace.beginAsyncSection(name, cookie)
      return InteractionTrace {
        Trace.endAsyncSection(name, cookie)
      }
    }
  }
}
