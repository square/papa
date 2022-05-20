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

  class TriggerData(
    val triggerDurationMillisOrNull: Int?,
    val triggerName: String
  ) {
    companion object {
      val UNKNOWN = TriggerData(null, "unknown")
    }
  }

  companion object {
    @Volatile
    var analytics: UserInteractionLatencyAnalytics? = null
  }
}
