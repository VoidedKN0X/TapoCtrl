package com.vknxworks.tapoctrl.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.vknxworks.tapoctrl.ui.lock.AppLock

fun Context.hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/** "4:05" or "1:02:03". */
fun formatDuration(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun openAppSettings(context: Context) {
    context.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}

/** Opens an Android settings page for this app (falls back to the app's info page). */
fun openSystemPage(context: Context, action: String, withPackage: Boolean = true) {
    val intent = Intent(action).apply { if (withPackage) data = Uri.fromParts("package", context.packageName, null) }
    if (!context.launch(intent)) openAppSettings(context)
}

/** Starts another app; that trip doesn't lock TapoCtrl. False when no app can do it. */
fun Context.launch(intent: Intent): Boolean = try {
    AppLock.expectReturn()
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
