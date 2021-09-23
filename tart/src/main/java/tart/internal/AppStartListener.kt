package tart.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri

/**
 * Registered [ContentProvider]s receive their [attachInfo] callback before
 * [android.app.Application.onCreate].
 */
internal abstract class AppStartListener : ContentProvider() {

  final override fun attachInfo(
    context: Context,
    info: ProviderInfo
  ) {
    super.attachInfo(context, info)
    onAppStart(context)
  }

  abstract fun onAppStart(context: Context)

  final override fun onCreate(): Boolean {
    return false
  }

  final override fun query(
    uri: Uri,
    strings: Array<String>?,
    s: String?,
    strings1: Array<String>?,
    s1: String?
  ): Cursor? {
    return null
  }

  override fun getType(uri: Uri): String? {
    return null
  }

  final override fun insert(
    uri: Uri,
    contentValues: ContentValues?
  ): Uri? {
    return null
  }

  final override fun delete(
    uri: Uri,
    s: String?,
    strings: Array<String>?
  ): Int {
    return 0
  }

  final override fun update(
    uri: Uri,
    contentValues: ContentValues?,
    s: String?,
    strings: Array<String>?
  ): Int {
    return 0
  }
}
