package tart.internal

internal enum class LaunchedActivityStartingTransition {
  /**
   * The activity was created with no state bundle and then resumed.
   */
  CREATED_NO_STATE,

  /**
   * The activity was created with a state bundle and then resumed.
   */
  CREATED_WITH_STATE,

  /**
   * The activity was started and then resumed
   */
  STARTED
}