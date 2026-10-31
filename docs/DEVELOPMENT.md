## Current Play candidate: 0.3.0 (15); separate beta4 (14)

See [PLAY_BETA.md](PLAY_BETA.md) for current behavior, configuration, release gates and the physical-device protocol. The notes below describe earlier releases and historical validation. In particular, automatic charging-only processing defaults off for new installs, GPU text-only is attempted before CPU, inference uses cooperative streaming cancellation, and active branding is the walnut/brain vector. Saved charging preferences are preserved on updates.

# SuperMens developer notes

## Android project

This project contains the local Android app only, with no backend module.

- Application IDs: release `com.bbnss.SuperMens`; debug `it.supermens.offline`; beta `it.supermens.offline.beta`.
- Minimum SDK: 35 (Android 15); compile and target SDK: 36.
- Packaged architecture: `arm64-v8a`.
- Release: `0.3.0`, version code `15`, prepared for the owner's Play internal test. Debug and the separate beta remain `0.3.0-beta4`, code `14`.
- Toolchain: JDK 17 or later, Android SDK 36 and the included Gradle wrapper.

Configure `JAVA_HOME` and `ANDROID_HOME` for your machine, or use Android Studio. Never commit `local.properties`.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Debug output: `app/build/outputs/apk/debug/app-debug.apk`.
Unsigned release output: `app/build/outputs/apk/release/app-release-unsigned.apk`.
Private beta output: `app/build/outputs/apk/beta/app-beta.apk`.
Installable copies in `apk/` are ignored by Git and intended as release assets.

## Release signing

Compile without production keys, passwords or Play credentials:

```sh
./gradlew :app:assembleRelease :app:bundleRelease --no-daemon
```

Both release packages remain unsigned. The maintainer signs separately in their own terminal using hidden password input after reviewing the artifact. The AAB upload identity and official APK app-signing identity are distinct roles; verify the expected certificate for the destination. Never inject production signing material into Gradle or CI. The previously used E4:75 certificate is retired; do not reuse it.

For private beta testing, run `./gradlew :app:assembleBeta`. The `beta` build type retains release R8/resource optimization, uses the standard Android debug certificate and installs as **SuperMens Beta** (`it.supermens.offline.beta`). No production key or manual signing is needed. Its archive and model are separate from the regular app. Export/import a ZIP to copy an archive; import the original model file or download weights again. Do not uninstall the regular app to install this beta. Official builds never fall back to the debug certificate.

### Native callback regression check

LiteRT-LM 0.11.0 obtains streaming callback methods through JNI by their literal names and signatures. R8 cannot see those calls. Version 0.3.0-beta1 removed/renamed `onMessage(String)`, `onDone()` and `onError(int, String)` and merged the callback implementation into another class, causing a native SIGABRT during asynchronous inference. Debug-only tests did not expose the release optimization failure.

The ProGuard rules now retain both JNI callback contracts and their implementations. Check the actual optimized DEX after every release or beta build:

```sh
python3 tools/verify_native_callbacks.py app/build/outputs/apk/beta/app-beta.apk \
  --dexdump "$ANDROID_HOME/build-tools/36.0.0/dexdump"
```

Repeat with the unsigned release APK before official signing. This check fails on the original beta1 APK and passes on the fixed artifacts. It verifies the native linkage contract without requiring weights; real-device inference remains a separate check.

Migrating a debug installation requires exporting the archive, uninstalling, installing the release APK and restoring the archive. The model must be downloaded or imported again; signing material is never part of app backups.

Release 0.2.4 is published with `prerelease=false` and marked latest so Obtainium discovers it by default. Its APK asset has the fixed name `SuperMens.apk`; `versionCode` increases with each update. Keep the experimental status clear in the release notes.

## Model and inference

### Conversations and translations

Database schema 5 adds a target language to posts and ordered user/assistant messages for `chat` and `translation` posts. Messages retain their mode, language, state and reply relationship; original text and translations remain separately accessible. The first input and its processing job are committed together. Manual transcription, translation and chat jobs use the existing serial processing guard and can run on battery. Audio clips and translated blocks have separate checkpoint keys; successful publication and the completed job receipt share a transaction. Interrupted jobs resume on reopening; failed jobs have an explicit retry action.

