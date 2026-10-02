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
| Google Play services base, basement and tasks; ODML Image (ML Kit dependencies) | 18.5.0 / 18.9.0 / 18.2.0 / 1.0.0-beta1 | Google SDK terms, as identified by component metadata | [Google Maven](https://maven.google.com/), [SDK terms](https://developer.android.com/studio/terms) |
| Google DataTransport, Firebase component/encoder utilities, Gson, Guava and annotations | Resolved by Gradle | Apache-2.0 | [DataTransport and Firebase utilities](https://github.com/firebase/firebase-android-sdk), [Gson](https://github.com/google/gson), [Guava](https://github.com/google/guava), [J2ObjC](https://github.com/google/j2objc) |
| Reactive Streams | 1.0.3 | CC0-1.0 | [License](https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/LICENSE) |
| Checker Framework qualifiers | 3.12.0 | MIT | [License](https://github.com/typetools/checker-framework/blob/checker-framework-3.12.0/LICENSE.txt) |
| JSpecify and javax.inject | 1.0.0 / 1 | Apache-2.0 | [JSpecify](https://github.com/jspecify/jspecify), [javax.inject](https://github.com/javax-inject/javax-inject) |
| JUnit (test only) | 4.13.2 | EPL-1.0 | [JUnit](https://github.com/junit-team/junit4) |

Gemma 4 E2B weights are downloaded/imported separately and are not included in the APK or source repository. Consult the [pinned model repository](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/7fa1d78473894f7e736a21d920c3aa80f950c0db) for its model license and notices.

The original LiteRT-LM `LICENSE` and `THIRD_PARTY_NOTICE.txt` supplied in its AAR are also preserved in the APK under `assets/licenses/LiteRT-LM-*`. Those upstream notices cover the SDK's own bundled components and do not change the SuperMens license.

## Google SDK linking permission

SuperMens code owned by BBNSS is distributed under GPL-3.0-only with the additional permission in [LICENSE_EXCEPTION.md](LICENSE_EXCEPTION.md) to link and distribute it with the identified ML Kit SDKs and their necessary Google runtime dependencies and recognition models. Google's components retain their own terms; the additional permission does not grant rights on behalf of Google.

NewPipeExtractor and its integration were removed in version 0.2.2-alpha. This APK does not include that library. The source repository does not contain proprietary Google SDK binaries or Gemma model weights; Gradle resolves the SDK dependencies and the app downloads or imports Gemma weights separately.
