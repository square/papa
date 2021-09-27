package tart.internal

import android.view.Choreographer

/**
 * Choreographer.mLastFrameTimeNanos is annotated with @UnsupportedAppUsage which
 * means it's on the grey list but we can still use it.
 * https://cs.android.com/android/_/android/platform/frameworks/base/+/662af62f16f378d0ffdc5546de2cabfbc7c0e147:core/java/android/view/Choreographer.java;l=179;drc=5d123b67756dffcfdebdb936ab2de2b29c799321
 *
 * Based on
 * https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:metrics/metrics-performance/src/main/java/androidx/metrics/performance/JankStatsApi16Impl.kt;l=109-115;drc=523d7a11e46390281ed3f77893671730cd6edb98
 */
private val choreographerLastFrameTimeField by lazy {
  @Suppress("DiscouragedPrivateApi")
  Choreographer::class.java.getDeclaredField("mLastFrameTimeNanos").apply { isAccessible = true }
}

internal val Choreographer.lastFrameTimeNanos: Long
  get() = choreographerLastFrameTimeField[this] as Long