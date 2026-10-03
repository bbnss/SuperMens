<div align="center">

<img src="docs/assets/supermens-icon.svg" width="120" alt="SuperMens app icon">

# SuperMens

### Save it. Understand it. Keep it on your phone.

An AI-powered personal knowledge archive for Android,<br>
with a local LLM and on-device processing.

[![License: GPL v3 + exception](https://img.shields.io/badge/License-GPLv3%20%2B%20exception-blue)](LICENSE_EXCEPTION.md)
![Status: Alpha](https://img.shields.io/badge/Status-Alpha-orange)
![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white)
![ARM64](https://img.shields.io/badge/Architecture-ARM64-64748B)
![On-device AI](https://img.shields.io/badge/AI-On--device-38BDF8)

[**Download the APK**](https://github.com/bbnss/SuperMens/releases/latest/download/SuperMens.apk)
· [Privacy policy](https://bbnss.github.io/SuperMens/privacy.html)
· [Report an issue](https://github.com/bbnss/SuperMens/issues)
· [SuperBrain inspiration](https://github.com/sidinsearch/superbrain)

</div>

---

## Your second brain, on your phone

SuperMens brings the save-and-understand idea of tools like [SuperBrain](https://github.com/sidinsearch/superbrain) directly to Android. Capture content, extract its text, generate summaries and ask questions about your saved documents.

AI inference runs locally using **Gemma 4 E2B through LiteRT-LM**. No remote backend, app account or AI API key is required.

**Currently in Alpha, tested on Google Pixel 7a and Pixel 9a.** Compatibility and performance on other devices remain experimental.

## What you can save

| Content | What SuperMens does |
| --- | --- |
| PDFs | Extracts page text with OCR, creates a preview and retains an original-file reference when available. |
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
- **Process while charging:** capture online material immediately, then run automatic processing when connected to power. Manual questions and actions remain available on battery.
- **Resume interrupted work:** persistent queues and checkpoints preserve completed transcription clips, PDF pages and summary steps. Heavy tasks run one at a time.
- **Read comfortably:** long transcripts, OCR and document text start with a compact preview. Expand the complete text, collapse it, or return to the top with one tap.
- **Open saved files:** launch a compatible viewer from the detail screen. PDFs keep an original-file reference; relink the original if access expires.
- **Copy and share:** copy summaries, descriptions, OCR and transcripts; share a complete post as image and text or a paginated PDF.
- **Keep your data portable:** export and restore ZIP archives containing structured data, Markdown notes and retained attachments.
- **Keep storage lean:** compressed images and extracted text. Newly imported videos are not retained after preparation; temporary audio is removed after successful transcription.
- **English and Italian:** the interface follows your preferred device or app language, with English as the fallback.

## Get started

1. Download and install the [APK](https://github.com/bbnss/SuperMens/releases/latest/download/SuperMens.apk).
2. Use the home banner to download **Gemma 4 E2B**. On Wi-Fi the download starts immediately; on mobile/metered data choose whether to download now or wait for Wi-Fi. You can also import the supported `.litertlm` file in **Settings**.
3. Use **Test model** to check local inference, then save something through Android sharing or the **+** menu.
4. Open its card to read, copy, share or ask a question.

Obtainium: add `https://github.com/bbnss/SuperMens` as a GitHub source. Version 0.2.4 is a regular release, so prereleases do not need to be enabled; the app remains experimental.

The model is downloaded separately and occupies approximately [2.6 GB](https://developers.google.com/edge/litert-lm/models/gemma-4). Allow additional storage for imports and temporary processing files. The supported model revision and import details are in the [developer notes](docs/DEVELOPMENT.md).

### Device requirements

- **Android 15 or newer** — API 35 minimum.
- **64-bit ARM processor** — `arm64-v8a`.
- **8 GB RAM or more recommended**, with additional memory providing headroom.
- Compatible GPU support for AI image descriptions; text processing can fall back to CPU.

Moving from an earlier debug build to this release-signed build requires exporting your archive, uninstalling the debug app, installing this APK and importing the archive. The model must be downloaded or imported again.

## Privacy and offline use

Summaries, questions, image descriptions, OCR and transcription are processed on the device. Your archive is stored locally.

Internet is needed to download the model and acquire online sources. Once the required material and model are available, local processing can continue offline. OCR is bundled with the APK and works offline from first use.

ML Kit may contact Google for updates and send technical usage and performance metrics. According to [Google’s ML Kit privacy documentation](https://developers.google.com/ml-kit/terms), input content and processing results are not sent to Google. The optional Invidious caption fallback contacts the instance you configure.

## Alpha limitations

AI output may contain mistakes. Check important details against the source.

Public social posts can still be blocked, incomplete or unavailable. YouTube captions are not always accessible. SuperMens processes the content it can obtain; X posts also support adding missing text manually. Subtitle files in `.srt` and `.vtt` format can be imported when captions are unavailable.

Android may delay background work. After force-stopping the app, reopen it to resume processing.

PDF and video references depend on access to the original file. After moving devices, originals may need to be relinked. Retrying a video transcription after temporary audio has been removed also requires the original.

## Build from source

Requires JDK 17, Android SDK 36 and the included Gradle wrapper.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Release builds require [private signing configuration](docs/DEVELOPMENT.md#release-signing).

Built with Kotlin, Jetpack Compose, SQLite, WorkManager, LiteRT-LM and ML Kit. YouTube captions use direct retrieval with an optional Invidious fallback. Third-party components and model weights retain their respective licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).

## License

SuperMens source code is licensed under **GNU GPL v3.0 only, with a Google SDK linking exception**. You may use, modify and redistribute it, including commercially, subject to the license and its corresponding-source requirements. See [LICENSE](LICENSE) and the [additional permission for ML Kit](LICENSE_EXCEPTION.md). The exception applies to BBNSS-owned code and does not relicense Google's SDKs or other third-party components. Model weights retain their own terms.

---

Made by **[BBNSS](https://github.com/bbnss)**. Feedback and reproducible bug reports are welcome.
