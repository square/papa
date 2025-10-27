package papa.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.tracing.Trace
import papa.SafeTraceSetup

internal class ForceShellProfileableReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    Trace.forceEnableAppTracing()
    SafeTraceSetup.enableMainThreadMessageTracing()
  }
}
