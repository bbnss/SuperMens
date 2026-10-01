# SuperMens developer notes

## Android project

This project contains the local Android app only, with no backend module.

- Application ID: `it.supermens.offline`.
- Minimum SDK: 35 (Android 15); compile and target SDK: 36.
- Packaged architecture: `arm64-v8a`.
- Release being prepared: `0.2.1-alpha`, version code `3`.
- Toolchain: JDK 17 or later, Android SDK 36 and the included Gradle wrapper.

Configure `JAVA_HOME` and `ANDROID_HOME` for your machine, or use Android Studio. Never commit `local.properties`.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Debug output: `app/build/outputs/apk/debug/app-debug.apk`.
Signed release output: `app/build/outputs/apk/release/app-release.apk`.
Installable copies in `apk/` are ignored by Git and intended as release assets.

## Release signing

| Environment variable | Meaning |
| --- | --- |
| `SUPERMENS_KEYSTORE_PATH` | Absolute path to a private keystore outside the repository. |
| `SUPERMENS_KEYSTORE_PASSWORD` | Keystore password. |
| `SUPERMENS_KEY_ALIAS` | Signing key alias. |
| `SUPERMENS_KEY_PASSWORD` | Signing key password. |

Supply the variables from your local secrets configuration, then run:

```sh
./gradlew :app:assembleRelease --no-daemon --no-configuration-cache
```

Missing credentials fail release signing; there is no debug-signature fallback. Back up the keystore and credentials securely and preserve the signing identity for future updates. Never commit signing material, secrets files or Gradle caches.

Migrating a debug installation requires exporting the archive, uninstalling, installing the release APK and restoring the archive. The model must be downloaded or imported again; signing material is never part of app backups.

## Model and inference

The APK does not include weights. The supported model is `gemma-4-E2B-it.litertlm` from [this pinned revision](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/7fa1d78473894f7e736a21d920c3aa80f950c0db), sized **2,583,085,056 bytes**. Import currently validates size rather than a cryptographic digest. Allow storage for the staged imported file as well as any existing weights.

Android isolates other apps’ private files. A model installed elsewhere must be exported into an accessible document or downloaded for SuperMens. Downloads require an unmetered connection.

LiteRT-LM attempts GPU text/vision and CPU audio, then CPU text/audio. AI image descriptions require the GPU configuration. Runtime context is 4,096 tokens. Native calls finish before the processing guard releases their resources.

## Processing and retrieval

SQLite database version 3 preserves posts, segments, answers and legacy attachments while adding persistent jobs, source quality and checkpoints. FTS4 provides keyword and prefix search.

Questions search the complete stored text through overlapping passages. Gemma suggests terms and synonyms; retrieval retains original question terms and nearby context, including page/timestamp references. Insufficient retrieval triggers additional passages and then chunk-based reading. Whole-document questions use chunk-based reading directly. Each retrieval context is bounded to 5,200 UTF-8 bytes as a conservative token estimate. Saved questions and answers appear after the summary.

Summary chunks, reductions and final output are checkpointed. Audio uses mono WAV clips of 25 seconds with one-second overlap; successful clips are retained and overlap merging removes repeated boundary text. PDFs use bundled Latin-script OCR over rendered pages with per-page checkpoints; text does not reproduce layout or graphics.

## Queue and charging

Acquisition and analysis are separate persistent queues. Text, captions, previews and source snapshots are acquired before charging waits. A process-wide guard serializes heavy tasks: media preparation, OCR, native generation and share preparation.

Automatic ready posts run oldest first. Waiting or failed acquisition does not block unrelated posts. Manual questions/actions wait for the current operation and then take priority, even on battery. Charging-only processing is disabled by default. Unplugging pauses automatic work at a checkpoint after the current native call finishes.

Missing models and Internet wait without consuming retries; three actual failures require manual retry. WorkManager resumes eligible work subject to Android scheduling. Reopen the app after force-stopping it.

## Media, backup and sharing

- Imported photos: WebP up to 2,400 px / quality 85. Online previews: up to 1,600 px / quality 75. EXIF orientation is applied; small images are not enlarged. Inference uses PNG bytes in memory without saving another image.
- Camera: system capture with a temporary private URI, retaining the compressed image after successful import. Cancellation creates no post.
- Images: description and OCR without a redundant summary; legacy summaries remain stored.
- PDFs: extracted text, preview and original reference when persistent permission is granted. A temporary snapshot survives charging waits and is removed after successful extraction. Originals can be relinked; Android grants do not transfer in backups.
- New videos: temporary working copy, preview up to 720 px, temporary mono AAC/M4A at 16 kHz / 48 kbps. The working video is deleted after preparation; audio after successful transcription. Legacy archived videos remain unchanged. Retranscription later requires the original.
- ZIP backup: JSON manifest, Markdown notes, saved answers, previews and retained attachments. Pending sources, temporary audio and model weights are excluded.
- Sharing: all stored segments/answers, including those beyond the detail screen’s visible limit. JPEG plus text or a complete paginated PDF. Share files older than 24 hours are removed at the next share.

**Free model RAM** closes the engine and CPU/GPU resources without deleting the model, posts, attachments or cache. It is disabled while heavy work is running or ready. The next AI action reloads the model; actual memory recovery depends on Android.

## Sources and privacy

Web acquisition uses Jsoup and redirect-aware Open Graph/Twitter metadata. YouTube tries direct player/caption extraction, NewPipeExtractor and an optional configured HTTPS Invidious instance. X tries public syndication, oEmbed and HTML; full-text status requires an explicit complete-text field. Other socials rely on public metadata and shared text. These sources may be incomplete or blocked.

There is no remote AI endpoint. ML Kit can send technical metrics and contact Google for updates while processing content locally; see [its terms](https://developers.google.com/ml-kit/terms). Websites, model hosting and optional Invidious instances receive ordinary network requests.

## UI and validation

The home retains two columns, colored type filters, bottom search and a compact add menu. Settings use the current JPEG with a circular clip and slight scaling to exclude its outer white rim. The launcher uses the same artwork with Android’s adaptive mask. The README embeds that original artwork in `docs/assets/supermens-icon.svg` with the same circular mask and scaling, keeping the outer background transparent. Only BBNSS in the footer opens the repository.

Italian is selected for an Italian primary device/app language; other primary languages use English. Existing content keeps its language.

Before this release preparation, 40 JVM and 35 Android tests passed. The suite covers retrieval, captions, transcript overlap, queue ordering/serialization, migration, compression, EXIF, PDF extraction, URI grants, backup, complete sharing and UI languages. See `PUBLICATION_REVIEW.md` for candidate checks.

Physical-device testing with installed weights is needed for Gemma quality/performance and GPU behavior. Exercise camera capture/cancel, recording, dictation fallback, long-document questions, relinking, charging/unplugging and background resume.

`tools/adb_profile.sh --name transcription --duration 180` samples CPU, PSS, battery, temperature and charging every five seconds with one authorized device. Generated CSV files contain its serial: keep `tools/profiles/` private and ignored.
