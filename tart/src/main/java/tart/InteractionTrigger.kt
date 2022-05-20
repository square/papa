package tart

sealed class InteractionTrigger {

  /**
   * An interaction that was triggered by user input, i.e. either by a button click in the UI or a
   * back key press.
   *
   * The time at which the system received the button click / back key press will be automatically
   * captured and reported, so long as this is used within the same main thread message as the
   * onClickListener callback or the onBackPressed() callback.
   */
  object Input : InteractionTrigger()

  /**
   * An interaction that was triggered by something else than user input, e.g. a physical action on
   * connected hardware or an interaction started automatically such as coming out of a loading
   * screen.
   */
  class Custom(
    val name: String,
    /**
     * A timestamp captured using [android.os.SystemClock.uptimeMillis] that represents the time at
     * which the user interaction actually started (or the best possible approximation of that time).
     */
    val triggerStartUptimeMillis: Long
  ) : InteractionTrigger()

  /**
   * An interaction for which we do not know the trigger.
   */
  object Unknown : InteractionTrigger()
}
