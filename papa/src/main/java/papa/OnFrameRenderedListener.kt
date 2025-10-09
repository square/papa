package papa

import kotlin.time.Duration

fun interface OnFrameRenderedListener {
  fun onFrameRendered(frameRenderedUptime: Duration)
}
