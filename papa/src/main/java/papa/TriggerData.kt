package papa

sealed class TriggerData {
  abstract val name: String
  abstract val durationUptimeMillis: Long

  class Found(
    override val name: String,
    override val durationUptimeMillis: Long
  ) : TriggerData() {
    override fun toString(): String {
      return "Found(name='$name', duration=$durationUptimeMillis ms)"
    }
  }

  object Unknown : TriggerData() {
    override val name = "unknown"
    override val durationUptimeMillis = 0L

    override fun toString(): String {
      return "unknown"
    }
  }
}
