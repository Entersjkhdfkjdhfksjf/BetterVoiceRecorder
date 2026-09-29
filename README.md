# ClearScribe

A Wear OS voice recorder: on-device cleanup (Desert Ant Labs' **Clear**), on-device
transcription (pluggable — nothing wired in yet), and a generic title until
Desert Ant Labs' Title model ships for Android.

Everything here is built and tested via GitHub Actions + adb — no local
Gradle build is assumed.

## Pipeline, as implemented

1. **Record** — `RecorderService` captures 48 kHz mono PCM via `AudioRecord`
   in a foreground service, as a `.pcm` scratch file in `cacheDir`.
2. **Clean** — on stop, `AudioCleaner` runs Desert Ant Labs' Clear SDK over
   the full recording. **Not yet functional — see "Before this runs" below.**
3. **Store** — the cleaned audio is written as a `.wav` in `filesDir`, and
   downsampled to 16 kHz for transcription.
4. **Transcribe** — `PlaceholderTranscriber` stands in until a real engine
   (Moonshine, or Desert Ant Labs' Voz if/when it ships on Android) is wired
   into `Transcriber`.
5. **Title** — `GenericTitleProvider` produces `"Recording · 28 Sep, 3:42 PM"`.
   Swap it for Desert Ant Labs' Title model once that has an Android build.
6. **Summarize** — `FirstSentencesSummarizer` takes the first two sentences.
   A real summarizer (e.g. Gemma 3 270M via LiteRT-LM) can replace it later.
7. **Now Bar** — while recording, `RecorderService` registers an
   [Ongoing Activity](https://developer.android.com/codelabs/ongoing-activity)
   (`androidx.wear:wear-ongoing`). This is the standard Wear OS API — Samsung's
   Now Bar on One UI Watch 8+ surfaces any app's Ongoing Activity
   automatically, so no Samsung-specific code is needed. It also shows on the
   watch face and in the app launcher's Recents on any Wear OS 3+ watch,
   Now Bar or not.

Every pluggable step (`Transcriber`, `TitleProvider`, `Summarizer`) is a small
interface bound once in `ClearScribeApp`, so swapping an implementation never
touches `RecorderService` or the UI.

## Before this runs: two unverified API calls

I could not confirm two API details against the actual SDK source (only
against docs pages/snippets), and both are marked with comments in the code:

1. **`AudioCleaner.kt`** — `Clear.enhance()`'s confirmed return fields include
   `measuredTruePeakDbfs`, but I never saw the field holding the *enhanced
   samples themselves*. The code calls `TODO(...)` there on purpose — it will
   crash immediately rather than silently produce wrong audio. Ctrl/Cmd-click
   into `Clear.enhance`'s return type in Android Studio (or open it from the
   GitHub Actions build log / the AAR sources) and fix that one line.
2. **`RecorderService.kt` → `attachOngoingActivity`** — uses a plain static
   `"Recording"` status. A live elapsed-time status (`Status.TimerPart` or
   similar) exists in the Ongoing Activity API but I didn't confirm its exact
   signature, so it's left as a "nice to have" — check the
   `androidx.wear.ongoing` API reference if you want a live timer instead of
   a static label.

Also unverified: `ai.desertant:clear:3.5.0` resolving from Maven Central (the
docs page shows 3.1.0) — if 3.5.0 fails to resolve in CI, drop back to 3.1.0.

## CI

`.github/workflows/build.yml` has two jobs:

- **build** — assembles a debug APK (Gradle is provisioned directly by
  `setup-gradle`, no `gradlew` wrapper jar is checked into this repo) and
  uploads it as an artifact.
- **instrumented-test** — boots a Wear OS emulator image and runs
  `connectedDebugAndroidTest`. The `target`/`profile` combination is
  unverified against `reactivecircus/android-emulator-runner` — if it fails
  to boot, check that action's docs for currently supported Wear targets.

## Testing on your Galaxy Watch

1. On the watch: **Settings → About watch → Software → tap Software version**
   repeatedly to unlock Developer options, then **Settings → Developer
   options → enable ADB debugging** (and Wi-Fi debugging, if shown).
2. Download the `app-debug` artifact from the finished Actions run.
3. From any machine with `adb` (platform-tools only — this doesn't compile
   anything):
   ```
   adb pair <watch-ip>:<pairing-port>      # only if the watch shows a pairing code
   adb connect <watch-ip>:<port>
   adb install -r app-debug.apk
   ```
4. Grant the mic permission when the app prompts, then start a recording.
5. `adb logcat -s RecorderService` shows the Clear pass timing and
   `measuredTruePeakDbfs` once you fix the `TODO` above.

Galaxy Watch 4–7 all run arm64-v8a, matching Clear's supported ABI, so no
architecture mismatch is expected.

## Known limits of this scaffold

- No UI for playback, renaming a title, or deleting a recording yet — just
  record, stop, and see it in the list.
- Long recordings are held entirely in memory as `FloatArray`s (48 kHz mono
  ≈ 192 KB/second). Fine for a minute or two; a multi-minute recording should
  be chunked before this is used for real.
- No retry/error UI if Clear or the transcriber throws — check Logcat.
