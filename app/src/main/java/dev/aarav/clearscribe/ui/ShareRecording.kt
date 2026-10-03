package dev.aarav.clearscribe.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File

private const val LOCALSEND_PACKAGE = "org.localsend.localsend_app"

/**
 * Sends a recording's WAV file off the device. Tries LocalSend directly
 * first (skips the chooser entirely), falling back to the normal Android
 * share sheet if LocalSend isn't installed.
 *
 * Note: LocalSend ships for Android, iOS, macOS, Windows and Linux — there's
 * no Wear OS build, confirmed against its own release listings. So on an
 * actual watch this will always fall through to the generic chooser (which,
 * per the earlier conversation, may itself not render well on some Wear OS
 * builds). On a phone/tablet, LocalSend's package resolves fine and this
 * skips straight to it.
 */
fun shareRecording(context: Context, audioPath: String) {
    val file = File(audioPath)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    val localSendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setPackage(LOCALSEND_PACKAGE)
    }

    val canUseLocalSend = localSendIntent.resolveActivity(context.packageManager) != null
    if (canUseLocalSend) {
        context.startActivity(localSendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }

    val chooserIntent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(chooserIntent, "Share recording").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
