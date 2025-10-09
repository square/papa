package papa

import android.Manifest.permission.SYSTEM_ALERT_WINDOW
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi

/**
 * A window overlay for displaying dev information. Requires the [SYSTEM_ALERT_WINDOW]
 * permission.
 *
 * Consumers are expected to:
 * - Leverage [canDrawOverlays] and [newManageOverlayIntent] to obtain the draw overlay permission.
 * - Appropriately call [show] and [dismiss] so that the overlay is only displayed while the app
 * has foreground priority.
 */
class WindowOverlay(
  context: Context,
  private val overlayLayoutParamsFactory: () -> WindowManager.LayoutParams =
    ::newNotTouchableWindowLayoutParams,
  private val overlayViewFactory: (Context) -> View
) {

  private val appContext = context.applicationContext

  private val windowManager: WindowManager
    get() = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

  private var overlayShown: View? = null

  fun canDrawOverlays(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    Settings.canDrawOverlays(appContext)
  } else {
    true
  }

  @RequiresApi(Build.VERSION_CODES.M)
  fun newManageOverlayIntent() =
    Intent(ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${appContext.packageName}"))

  fun show() {
    if (overlayShown != null) {
      Log.d(TAG, "Ignoring $TAG.show(): already showing.")
      return
    }
    if (!canDrawOverlays()) {
      Log.d(
        TAG,
        "Ignoring $TAG.show(): $TAG.canDrawOverlays() is false."
      )
      return
    }

    overlayShown = overlayViewFactory(appContext)
    windowManager.addView(overlayShown, overlayLayoutParamsFactory())
  }

  fun dismiss() {
    if (overlayShown == null) {
      Log.d(TAG, "Ignoring $TAG.dismiss(): not showing.")
      return
    }
    windowManager.removeView(overlayShown)
    overlayShown = null
  }

  private companion object {
    private val TAG = WindowOverlay::class.java.simpleName

    fun newNotTouchableWindowLayoutParams(): WindowManager.LayoutParams {
      val windowType = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
      } else {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      }

      return WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
      )
    }
  }
}
