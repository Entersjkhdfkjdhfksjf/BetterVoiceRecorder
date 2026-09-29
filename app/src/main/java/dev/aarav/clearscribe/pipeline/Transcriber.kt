package dev.aarav.clearscribe.pipeline

/**
 * Turns cleaned 16 kHz mono audio into text.
 *
 * Nothing is wired in yet. Options discussed so far:
 *  - Moonshine (ai.moonshine:moonshine-voice) as an interim engine — its
 *    buffer-in/text-out API wasn't verified, so it isn't added as a dependency
 *    here yet. Check "Using the Library -> Transcription" in Moonshine's docs
 *    before wiring it up.
 *  - Desert Ant Labs' Voz, once/if it ships an Android SDK.
 *
 * Until one of those is wired in, [PlaceholderTranscriber] keeps the rest of
 * the pipeline (title, summary, storage) exercisable end to end.
 */
interface Transcriber {
    /** [samples16k]: 16 kHz mono, range -1f..1f. */
    suspend fun transcribe(samples16k: FloatArray): String
}

class PlaceholderTranscriber : Transcriber {
    override suspend fun transcribe(samples16k: FloatArray): String =
        "[transcription not wired up yet — ${samples16k.size / 16_000} s of audio captured]"
}
