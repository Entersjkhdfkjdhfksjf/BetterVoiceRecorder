package dev.aarav.clearscribe.pipeline

/**
 * Produces a short summary of a transcript. Swap in an on-device LLM
 * (e.g. Gemma 3 270M via LiteRT-LM) later — nothing else in the app changes.
 */
interface Summarizer {
    suspend fun summarize(transcript: String): String
}

/**
 * No model, no cost: takes the first couple of sentences. Good enough for
 * short voice notes, and a safe default while a real summarizer is unverified
 * on-watch.
 */
class FirstSentencesSummarizer(private val maxSentences: Int = 2) : Summarizer {
    override suspend fun summarize(transcript: String): String {
        if (transcript.isBlank()) return ""
        val sentences = transcript.split(Regex("(?<=[.!?])\\s+"))
        return sentences.take(maxSentences).joinToString(" ").trim()
    }
}
