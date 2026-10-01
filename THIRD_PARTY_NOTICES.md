# Third-party notices

These notices do not relicense third-party components or model weights. Transitive dependencies retain their respective licenses and notices.

| Component | Version | License / terms | Source |
| --- | --- | --- | --- |
| AndroidX Core, Activity, Compose, WorkManager and AndroidX test tools | See `app/build.gradle.kts` | Apache-2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| Kotlin and kotlinx.coroutines | 2.2.20 / 1.10.2 | Apache-2.0 | [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Jsoup | 1.22.2 | MIT | [License](https://jsoup.org/license) |
| LiteRT-LM Android | 0.11.0 | Apache-2.0 | [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) |
| ML Kit bundled text recognition | 16.0.1 | ML Kit Terms of Service | [Terms and privacy](https://developers.google.com/ml-kit/terms) |
| ML Kit GenAI speech recognition | 1.0.0-alpha1 | ML Kit Terms and GenAI additional terms | [Terms and privacy](https://developers.google.com/ml-kit/terms) |
| NewPipeExtractor | v0.26.5 | GPL-3.0-or-later | [Source](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5), [license](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/LICENSE) |
| JUnit (test only) | 4.13.2 | EPL-1.0 | [JUnit](https://github.com/junit-team/junit4) |

Gemma 4 E2B weights are downloaded/imported separately and are not included in the APK or source repository. Consult the [pinned model repository](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/7fa1d78473894f7e736a21d920c3aa80f950c0db) for its model license and notices.

## Distribution review in progress

NewPipeExtractor is retained. Its GPL license and the proprietary ML Kit terms require a clarified compatibility basis before public APK distribution. No applicable linking exception has been established in this review. Selecting a license for SuperMens itself would not substitute for resolving that question.

The source repository does not contain ML Kit binaries or model weights; Gradle resolves external SDK dependencies. Distribution of the combined prebuilt APK is pending this compatibility review. This notice records the unresolved point and does not assert that the combined APK is cleared for distribution. Refer to the [GNU FAQ on GPL-incompatible libraries](https://www.gnu.org/licenses/gpl-faq.en.html#GPLIncompatibleLibs) and the component terms above.