Translation uses UTF-8-bounded blocks without discarding source characters. Free chat rebuilds LiteRT-LM history using complete recent exchanges, reserves room within the existing 4,096-token runtime context and marks replies when older context is omitted. Translation-post questions use retrieval over the saved original/translated text. Audio is transcribed into an editable draft before submitting it for translation or a reply. Temporary audio is removed after successful transcription. A bundled ML Kit language-identification model checks sufficiently confident results offline. Gemma receives translation instructions through the system role and full English target-language names/tags; one corrective attempt precedes a non-automatically-retried language error. Translation checkpoints include prompt version, target and input digest. No cloud inference provider is introduced.

Android TextToSpeech is used only with voices whose `isNetworkConnectionRequired` flag is false. Missing voices lead to Android voice settings; playback stops when recording starts, the app moves to the background, or the screen closes. The target translation language uses `LocaleManager.systemLocales`, independently of the English/Italian app UI fallback.

Backup manifest version 4 includes post-title state, retained PDF copies, conversation messages, reply links, target languages and pending audio. Imports accept versions 1, 2, 3 and 4 and remap message identifiers while retaining reply relationships. Markdown exports include the complete conversation and manual additions. Models and voice packages remain outside app backups.

Manual additions use their own `manual` segment source and survive re-extraction/retranscription. They do not mark an incomplete online source complete or enqueue a summary. Shared PDF footers and image/text signatures use the existing `repository_url` resource.

The APK does not include weights. The supported model is `gemma-4-E2B-it.litertlm` from [this pinned revision](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/7fa1d78473894f7e736a21d920c3aa80f950c0db), sized **2,583,085,056 bytes**. Downloads and imports validate both size and SHA-256 (`ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`). DownloadManager can preallocate the entire file size while only a few MB have arrived, so file size must never be used as a completion signal. DownloadManager writes to a `.download` staging file; a persistent verification worker promotes it only after validation. Existing models from earlier builds are verified on upgrade. Allow storage for the staged imported file as well as any existing weights.

Android isolates other apps’ private files. A model installed elsewhere must be exported into an accessible document or downloaded for SuperMens. The download dialog offers Wi-Fi only or consent to mobile/metered data for that download. Roaming stays disabled. Paused and failed downloads show their reason. A download paused for Wi-Fi can be restarted on mobile data after confirmation.

LiteRT-LM attempts GPU text/vision and CPU audio, then CPU text/audio. A failed initialization closes only initialized engines; cleanup errors cannot hide the original cause or prevent CPU fallback. Settings provides an actual short inference test and reports the selected backend. AI image descriptions require the GPU configuration. Runtime context is 4,096 tokens. Native calls finish before the processing guard releases their resources.

The weights exceed GitHub’s 2 GiB per-release-asset limit (2,583,085,056 bytes). Keep the pinned Hugging Face URL for now. A GitHub mirror would require split assets, manifest/digest validation and reassembly support in the app; do not commit weights into Git or bundle them into the APK.

## Processing and retrieval

SQLite database version 4 preserves posts, segments, answers, legacy attachments, persistent jobs, source quality and checkpoints. Version 4 adds atomic import receipts to prevent Activity-result replay while preserving intentional later imports. FTS4 provides keyword and prefix search.

Questions search the complete stored text through overlapping passages. Gemma suggests terms and synonyms; retrieval retains original question terms and nearby context, including page/timestamp references. Insufficient retrieval triggers additional passages and then chunk-based reading. Whole-document questions use chunk-based reading directly. Each retrieval context is bounded to 5,200 UTF-8 bytes as a conservative token estimate. Saved questions and answers appear after the summary.

Summary chunks, reductions and final output are checkpointed. Audio uses mono WAV clips of 25 seconds with one-second overlap; successful clips are retained and overlap merging removes repeated boundary text. PDFs use bundled Latin-script OCR over rendered pages with per-page checkpoints; text does not reproduce layout or graphics.

## Queue and charging

Acquisition and analysis are separate persistent queues. Text, captions, previews and source snapshots are acquired before charging waits. A process-wide guard serializes heavy tasks: media preparation, OCR, native generation and share preparation.

Automatic ready posts run oldest first. Waiting or failed acquisition does not block unrelated posts. Manual questions/actions wait for the current operation and then take priority, even on battery. Charging-only processing is disabled by default. Unplugging pauses automatic work at a checkpoint after the current native call finishes.

Queued or running posts can be deleted without waiting for native inference; deleting a post removes its persistent jobs and checkpoints, and late callbacks cannot restore it. Sharing a failed URL again retries acquisition without duplicating the post.

Missing models and Internet wait without consuming retries; three actual failures require manual retry. WorkManager resumes eligible work subject to Android scheduling. Reopen the app after force-stopping it.

