package tart.internal

internal enum class StartTemperature {
  /**
   * Warm start: the activity was created with no state bundle and then resumed.
   */
  CREATED_NO_STATE,

  /**
   * Warm start: the activity was created with a state bundle and then resumed.
   */
  CREATED_WITH_STATE,

  /**
   * Warm start: the activity was started and then resumed
   */
  STARTED,

  /**
   * A hot start: the activity was resumed.
   */
  RESUMED
}