package dev.aarav.clearscribe.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sends a recording's WAV file through the standard Android share sheet
 * (Bluetooth share, Messages, whatever's installed) — the only reasonable
 * way to get a file off a watch with no file manager and no cable workflow.
 */
fun shareRecording(context: Context, audioPath: String) {
    val file = File(audioPath)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share recording").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
