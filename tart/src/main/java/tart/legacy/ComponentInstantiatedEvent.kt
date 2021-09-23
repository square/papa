package tart.legacy

/**
 * Tracks the first time an Android component (activity, service, receiver) is instantiated.
 */
data class ComponentInstantiatedEvent(
  val componentName: String,
  val elapsedUptimeMillis: Long
)
