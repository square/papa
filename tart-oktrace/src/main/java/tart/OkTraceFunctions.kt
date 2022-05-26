package tart

import tart.OkTrace.isTraceable

/**
 * Allows tracing of a block of code without any overhead when [isTraceable] is false.
 *
 * [label] a string producing lambda if the label is computed dynamically. If the label isn't
 * dynamic, use the [okTrace] which directly takes a string instead.
 */
inline fun <T> okTrace(
  crossinline label: () -> String,
  crossinline block: () -> T
): T {
  if (!OkTrace.isCurrentlyTracing) {
    return block()
  }
  try {
    OkTrace.beginSection(label())
    return block()
  } finally {
    OkTrace.endSection()
  }
}

/**
 * Allows tracing of a block of code
 */
inline fun <T> okTrace(
  label: String,
  crossinline block: () -> T
): T = okTrace({ label }, block)