package tart.legacy

enum class AppLifecycleState {
  /**
   * The application has at least one resumed activity.
   */
  RESUMED,

  /**
   * The application has no resumed activity.
   */
  PAUSED
}
