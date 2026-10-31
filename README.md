<div align="center">

<img src="docs/assets/supermens-icon.svg" width="120" alt="SuperMens app icon">

# SuperMens

### Save it. Understand it. Keep it on your phone.

An AI-powered personal knowledge archive for Android,<br>
with a local LLM and on-device processing.

[![License: GPL v3 + exception](https://img.shields.io/badge/License-GPLv3%20%2B%20exception-blue)](LICENSE_EXCEPTION.md)
![Version: 0.3.0](https://img.shields.io/badge/Version-0.3.0-blue)
![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white)
![ARM64](https://img.shields.io/badge/Architecture-ARM64-64748B)
![On-device AI](https://img.shields.io/badge/AI-On--device-38BDF8)

[**Download the official APK**](https://github.com/bbnss/SuperMens/releases/download/v0.3.0/SuperMens.apk)
· [Google Play](https://play.google.com/store/apps/details?id=com.bbnss.SuperMens)
· [Privacy policy](https://bbnss.github.io/SuperMens/privacy.html)
· [Report an issue](https://github.com/bbnss/SuperMens/issues)
· [SuperBrain inspiration](https://github.com/sidinsearch/superbrain)

</div>

---

## Your second brain, on your phone

SuperMens brings the save-and-understand idea of tools like [SuperBrain](https://github.com/sidinsearch/superbrain) directly to Android. Capture content, extract its text, generate summaries and ask questions about your saved documents.

AI inference runs locally using **Gemma 4 E2B through LiteRT-LM**. No remote inference backend, app account or AI API key is required. An optional, explicitly submitted content report contacts the developer reporting service.

**The current release is SuperMens 0.3.0 (15)**, package `com.bbnss.SuperMens`. The GitHub APK is the universal APK generated and signed by Google Play from the same version 15 app bundle used on Play. It uses the same app-signing certificate as Play installations, so installations from either channel can update one another when Android accepts the version and signing lineage. See [release provenance](docs/RELEASE_PROVENANCE.md) for hashes and source verification.

The earlier `it.supermens.offline` app and `it.supermens.offline.beta` experimental beta have different package names. They remain separate installations. Export an archive from the earlier app and import it into SuperMens to migrate data; models must be imported or downloaded separately.

Voluntary AI response reports use the configured Google Apps Script email receiver. Consent and optional diagnostics start unchecked; review and edit the response before sending. See the [reporting and 30-day deletion procedure](docs/REPORTING_APPS_SCRIPT.md).

## What you can save

| Content | What SuperMens does |
| --- | --- |
| PDFs | Keeps a private PDF copy, extracts page text with OCR and creates a preview. |
| Photos and screenshots | Compresses images, extracts text and generates a description. |
| Imported audio and video | Transcribes speech locally and summarizes the extracted text. |
| YouTube links | Imports accessible captions, title and preview for local analysis. |
| Public X, Instagram, LinkedIn and Reddit posts | Saves shared text and publicly accessible text, metadata and previews. |
| News, Amazon products and web pages | Extracts readable content and generates a local summary. |
| Notes | Saves written notes, voice recordings and supported on-device dictation. |

Share content from other Android apps, import files, paste a link or capture a photo with the camera. Other public pages may also work when their content is accessible.

## More than saving

- **Ask your documents:** local retrieval finds relevant passages in long content; questions and answers stay with the post.
- **Find it again:** search your local archive and filter by content type.
- **Process immediately, or only while charging:** new installations run automatic processing on battery too. Enable **Process only while charging** in Settings to defer automatic OCR and AI until power is connected. Existing choices are preserved. Manual questions and actions remain available on battery; severe thermal status pauses heavy work.
- **Resume interrupted work:** persistent queues and checkpoints preserve completed transcription clips, PDF pages and summary steps. Heavy tasks run one at a time.
- **Read comfortably:** long transcripts, OCR and document text start with a compact preview. Expand the complete text, collapse it, or return to the top with one tap.
- **Open saved files:** launch a compatible viewer from the detail screen. PDFs open from a permanent private copy, also included in backups. Relinking asks twice before replacing that copy.
- **Copy and share:** copy summaries, descriptions, OCR and transcripts; share a complete post as image and text or a paginated PDF.
- **Add missing context:** integrate written or pasted text into any post, preserving the original content. Create or update its summary when you choose.
- **Daily timeline:** the **All** tab groups posts by creation date and keeps the two-column cards. Each day has an expandable list of clickable post titles. Gemma creates a descriptive one-sentence title together with each post summary; images, chats and translations also receive titles when processed. Existing titles remain until those posts are processed again. No daily-summary inference runs. Returning from a post preserves the timeline position, search, filter and expanded lists. Back from the home asks before leaving the app.
- **Translate locally:** use the **Translation** entry in the + menu for written/pasted text, text files, recordings or imported audio. Review the transcription before translating; the target language initially follows the device. Choose a common language or enter another language name/code; quality varies. Translation uses separate system instructions and a bundled offline language check; a clearly wrong-language result receives one corrective attempt before showing a retryable error. Use **Retranslate** to reload the original text and its target language. Reopen a translation post to continue translating or chat about its text.
- **Free chat:** use the **Free chat** entry to converse with the local model using text or transcribed audio. Conversations are saved as posts and can be resumed or summarized. Switch between text and spoken replies; playback uses installed offline Android voices. A small local model can invent facts, especially without supplied context.
- **Keep your data portable:** export and restore ZIP archives containing structured data, Markdown notes and retained attachments. Exports are unencrypted and limited to 2 GB uncompressed and 9,999 entries, matching restore limits.

- **Keep storage lean:** compressed images and extracted text. Newly imported videos are not retained after preparation; temporary audio is removed after successful transcription.
- **English and Italian:** the interface follows your preferred device or app language, with English as the fallback.

Shared PDFs carry the project URL in every page footer; image/text shares carry it at the end of the text. Backups include descriptive titles, saved PDF copies, conversations, target languages and audio still awaiting transcription. Older backups remain importable; their daily overviews are ignored.

## Get started

1. Install SuperMens from [Google Play](https://play.google.com/store/apps/details?id=com.bbnss.SuperMens) or download the [official APK](https://github.com/bbnss/SuperMens/releases/download/v0.3.0/SuperMens.apk). Both channels use package `com.bbnss.SuperMens` and the same Google app-signing certificate.
2. Use the home banner to download **Gemma 4 E2B**. On Wi-Fi the download starts immediately; on mobile/metered data choose whether to download now or wait for Wi-Fi. You can also import the supported `.litertlm` file in **Settings**.
3. Use **Test model** to check local inference, then save something through Android sharing or the **+** menu.
4. Open its card to read, copy, share or ask a question.

Obtainium: add `https://github.com/bbnss/SuperMens` as a GitHub source and choose `SuperMens.apk` from the stable release. Prereleases with `SuperMens-Beta.apk` refer to the separate historical beta package.

The model is downloaded separately and occupies approximately [2.6 GB](https://developers.google.com/edge/litert-lm/models/gemma-4). Allow 6–8 GB free for working space; each model download/import requires the incoming model plus at least 512 MiB free. Existing weights are retained during import verification. You can remove weights in Settings without deleting your archive. The supported model revision and import details are in the [developer notes](docs/DEVELOPMENT.md).

### Device requirements

- **Android 15 or newer** — API 35 minimum.
- **64-bit ARM processor** — `arm64-v8a`.
- **8 GB RAM or more recommended**, with additional memory providing headroom.
- Compatible GPU support for AI image descriptions; GPU text-only and CPU fallback modes have visible limitations. NPU hardware alone does not guarantee compatibility. Settings provides a technical preview that you may share without content or persistent identifiers.

To copy data from an earlier package, export its archive and import it into the current **SuperMens** app. Import the original model file or download weights again. Keep the earlier app installed until you have checked the imported archive.

## Privacy and offline use

Summaries, questions, image descriptions, OCR and transcription are processed on the device. Your archive is stored locally.

Internet is needed to download the model and acquire online sources. Once the required material and model are available, local processing can continue offline. OCR is bundled with the APK and works offline from first use.

ML Kit may contact Google for updates and send technical usage and performance metrics. According to [Google’s ML Kit privacy documentation](https://developers.google.com/ml-kit/terms), input content and processing results are not sent to Google. The optional Invidious caption fallback contacts the instance you configure.

## Limitations

AI output may contain mistakes. Check important details against the source. Chat replies stream provisionally; **Stop processing** requests cancellation and **Retry** resumes saved checkpoints. Report a generated response through its editable, consent-based report dialog when the reporting service is configured.

Public social posts can still be blocked, incomplete or unavailable. YouTube captions are not always accessible. SuperMens processes the content it can obtain; X posts also support adding missing text manually. Subtitle files in `.srt` and `.vtt` format can be imported when captions are unavailable.

Android may delay background work. After force-stopping the app, reopen it to resume processing.

PDFs imported from version 0.2.8 keep a permanent private copy. Older PDFs are recovered automatically when their original or temporary copy is still accessible; otherwise they must be relinked. Video references still depend on the original file. Retrying a video transcription after temporary audio has been removed requires that original.

## Build from source

Requires JDK 17, Android SDK 36 and the included Gradle wrapper.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

For private device testing, run `./gradlew :app:assembleBeta`. This creates **SuperMens Beta** (`it.supermens.offline.beta`) with release optimization and the standard Android debug signature. It installs alongside the regular app, with a separate archive and model. Import an exported ZIP and import/download the model if needed. Future beta APKs signed with the same debug certificate update this separate installation.

The release variant builds package `com.bbnss.SuperMens`, version `0.3.0` (15): run `./gradlew :app:bundleRelease` to produce an unsigned AAB. Upload signing happens separately in the maintainer's terminal. GitHub distributes the APK generated and signed by Google Play, not an APK signed with the upload key. See the [signing workflow](docs/DEVELOPMENT.md#release-signing).

Built with Kotlin, Jetpack Compose, SQLite, WorkManager, LiteRT-LM and ML Kit. YouTube captions use direct retrieval with an optional Invidious fallback. Third-party components and model weights retain their respective licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).

## License

SuperMens source code is licensed under **GNU GPL v3.0 only, with a Google SDK linking exception**. You may use, modify and redistribute it, including commercially, subject to the license and its corresponding-source requirements. See [LICENSE](LICENSE) and the [additional permission for ML Kit](LICENSE_EXCEPTION.md). The exception applies to BBNSS-owned code and does not relicense Google's SDKs or other third-party components. Model weights retain their own terms.

---

Made by **[BBNSS](https://github.com/bbnss)**. Feedback and reproducible bug reports are welcome.