## Daily title lists

Schema 7 adds `title_state` (`pending`, `legacy`, `generated`) and removes the three daily-summary tables. Existing posts keep their titles as `legacy`; no title backfill is queued. New titles enter the expandable list only after successful processing. The list follows the cards' order and opens the corresponding posts; search hides it. Every list starts collapsed and its expansion survives navigation and Activity recreation.

The final post-summary inference returns validated `<title>` and `<summary>` blocks together. Image inference returns `<title>` and `<description>`. Chat/translation jobs create a title from the completed exchange without adding a summary; response and translation checkpoints survive title-generation failure. Publication of title/content and job completion is atomic. A malformed response gets one corrective attempt before normal queue retries; invalid output is never checkpointed. Ordinary post questions do not change titles.

Daily scheduling, watchdogs, caches and generation are retired. Existing unique daily work and worker-class tags are cancelled; compatibility workers return success without loading Gemma. Older backup daily data is ignored. Automatic post processing retains its charging preference and manual actions retain their priority.

## Media, backup and sharing

- Imported photos: WebP up to 2,400 px / quality 85. Online previews: up to 1,600 px / quality 75. EXIF orientation is applied; small images are not enlarged. Inference uses PNG bytes in memory without saving another image.
- Camera: system capture with a temporary private URI, retaining the compressed image after successful import. Cancellation creates no post.
- Images: description and OCR without a redundant summary; legacy summaries remain stored.
- PDFs: imports and relinks stage, sync and validate a complete private `.pdf` copy before committing it. Extraction and opening prefer this copy, which remains after OCR and is included in ZIP backups. Startup recovery promotes readable legacy snapshots/originals without requiring weights or charging. Relinking warns before selection and confirms the chosen filename before replacement; errors keep the existing document. Confirmed requests have persistent receipts to prevent replay after Activity recreation. Replacement retires stale jobs/checkpoints and invalidates derived PDF content while preserving manual additions and saved questions/answers.
- New videos: temporary working copy, preview up to 720 px, temporary mono AAC/M4A at 16 kHz / 48 kbps. The working video is deleted after preparation; audio after successful transcription. Legacy archived videos remain unchanged. Retranscription later requires the original.
- ZIP backup: JSON manifest, Markdown notes, saved answers, previews and retained attachments. Pending sources, temporary audio and model weights are excluded.
- Sharing: all stored segments/answers, including those beyond the detail screen’s visible limit. JPEG plus text or a complete paginated PDF. Share files older than 24 hours are removed at the next share.

**Free model RAM** closes the engine and CPU/GPU resources without deleting the model, posts, attachments or cache. It is disabled while heavy work is running or ready. The next AI action reloads the model; actual memory recovery depends on Android.

## Sources and privacy

Web acquisition uses Jsoup and redirect-aware Open Graph/Twitter metadata. YouTube tries direct player/caption extraction and an optional configured HTTPS Invidious instance. X tries public syndication, oEmbed and HTML; previews include photos, video posters, linked-article cards and quoted media. Re-sharing a post with a missing/failed preview retries acquisition; full-text status requires an explicit complete-text field. LinkedIn and Amazon additionally use public structured metadata and post/product elements. Reddit tries structured public post data, classic public HTML and oEmbed previews; shared titles, HTML and ClipData text are retained. Other socials rely on public metadata and shared text. These sources may be incomplete or blocked.

