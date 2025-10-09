package papa

fun interface InteractionTrace {
  fun endTrace()

  companion object {
    fun startNow(
      name: String
    ): InteractionTrace {
      val cookie = System.nanoTime().rem(Int.MAX_VALUE).toInt()
      SafeTrace.beginAsyncSection(name, cookie)
      return InteractionTrace {
        SafeTrace.endAsyncSection(name, cookie)
      }
    }
  }
}
