package papa

import papa.internal.checkMainThread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

sealed interface InteractionTrigger {
  val triggerUptime: Duration
  val name: String
  fun takeOverInteractionTrace(): InteractionTrace?

  companion object {
    fun triggerNow(
      name: String,
    ): InteractionTrigger {
      val nowUptimeNanos = System.nanoTime()
      val interactionTrace = InteractionTrace.startNow(name)
      val triggerUptime = nowUptimeNanos.nanoseconds
      return SimpleInteractionTrigger(triggerUptime, name, interactionTrace)
    }
  }
}

class SimpleInteractionTrigger(
  override val triggerUptime: Duration,
  override val name: String,
  private var interactionTrace: InteractionTrace? = null,
) : InteractionTrigger {

  override fun takeOverInteractionTrace(): InteractionTrace? {
    checkMainThread()
    try {
      return interactionTrace
    } finally {
      interactionTrace = null
    }
  }

  override fun toString(): String {
    return "InteractionTrigger(name='$name', triggerUptime=$triggerUptime)"
  }
}

class InteractionTriggerWithPayload<T>(
  triggerUptime: Duration,
  name: String,
  interactionTrace: InteractionTrace?,
  val payload: T
) : InteractionTrigger by SimpleInteractionTrigger(triggerUptime, name, interactionTrace) {
  override fun toString(): String {
    return "InteractionTrigger(name='$name', triggerUptime=$triggerUptime, payload=$payload)"
  }
}
