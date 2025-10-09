package papa

class EventFrameLabeler(
  private val doFrameSectionNamePrefix: String = DO_FRAME_SECTION
) {

  private var frameCount = 0
  private var currentEventLabel: String? = null
  private var currentEventFrameDuration = 0

  fun onEvent(
    label: String,
    frameDuration: Int = 60
  ) {
    frameCount = 0
    currentEventLabel = label
    currentEventFrameDuration = frameDuration
  }

  fun mapSectionNameIfFrame(name: String): String? {
    if (currentEventLabel == null) {
      return null
    }
    return if (doFrameSectionNamePrefix in name) {
      frameCount++
      if (frameCount > currentEventFrameDuration) {
        frameCount = 0
        currentEventLabel = null
        currentEventFrameDuration = 0
        return null
      }
      "Frame $frameCount after $currentEventLabel"
    } else {
      null
    }
  }

  private companion object {
    private const val DO_FRAME_SECTION =
      "android.view.Choreographer\$FrameDisplayEventReceiver"
  }
}
