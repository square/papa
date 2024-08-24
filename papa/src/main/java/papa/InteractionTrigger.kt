package papa

import papa.internal.checkMainThread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

data class InteractionTrigger(
  val triggerUptime: Duration,
  val name: String,
  private var interactionTrace: InteractionTrace? = null,
  /**
   * Additional details that can be carried by [InteractionTrigger]
   */
  val payload: Any? = null
) {

  fun takeOverInteractionTrace(): InteractionTrace? {
    checkMainThread()
    try {
      return interactionTrace
    } finally {
      interactionTrace = null
    }
  }

  companion object {
    fun triggerNow(
      name: String,
      payload: Any? = null
    ): InteractionTrigger {
      val nowUptimeNanos = System.nanoTime()
      val interactionTrace = InteractionTrace.startNow(name)
      val triggerUptime = nowUptimeNanos.nanoseconds
      return InteractionTrigger(triggerUptime, name, interactionTrace, payload)
    }
  }
}