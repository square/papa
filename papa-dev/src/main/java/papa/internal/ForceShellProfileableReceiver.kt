package papa.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import papa.SafeTrace

internal class ForceShellProfileableReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    SafeTrace.forceShellProfileable()
  }
}
