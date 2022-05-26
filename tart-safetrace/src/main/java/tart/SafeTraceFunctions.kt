package tart

import tart.SafeTrace.isTraceable

/**
 * Allows tracing of a block of code without any overhead when [isTraceable] is false.
 *
 * [label] a string producing lambda if the label is computed dynamically. If the label isn't
 * dynamic, use the [safeTrace] which directly takes a string instead.
 */
inline fun <T> safeTrace(
  crossinline label: () -> String,
  crossinline block: () -> T
): T {
  if (!SafeTrace.isCurrentlyTracing) {
    return block()
  }
  try {
    SafeTrace.beginSection(label())
    return block()
  } finally {
    SafeTrace.endSection()
  }
}

/**
 * Allows tracing of a block of code
 */
inline fun <T> safeTrace(
  label: String,
  crossinline block: () -> T
): T = safeTrace({ label }, block)