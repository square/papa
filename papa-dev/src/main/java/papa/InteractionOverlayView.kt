package papa

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.TypedValue
import android.util.TypedValue.COMPLEX_UNIT_DIP
import android.util.TypedValue.COMPLEX_UNIT_SP
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Overlay view that displays the interactions in flight.
 *
 * Meant to be used with [WindowOverlay].
 */
@SuppressLint("ViewConstructor")
class InteractionOverlayView<EventType : Any>(
  private val context: Context,
  private val trackedInteractionsProvider: () -> List<TrackedInteraction<EventType>>
) : View(context) {

  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.rgb(255, 0, 0)
    textSize = 8f.sp
  }

  private val textBackgroundPaint = Paint().apply {
    color = Color.argb(100, 0, 100, 0)
  }

  private val rect = Rect()

  private val textLeftX = 4f.dp
  private val textTop = (48f.dp).toInt()

  private val lineHeight = textPaint.descent() - textPaint.ascent()
  private val textBaselineStartY = textTop - textPaint.ascent()

  private val interactionFrameCount =
    mutableMapOf<TrackedInteraction<EventType>, Int>().withDefault {
      0
    }

  override fun onMeasure(
    widthMeasureSpec: Int,
    heightMeasureSpec: Int
  ) {
    // Use available space.
    setMeasuredDimension(
      MeasureSpec.getSize(widthMeasureSpec),
      MeasureSpec.getSize(heightMeasureSpec)
    )
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val trackedInteractions = trackedInteractionsProvider()

    val trackedInteractionsWithFrameCount =
      trackedInteractions.map { it to interactionFrameCount.getValue(it) + 1 }
    interactionFrameCount.clear()
    interactionFrameCount.putAll(trackedInteractionsWithFrameCount.toMap())

    val interactionLines =
      trackedInteractionsWithFrameCount.map { (trackedInteraction, frameCount) ->
        val input = trackedInteraction.interactionInput?.let { deliveredInput ->
          when (val inputEvent = (deliveredInput as DeliveredInput<*>).event) {
            is MotionEvent -> MotionEvent.actionToString(inputEvent.action)
            is KeyEvent -> KeyEvent.keyCodeToString(inputEvent.keyCode)
            else -> error("Unknown input event class ${inputEvent::class.java.name}")
          } + " -> "
        } ?: ""
        "$frameCount $input" + trackedInteraction.sentEvents.joinToString(" -> ") {
          it.event.toString()
        }
      }

    if (interactionLines.isNotEmpty()) {
      val textBottom = textTop + (lineHeight * interactionLines.size).toInt()
      rect.set(0, textTop, width, textBottom)
      canvas.drawRect(rect, textBackgroundPaint)

      var textBaselineY = textBaselineStartY
      for (interactionLine in interactionLines) {
        canvas.drawText(interactionLine, textLeftX, textBaselineY, textPaint)
        textBaselineY += lineHeight
      }
    }
    invalidate()
  }

  private val Float.dp: Float
    get() = TypedValue.applyDimension(COMPLEX_UNIT_DIP, this, context.resources.displayMetrics)

  private val Float.sp: Float
    get() = TypedValue.applyDimension(COMPLEX_UNIT_SP, this, context.resources.displayMetrics)
}