package tart

interface UserInteractionLatencyAnalytics {

  fun reportInteraction(
    description: String,
    stateBeforeInteraction: AppState.Value,
    stateAfterInteraction: AppState.Value,
    reportStartUptimeMillis: Long,
    rawDurationUptimeMillis: Int,
    totalDurationUptimeMillis: Int,
    triggerDurationUptimeMillis: Int?,
    triggerName: String,
  )
}
