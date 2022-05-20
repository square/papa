package tart

interface UserInteractionLatencyAnalytics {

  fun reportInteraction(
    interaction: Interaction,
    stateBeforeInteraction: AppState.Value,
    stateAfterInteraction: AppState.Value,
    reportStartUptimeMillis: Long,
    rawDurationUptimeMillis: Int,
    totalDurationUptimeMillis: Int,
    triggerData: TriggerData
  )

  sealed class TriggerData {
    abstract val triggerName: String
    abstract val triggerDurationMillis: Int

    class Found(
      override val triggerName: String,
      override val triggerDurationMillis: Int
    ) : TriggerData()

    object Unknown : TriggerData() {
      override val triggerName = "unknown"
      override val triggerDurationMillis = 0
    }
  }

  companion object {
    @Volatile
    var analytics: UserInteractionLatencyAnalytics? = null
  }
}
