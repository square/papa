package papa

import android.content.pm.ApplicationInfo
import android.os.Build
import papa.SafeTrace.MAX_LABEL_LENGTH
import papa.SafeTrace.beginSection
import papa.SafeTrace.isCurrentlyTracing
import papa.SafeTrace.isTraceable
import papa.internal.SafeTraceMainThreadMessages

/**
 * This is a wrapper for [androidx.tracing.Trace] that should be used instead as [beginSection] and
 * [safeTrace] automatically truncate the label at 127 characters instead of crashing.
 *
 * [SafeTrace] also provides [isTraceable], [isCurrentlyTracing] and [MAX_LABEL_LENGTH].
 *
 * All tracing methods check that [isTraceable] is true before delegating to
 * [androidx.tracing.Trace] which would otherwise default to crashing if the reflection based
 * backport fail.
 */
object SafeTrace {

  @Volatile
  private var _isTraceable: Boolean? = null

  /**
   * Whether calls to tracing functions will be forwarded to the Android tracing APIs.
   * This is true if the app manifest has the debuggable to true or if it includes the
   * `<profileable android:shell="true"/>` on API 29+, which indicate an intention for this build
   * to be a special build that you want to profile.
   *
   * Starting with API 31, builds can be profileable without setting
   * `<profileable android:shell="true"/>`. However, here we intentionally still return false on
   * API 31 unless explicitly profileable as our goal is make it easy to avoid any additional
   * workload in release builds.
   *
   * You can force this to be true by calling [forceTraceable], which will enable app tracing even
   * on non profileable build.
   *
   * You can also trigger [forceTraceable] at runtime by sending a `papa.FORCE_TRACEABLE` broadcast.
   * To support this, you should add a dependency to the tart-dev-receivers artifact
   *
   */
  @JvmStatic
  val isTraceable: Boolean
    get() = isTraceableInlined()

  @JvmStatic
  val isCurrentlyTracing: Boolean
    get() = isTraceableInlined() && androidx.tracing.Trace.isEnabled()

  /**
   * @see isTraceable
   */
  @JvmStatic
  fun forceTraceable() {
    androidx.tracing.Trace.forceEnableAppTracing()
    _isTraceable = true
    SafeTraceMainThreadMessages.enableMainThreadMessageTracing()
  }

  @Suppress("NOTHING_TO_INLINE")
  private inline fun isTraceableInlined(): Boolean {
    // Prior to SafeTraceSetup.initDone we can't determine if the app is traceable or not, so we
    // always return false, unless something called forceTraceable(). The first call after
    // SafeTraceSetup.initDone becomes true will compute the actual value based on debuggable
    // and profileable manifest flags, then cache it so that we don't need to recheck again.
    return _isTraceable
      ?: if (SafeTraceSetup.initDone) {
        val application = SafeTraceSetup.application
        val applicationInfo = application.applicationInfo
        val isTraceable =
          // debuggable
          (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 ||
            // profileable
            (Build.VERSION.SDK_INT >= 29 && applicationInfo.isProfileableByShell)
        isTraceable.also {
          _isTraceable = it
        }
      } else {
        false
      }
  }

  /**
   * Writes a trace message to indicate that a given section of code has begun. This call must
   * be followed by a corresponding call to {@link #endSection()} on the same thread.
   */
  @JvmStatic
  fun beginSection(label: String) {
    if (!isCurrentlyTracing) {
      return
    }
    androidx.tracing.Trace.beginSection(label.take(MAX_LABEL_LENGTH))
  }

  @JvmStatic
  inline fun beginSection(crossinline labelLambda: () -> String) {
    if (!isCurrentlyTracing) {
      return
    }
    androidx.tracing.Trace.beginSection(labelLambda().take(MAX_LABEL_LENGTH))
  }

  /**
   * Begins and ends a section immediately. Useful for reporting information in the trace.
   */
  @JvmStatic
  fun logSection(label: String) {
    if (!isCurrentlyTracing) {
      return
    }
    androidx.tracing.Trace.beginSection(label.take(MAX_LABEL_LENGTH))
    androidx.tracing.Trace.endSection()
  }

  /**
   * @see [logSection]
   */
  @JvmStatic
  inline fun logSection(crossinline labelLambda: () -> String) {
    if (!isCurrentlyTracing) {
      return
    }
    val label = labelLambda().take(MAX_LABEL_LENGTH)
    androidx.tracing.Trace.beginSection(label)
    androidx.tracing.Trace.endSection()
  }

  /**
   * Writes a trace message to indicate that a given section of code has ended. This call must
   * be preceded by a corresponding call to {@link #beginSection(String)}. Calling this method
   * will mark the end of the most recently begun section of code, so care must be taken to
   * ensure that beginSection / endSection pairs are properly nested and called from the same
   * thread.
   */
  @JvmStatic
  fun endSection() {
    if (!isCurrentlyTracing) {
      return
    }
    androidx.tracing.Trace.endSection()
  }

  /**
   * @see androidx.tracing.Trace.beginAsyncSection
   */
  @JvmStatic
  inline fun beginAsyncSection(
    crossinline labelCookiePairLambda: () -> Pair<String, Int>
  ) {
    if (!isCurrentlyTracing) {
      return
    }
    val (label, cookie) = labelCookiePairLambda()
    androidx.tracing.Trace.beginAsyncSection(label, cookie)
  }

  /**
   * [cookie] defaults to 0 (cookie is used for async traces that overlap)
   * @see androidx.tracing.Trace.beginAsyncSection
   */
  @JvmStatic
  fun beginAsyncSection(
    label: String,
    cookie: Int = 0
  ) {
    if (!isCurrentlyTracing) {
      return
    }
    androidx.tracing.Trace.beginAsyncSection(label, cookie)
  }

  /**
   * [cookie] defaults to 0 (cookie is used for async traces that overlap)
   * @see androidx.tracing.Trace.endAsyncSection
   */
  @JvmStatic
  fun endAsyncSection(
    label: String,
    cookie: Int = 0
  ) {
    if (!isCurrentlyTracing) {
      return
    }
    androidx.tracing.Trace.endAsyncSection(label, cookie)
  }

  /**
   * @see androidx.tracing.Trace.beginAsyncSection
   */
  @JvmStatic
  inline fun endAsyncSection(
    crossinline labelCookiePairLambda: () -> Pair<String, Int>
  ) {
    if (!isCurrentlyTracing) {
      return
    }
    val (label, cookie) = labelCookiePairLambda()
    androidx.tracing.Trace.endAsyncSection(label, cookie)
  }

  /**
   * [android.os.Trace.beginSection] throws IllegalArgumentException if the
   * label is longer than 127 characters.
   *
   * public so that to allowing building strings differently to avoid trailing characters being removed.
   */
  const val MAX_LABEL_LENGTH = 127
}
