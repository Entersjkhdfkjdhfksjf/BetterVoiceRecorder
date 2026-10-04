package dev.aarav.clearscribe.record

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.aarav.clearscribe.data.Recording
import dev.aarav.clearscribe.ClearScribeApp
import dev.aarav.clearscribe.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/**
 * Records at 48 kHz (what Clear expects), then on stop: runs Clear, downsamples
 * to 16 kHz for the transcriber, and saves the result via the repository.
 *
 * Start with: startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_START))
 * Stop with:  startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
 */
class RecorderService : Service() {

    private val sampleRate = 48_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recordJob: Job? = null
    private var rawFile: File? = null
    private var startedAt: Instant = Instant.EPOCH

    private val app get() = application as ClearScribeApp

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopAndProcess()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording() {
        ensureChannel()
        val notificationBuilder = buildNotificationBuilder()
        startedAt = Instant.now()
        attachOngoingActivity(notificationBuilder)
        startForeground(NOTIFICATION_ID, notificationBuilder.build(), foregroundServiceType())

        val file = File(cacheDir, "rec_${startedAt.toEpochMilli()}.pcm").also { rawFile = it }

        recordJob = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                Log.e(TAG, "AudioRecord.getMinBufferSize returned $minBuf — mic unavailable?")
                return@launch
            }
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
            val chunk = ShortArray(minBuf)
            record.startRecording()
            try {
                file.outputStream().buffered().use { out ->
                    val byteBuf = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    while (isActive) {
                        val n = record.read(chunk, 0, chunk.size)
                        if (n <= 0) continue
                        byteBuf.clear()
                        byteBuf.asShortBuffer().put(chunk, 0, n)
                        out.write(byteBuf.array(), 0, n * 2)
                    }
                }
            } finally {
                record.stop()
                record.release()
            }
        }
    }

    private fun stopAndProcess() {
        val file = rawFile
        if (file == null) {
            stopSelf()
            return
        }
        scope.launch {
            recordJob?.cancelAndJoin()

            val t0 = System.currentTimeMillis()
            val raw48k = readPcm16AsFloat(file)

            val cleanSamples = try {
                app.denoiser.clean(raw48k, sampleRate)
            } catch (e: Throwable) {
                Log.e(TAG, "GTCRN denoise pass failed — using raw audio", e)
                raw48k
            }
            Log.i(TAG, "Denoise pass: ${System.currentTimeMillis() - t0} ms")

            val wavFile = File(filesDir, "rec_${startedAt.toEpochMilli()}.wav")
            writeWav(cleanSamples, sampleRate, wavFile)

            val samples16k = resample(cleanSamples, sampleRate, 16_000)
            val transcript = app.transcriber.transcribe(samples16k)
            val title = app.titleProvider.titleFor(transcript, startedAt)
            val summary = app.summarizer.summarize(transcript)

            app.repository.save(
                Recording(
                    title = title,
                    transcript = transcript,
                    summary = summary,
                    audioPath = wavFile.absolutePath,
                    startedAt = startedAt,
                    durationMs = (raw48k.size.toLong() * 1000) / sampleRate,
                )
            )

            file.delete()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotificationBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording…")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setContentIntent(openAppIntent())

    /**
     * Registers an Ongoing Activity so the watch face, the app launcher's
     * Recents, and — on One UI Watch 8+ — the Galaxy Watch Now Bar all show
     * "Recording" while this service is running. This is the standard
     * AndroidX Wear API (androidx.wear:wear-ongoing); Now Bar surfaces it
     * automatically, no Samsung-specific code needed.
     *
     * VERIFY: the timer/elapsed-time status part (Status.TimerPart or
     * similar) wasn't confirmed against the current androidx.wear.ongoing
     * API reference, so this uses a plain static text status for now. Check
     * the Ongoing Activity API reference if you want a live elapsed timer
     * instead of the static "Recording" label.
     */
    private fun attachOngoingActivity(notificationBuilder: NotificationCompat.Builder) {
        val status = Status.Builder()
            .addTemplate("Recording")
            .build()

        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, notificationBuilder)
            .setStaticIcon(android.R.drawable.presence_audio_online)
            .setTouchIntent(openAppIntent())
            .setStatus(status)
            .build()
            .apply(applicationContext)
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val ACTION_START = "dev.aarav.clearscribe.action.START"
        const val ACTION_STOP = "dev.aarav.clearscribe.action.STOP"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "RecorderService"
    }
}