There is no remote AI endpoint. ML Kit can send technical metrics and contact Google for updates while processing content locally; see [its terms](https://developers.google.com/ml-kit/terms). Websites, model hosting and optional Invidious instances receive ordinary network requests.

## UI and validation

The All tab groups posts by local creation date in a scrolling daily timeline with two columns and an expandable title list. Expansion, stable visible item key and scroll offset live above the navigation routes and survive Activity recreation. Searches keep dates and hide the daily title list. Back from home requires an exit confirmation; nested screens and dialogs handle Back before it. Other type tabs retain the grid. The home retains colored type filters, bottom search and a rounded add menu. A missing-model banner starts Wi-Fi downloads after a click and asks for mobile/metered consent otherwise. The multiline text editor uses explicit dark-theme colors and retains drafts across Activity recreation. Long text previews are capped at 1,200 characters; expanded text uses lazy 1,600-character display slices with page/timestamp references and collapse/top controls. Copying and exports always use complete stored text. Settings use the current JPEG with a circular clip and slight scaling to exclude its outer white rim. The launcher uses the same artwork with Android’s adaptive mask. The README embeds that original artwork in `docs/assets/supermens-icon.svg` with the same circular mask and scaling, keeping the outer background transparent. Only BBNSS in the footer opens the repository.

Italian is selected for an Italian primary device/app language; other primary languages use English. Existing content keeps its language.

Version 0.2.4 passed 56 JVM tests and 67 Android tests on a separate clean Android 16 / API 36.1 ARM64 emulator, plus debug/release lint with no errors and signed release assembly. Coverage includes long single-segment text, complete copying, expand/collapse/top controls, readable pasted text and draft restoration, model-banner Wi-Fi/mobile consent, transactional acquisition/completion, recovery of stale completed posts, version-3 archive migration, original-file MIME/read grants, and video import followed by three camera captures and Activity recreation. A new explicit import of the same video remains allowed. A signed 0.2.3-to-0.2.4 install retained an existing archive post; the release certificate is unchanged.

Live public-page checks extracted 1,266 text characters and nine image candidates from an Amazon product, and 544 characters plus a cover from a LinkedIn post. Both covers downloaded and decoded through the app's preview function. Reddit's JSON endpoint returned 403 from the test network, and its classic URL redirected to a login page. These pages were rejected rather than summarized; public oEmbed still returned the post title. Full Reddit extraction is covered with structured/HTML fixtures but cannot be guaranteed when Reddit blocks requests. No physical phone was connected, so native Gemma generation quality and the reported camera sequence after speech analysis still need physical-device validation.

Version 0.2.3 passed 46 JVM tests and 47 Android tests on a clean Android 16 / API 36.1 ARM64 emulator, plus signed release assembly and lint. New regressions cover initialization/cleanup failure with CPU fallback, digest validation and cancellation, refusing unverified size-matching weights, preserving an existing model after invalid import, explicit network consent, and X card/video/quoted previews. Live checks of the signed APK recovered a public X article-card preview and downloaded model bytes on Wi-Fi, paused on switching to mobile, and resumed only after consent. Android preallocation was observed directly: a logical 2.58 GB file with only a few MB transferred did not become ready. The certificate matches 0.2.2-alpha.

Version 0.2.2-alpha passed 40 JVM tests and 40 Android tests on an Android 16 / API 36.1 ARM64 emulator, plus release assembly and lint. Regression coverage includes deleting a queued post while another operation holds the inference guard, cascading jobs/checkpoints, ignoring late callbacks after deletion, retrying a failed shared URL, and preserving consecutive share intents. The suite also covers retrieval, captions, transcript overlap, queue ordering/serialization, migration, compression, EXIF, PDF extraction, URI grants, backup, complete sharing and UI languages. The release APK signature matches the maintainer’s existing release certificate; NewPipe is absent from the resolved dependency graph, DEX and R8 mapping.

The complete pinned model also matched the expected size and SHA-256 on the host, and the signed APK verified it successfully in its own model directory. Native inference could not complete on the Apple Silicon hosted ARM64 emulator: XNNPACK trapped with SIGILL at opcode `0x04bf5820` (`RDSVL`), matching the SME feature-dispatch failure described in [this upstream issue](https://github.com/google-ai-edge/mediapipe/issues/6293). This is a failed native smoke test, not a passing inference check. No physical device was connected for this release session.

Physical-device testing with installed weights is needed for Gemma quality/performance and GPU behavior. Exercise camera capture/cancel, recording, dictation fallback, long-document questions, relinking, charging/unplugging and background resume.

`tools/adb_profile.sh --name transcription --duration 180` samples CPU, PSS, battery, temperature and charging every five seconds with one authorized device. Generated CSV files contain its serial: keep `tools/profiles/` private and ignored.

## 0.2.8 validation

70 JVM tests and all 87 Android regressions passed on an isolated Android 16 / API 36.1 ARM64 emulator. Coverage includes title validation and checkpoints, legacy migration without backfill, title-list updates and links, scroll/expansion restoration after navigation and Activity recreation, both PDF confirmations, copy/replacement failures, recovery of legacy snapshots, backup round trips and title-failure resumption for translations.

Debug and release lint report no errors. The signed release passes APK signature and 16 KiB ZIP-alignment verification; its certificate matches 0.2.7. A signed 0.2.7-to-0.2.8 emulator update retained an existing post and exposed it in the expandable title list with versionCode 10. Publication audit found no private files, credentials or developer paths in the sources or APK.

No physical device is connected. Native Gemma quality/performance with the new title prompts remains a physical-device verification step; automated title and conversation regressions inject model output.
