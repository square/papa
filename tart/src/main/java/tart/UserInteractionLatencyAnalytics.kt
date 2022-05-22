package tart

interface UserInteractionLatencyAnalytics {

  fun reportInteraction(
    interaction: Interaction,
    stateBeforeInteraction: AppState.Value,
    stateAfterInteraction: AppState.Value,
    startUptimeMillis: Long,
    durationFromStartUptimeMillis: Long,
    triggerData: TriggerData
  )

  sealed class TriggerData {
    abstract val triggerName: String
    abstract val triggerDurationUptimeMillis: Long

    class Found(
      override val triggerName: String,
      override val triggerDurationUptimeMillis: Long
    ) : TriggerData()

    object Unknown : TriggerData() {
      override val triggerName = "unknown"
      override val triggerDurationUptimeMillis = 0L
    }
  }

  companion object {
    @Volatile
    var analytics: UserInteractionLatencyAnalytics? = null
  }
}
