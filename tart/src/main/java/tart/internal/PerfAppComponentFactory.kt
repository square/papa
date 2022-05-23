package tart.internal

import android.app.Activity
import android.app.AppComponentFactory
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.annotation.RequiresApi

/**
 * Tracks the time at which the classloader, application and first components are created on app
 * start on Android P+.
 *
 * androidx.core sets the android:appComponentFactory attribute of the application tag to
 * [androidx.core.app.CoreComponentFactory]. We override that to set [PerfAppComponentFactory]
 * instead, and delegate calls to [androidx.core.app.AppComponentFactory].
 */
@Suppress("unused")
@RequiresApi(28)
internal class PerfAppComponentFactory(
  private val delegate: AppComponentFactory = androidx.core.app.AppComponentFactory()
) : AppComponentFactory() {

  companion object {
    init {
      Perfs.firstClassLoaded()
    }
  }

  @RequiresApi(29)
  override fun instantiateClassLoader(
    cl: ClassLoader,
    aInfo: ApplicationInfo
  ): ClassLoader {
    return delegate.instantiateClassLoader(cl, aInfo)
      .apply {
        Perfs.classLoaderInstantiatedUptimeMillis = SystemClock.uptimeMillis()
      }
  }

  override fun instantiateApplication(
    cl: ClassLoader,
    className: String
  ): Application {
    return delegate.instantiateApplication(cl, className)
      .apply {
        Perfs.applicationInstantiatedUptimeMillis = SystemClock.uptimeMillis()
      }
  }

  override fun instantiateActivity(
    cl: ClassLoader,
    className: String,
    intent: Intent?
  ): Activity {
    onComponentInstantiatedAfterAppCreated(className)
    return delegate.instantiateActivity(cl, className, intent)
  }

  override fun instantiateReceiver(
    cl: ClassLoader,
    className: String,
    intent: Intent?
  ): BroadcastReceiver {
    onComponentInstantiatedAfterAppCreated(className)
    return delegate.instantiateReceiver(cl, className, intent)
  }

  override fun instantiateService(
    cl: ClassLoader,
    className: String,
    intent: Intent?
  ): Service {
    onComponentInstantiatedAfterAppCreated(className)
    return delegate.instantiateService(cl, className, intent)
  }

  override fun instantiateProvider(
    cl: ClassLoader,
    className: String
  ): ContentProvider {
    // Note: no call to onPostApplicationComponentInstantiated(className) here, as ContentProvider
    // instances are created prior to the application instance being created.
    return delegate.instantiateProvider(cl, className)
  }

  // "post application" here really means "not a content provider" since that's
  // the only component instantiated before Application.onCreate() is called.
  private fun onComponentInstantiatedAfterAppCreated(className: String) {
    if (!Perfs.firstPostApplicationComponentInstantiated) {
      Perfs.firstPostApplicationComponentInstantiated = true
      Perfs.firstComponentInstantiated(className)
    }
  }
}
