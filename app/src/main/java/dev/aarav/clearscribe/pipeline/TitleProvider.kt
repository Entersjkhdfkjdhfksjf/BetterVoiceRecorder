package dev.aarav.clearscribe.pipeline

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Produces a title for a finished recording. Swap the implementation bound in
 * ClearScribeApp once Desert Ant Labs' Title model (or anything else) ships
 * for Android — nothing else in the app needs to change.
 */
interface TitleProvider {
    suspend fun titleFor(transcript: String, startedAt: Instant): String
}

/** Placeholder used until a real title model is available on Android. */
class GenericTitleProvider : TitleProvider {
    private val fmt = DateTimeFormatter.ofPattern("d MMM, h:mm a")
        .withZone(ZoneId.systemDefault())

    override suspend fun titleFor(transcript: String, startedAt: Instant): String =
        "Recording · ${fmt.format(startedAt)}"
}
