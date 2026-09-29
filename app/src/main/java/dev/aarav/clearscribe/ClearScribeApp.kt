package dev.aarav.clearscribe

import android.app.Application
import androidx.room.Room
import dev.aarav.clearscribe.data.ClearScribeDatabase
import dev.aarav.clearscribe.data.RecordingRepository
import dev.aarav.clearscribe.pipeline.FirstSentencesSummarizer
import dev.aarav.clearscribe.pipeline.GenericTitleProvider
import dev.aarav.clearscribe.pipeline.PlaceholderTranscriber
import dev.aarav.clearscribe.pipeline.Summarizer
import dev.aarav.clearscribe.pipeline.TitleProvider
import dev.aarav.clearscribe.pipeline.Transcriber

/**
 * Simple manual DI. Swap any of these three for a real implementation as
 * Desert Ant Labs (or anything else) ships Android support — nothing else in
 * the app needs to change.
 */
class ClearScribeApp : Application() {

    lateinit var repository: RecordingRepository
        private set

    val transcriber: Transcriber = PlaceholderTranscriber()
    val titleProvider: TitleProvider = GenericTitleProvider()
    val summarizer: Summarizer = FirstSentencesSummarizer()

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, ClearScribeDatabase::class.java, "clearscribe.db")
            .build()
        repository = RecordingRepository(db.recordingDao())
    }
}
