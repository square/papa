package tart

enum class AppVisibilityState {
  /**
   * The application has at least one started activity.
   */
  VISIBLE,

  /**
   * The application has no started activity.
   */
  INVISIBLE
}
