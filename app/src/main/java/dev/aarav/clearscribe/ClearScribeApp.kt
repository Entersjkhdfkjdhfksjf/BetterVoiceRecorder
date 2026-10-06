package dev.aarav.clearscribe

import android.app.Application
import android.util.Log
import androidx.room.Room
import dev.aarav.clearscribe.data.ClearScribeDatabase
import dev.aarav.clearscribe.data.RecordingRepository
import dev.aarav.clearscribe.pipeline.FirstSentencesSummarizer
import dev.aarav.clearscribe.pipeline.GenericTitleProvider
import dev.aarav.clearscribe.pipeline.GtcrnDenoiser
import dev.aarav.clearscribe.pipeline.SherpaMoonshineTranscriber
import dev.aarav.clearscribe.pipeline.Summarizer
import dev.aarav.clearscribe.pipeline.TitleProvider
import dev.aarav.clearscribe.pipeline.Transcriber
import dev.aarav.clearscribe.playback.RecordingPlayer
import dev.aarav.clearscribe.ui.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Overall app readiness, gated on the transcription model only. Starts at
 * [NotRequested] and stays there until the user explicitly triggers
 * [ClearScribeApp.prepareModels] from Settings — models are never fetched
 * automatically on first install. On later launches, if the user has
 * prepared at least once before, prepare re-runs automatically — but by
 * then everything is already cached locally, so that's a fast local load,
 * not a fresh download, and doesn't re-prompt the user every time.
 */
sealed interface ModelReadyState {
    data object NotRequested : ModelReadyState
    data class Downloading(val what: String) : ModelReadyState
    data object Ready : ModelReadyState
    data class Failed(val what: String, val error: Throwable) : ModelReadyState
}

/**
 * Simple manual DI. Swap any of these for a real implementation as
 * alternatives ship — nothing else in the app needs to change.
 */
class ClearScribeApp : Application() {

    lateinit var repository: RecordingRepository
        private set
    lateinit var settings: SettingsStore
        private set

    lateinit var denoiser: GtcrnDenoiser
        private set
    private lateinit var sherpaTranscriber: SherpaMoonshineTranscriber
    lateinit var transcriber: Transcriber
        private set

    val titleProvider: TitleProvider = GenericTitleProvider()
    val summarizer: Summarizer = FirstSentencesSummarizer()
    val player = RecordingPlayer()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _modelReadyState = MutableStateFlow<ModelReadyState>(ModelReadyState.NotRequested)
    val modelReadyState: StateFlow<ModelReadyState> = _modelReadyState

    private val _cleanupAvailable = MutableStateFlow<Boolean?>(null) // null = not checked yet
    val cleanupAvailable: StateFlow<Boolean?> = _cleanupAvailable

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, ClearScribeDatabase::class.java, "clearscribe.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        repository = RecordingRepository(db.recordingDao())
        settings = SettingsStore(applicationContext)

        denoiser = GtcrnDenoiser(applicationContext)
        sherpaTranscriber = SherpaMoonshineTranscriber(applicationContext)
        transcriber = sherpaTranscriber

        // Only auto-run if the user has explicitly prepared at least once
        // before (see SettingsStore) — in that case everything is already
        // cached on disk, so this is a fast local load, not a download.
        if (settings.modelsEverPrepared) {
            prepareModels()
        }
    }

    /** Explicit, user-triggered (Settings screen) model preparation. Safe to call again if already Ready. */
    fun prepareModels() {
        appScope.launch {
            _modelReadyState.value = ModelReadyState.Downloading("cleanup model")
            _cleanupAvailable.value = denoiser.ensureModelReady()

            try {
                _modelReadyState.value = ModelReadyState.Downloading("transcription model")
                sherpaTranscriber.ensureModelReady()
                _modelReadyState.value = ModelReadyState.Ready
                settings.modelsEverPrepared = true
            } catch (e: Throwable) {
                Log.e("ClearScribeApp", "Transcription model setup failed", e)
                _modelReadyState.value = ModelReadyState.Failed("transcription model", e)
            }
        }
    }
}
