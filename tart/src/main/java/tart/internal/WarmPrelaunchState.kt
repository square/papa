package tart.internal

internal enum class WarmPrelaunchState {
  /**
   * Warm start: the activity was created with no state bundle and then resumed.
   */
  CREATED_NO_STATE,

  /**
   * Warm start: the activity was created with a state bundle and then resumed.
   */
  CREATED_WITH_STATE,

  /**
   * hot start: the activity was started and then resumed
   */
  STARTED,

  /**
   * Not a launch: the activity was just resumed.
   */
  RESUMED
}