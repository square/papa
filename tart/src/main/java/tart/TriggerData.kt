package tart

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