package tart

import java.util.concurrent.TimeUnit.NANOSECONDS

interface UserInteractionLatencyAnalytics {

  fun reportInteraction(
    interaction: Interaction,
    stateBeforeInteraction: AppState.Value,
    stateAfterInteraction: AppState.Value,
    reportStart: CpuDuration,
    durationFromStart: CpuDuration,
    totalDuration: CpuDuration,
    triggerData: TriggerData
  )

  sealed class TriggerData {
    abstract val triggerName: String
    abstract val triggerDuration: CpuDuration

    class Found(
      override val triggerName: String,
      override val triggerDuration: CpuDuration
    ) : TriggerData()

    object Unknown : TriggerData() {
      override val triggerName = "unknown"
      override val triggerDuration = CpuDuration(NANOSECONDS, 0, 0)
    }
  }

  companion object {
    @Volatile
    var analytics: UserInteractionLatencyAnalytics? = null
  }
}
