package papa

import android.Manifest.permission.SYSTEM_ALERT_WINDOW
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.util.TypedValue.COMPLEX_UNIT_DIP
import android.util.TypedValue.COMPLEX_UNIT_SP
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams

/**
 * A dev overlay that displays the interactions in flight. Requires the [SYSTEM_ALERT_WINDOW]
 * permission.
 *
 * Consumers are expected to know when to show and dismiss this overlay, as well as handle
 * dealing with foreground prioritization.
 */
class DevInteractionOverlay<EventType : Any>(
  context: Context,
  private val trackedInteractionsProvider: () -> List<TrackedInteraction<EventType>>
) {
  private val appContext = context.applicationContext

  private val windowManager: WindowManager
    get() = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

  private val hasDrawOverlayPermission: Boolean
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      Settings.canDrawOverlays(appContext)
    } else {
      true
    }

  private val overlay = object : View(appContext) {

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(255, 0, 0)
      textSize = 8f.sp
    }

    val textBackgroundPaint = Paint().apply {
      color = Color.argb(100, 0, 100, 0)
    }

    val rect = Rect()

    val textLeftX = 4f.dp
    val textTop = (48f.dp).toInt()

    val lineHeight = textPaint.descent() - textPaint.ascent()
    val textBaselineStartY = textTop - textPaint.ascent()

    val interactionFrameCount = mutableMapOf<TrackedInteraction<EventType>, Int>().withDefault {
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
            when (val inputEvent = deliveredInput.event) {
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
  }

  fun show() {
    if (overlay.isAttachedToWindow || !hasDrawOverlayPermission) {
      return
    }

     val windowType = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
        @Suppress("DEPRECATION")
        LayoutParams.TYPE_PHONE
      else
        LayoutParams.TYPE_APPLICATION_OVERLAY

    val params = LayoutParams(
      LayoutParams.MATCH_PARENT,
      LayoutParams.MATCH_PARENT,
      windowType,
      LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE,
      PixelFormat.TRANSLUCENT
    )

    windowManager.addView(overlay, params)
  }

  fun dismiss() {
    if (!overlay.isAttachedToWindow) {
      return
    }
    windowManager.removeView(overlay)
  }

  private val Float.dp: Float
    get() = TypedValue.applyDimension(COMPLEX_UNIT_DIP, this, appContext.resources.displayMetrics)

  private val Float.sp: Float
    get() = TypedValue.applyDimension(COMPLEX_UNIT_SP, this, appContext.resources.displayMetrics)
}