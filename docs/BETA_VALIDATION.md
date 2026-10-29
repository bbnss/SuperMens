# Validation of 0.3.0 beta preparation

## 0.3.0-beta3 / version code 13 — 2026-10-29

- New installs now default to processing on battery. The preference fallback is also false before initialization; updates preserve either saved charging-only choice. Removed the obsolete installation-age policy and its JVM test, and updated the existing Android preference regression to cover the missing preference, initialization, both saved choices and an existing archive. The Android test APK compiles; this session does not claim an instrumentation run.
- The maintainer reports that beta2 works on their phone. That confirms the previous reproduction improved, without supplying device-wide performance measurements or certification for beta3.
- 82 JVM tests and five report-service tests pass. Debug/release/beta lint have zero errors (118/117/118 warnings). Unsigned release APK/AAB, optimized beta APK and Android test APK build successfully. No production signing key or Play credential is supplied to the build.
- Removed redundant localized definitions of the non-translatable app name. The final beta APK identifies itself as SuperMens Beta in default, English and Italian and as `it.supermens.offline.beta`, version code 13. It retains the separate beta2 signing identity and is not debuggable.
- JNI callback verification, APK signature verification, 16 KiB ZIP alignment, and ELF alignment of all six ARM64 libraries pass. Source/APK audit reports zero findings. The shared artifact guard passes for the separately configured experimental beta identity. The common registry is unchanged; its reviewed local configuration is kept outside the app source tree.
- README and release notes describe the actual beta installation, battery default, retained preferences, disabled report sending and remaining hardware-testing work. The ADB profiler accepts an explicit package so measurements can target the separate beta; raw profiling CSVs remain private.
- The GitHub publication inventory is the optimized beta APK plus its SHA-256 file. Logs, signing material, credentials, archives, model weights and private release-review files are excluded. Remote publication is verified separately after upload and must not be inferred from these local checks.

## 0.3.0-beta2 / version code 12 — 2026-10-29

- A private device crash report identified a native SIGABRT in `nativeSendMessageAsync`: JNI could not find `onMessage(String)` and `onDone()`. Examination of the original beta1 APK confirmed that R8 removed/renamed the callback methods and merged the implementation into another class. This was a release-optimization bug.
- Added keep rules for LiteRT-LM JNI callback interfaces and their implementations. The new `tools/verify_native_callbacks.py` checks public instance method names, parameter types and return types in the actual APK DEX. It fails on the original beta1 APK, including the missing `onError(int, String)` callback, and passes on both rebuilt optimized release and beta APKs. No model/runtime dependency changed.
- The home status icon now uses a separate transparent vector cropped to the brain, occupying the previous 30 dp badge area. Grayscale when unloaded and the existing loaded-state pulse are preserved. The vector was rendered and inspected; its shape fits inside the viewport. The launcher artwork is unchanged by this fix.
- Settings displays the installed `BuildConfig.VERSION_NAME` and `VERSION_CODE` in English/Italian, at the top of the page.
- 83 JVM tests pass. Debug/release lint report zero errors (120/119 warnings). Release APK/AAB and Android test APK compile successfully. Release artifacts are unsigned and were built without production signing credentials.
- Official signing is deferred at the user's request. The new `beta` build type uses release optimization and the standard Android debug certificate, installs alongside the existing app as `it.supermens.offline.beta` / SuperMens Beta, and has a separate archive and model. No production key is needed or used for this private test build. `assembleBeta` passes; beta lint reports zero errors and 122 warnings. Package/version and EN/IT launcher labels match the intended beta identity; APK signature, 16 KiB ZIP alignment and ELF alignment of all six ARM64 libraries pass. Source/APK publication audit reports zero findings. The checked installable copy and verification metadata are under ignored `apk/SuperMens-0.3.0-beta2.*`.
- No new on-device inference or UI execution is claimed. The user will test the corrected build on their phone. The existing instrumentation/device/reporting-service gates below remain open.

## Historical beta1 preparation

Implementation and verification sessions: 2026-10-26–27. This records new checks; historical 0.2.8 APKs and emulator results do not validate this branch.

## Completed in this workspace

- 83 JVM tests pass through the committed Gradle 8.14.5 wrapper and Kotlin 2.2.20, including regressions for storage reserve and overflow, archive byte/entry budgets, charging defaults, thermal thresholds, asynchronous streaming, cooperative cancellation and terminal callbacks, deadline/output caps, rejected previews, EN/IT explicit-request guards, existing engine fallback, queue ordering/serialization, title format/checkpoints and model integrity.
- The initial session ran 30 pure Kotlin tests directly. The follow-up built the complete Android app against its dependencies with JDK 21, after running authorized commands outside the socket-restricted sandbox. An import conflict in the Gradle URI validation was found and corrected.
- Final debug APK/AAB and release APK/AAB build successfully. Debug and release lint report zero errors and 120 warnings each. No runtime/model dependency was upgraded.
- Release APK and AAB certificates match the previous 0.2.8 APK. An obsolete local signing configuration was caught by certificate comparison before delivery; the final packages use the matching existing key. APK signature verification, 16 KiB ZIP alignment and ELF alignment of all six ARM64 native libraries pass. Artifacts and public verification metadata are in the ignored `apk/` directory.
- Five report-service tests: durable/private storage, rejection of prompt/archive/identifier fields, UTF-8/body-size limits, volatile rate limit, optional diagnostics and retention.
- AAPT2 resource compilation and link against API 36, including new strings, adaptive/monochrome vectors and data-extraction rules. Link used a temporary manifest with resolved package placeholders in ignored build output; it is not an APK suitable for installing.
- XML catalogs checked for syntax, duplicate names and parity between default, English and Italian.
- Walnut/brain icon rendered and inspected; Play PNG is 512 x 512, RGBA, 8 bits/channel.
- Kotlin source and Gradle script parsed with the cached Kotlin PSI parser. Parsing checks syntax, not Android/Compose/SDK type resolution.
- Publication audit and whitespace checks pass. Existing user executable-mode changes to `gradlew` and `tools/adb_profile.sh` are preserved.

## Blocked or pending

- The final Android UI regression suite remains unverified. Android test APKs compile in a separate x86_64 build directory using a temporary init script; production remains ARM64. An initial targeted run passed 9 of 11 tests (including locale/footer, navigation restoration and all three beta readiness tests) and exposed two visibility failures. A focused share test then passed, while the search test confirmed a zero-height grid with the keyboard open. The final changes hide the download prompt while typing, close the keyboard on incoming shares, reset search/filter scroll and acknowledge incoming-share focus only when its card intersects the viewport. These fixes compile, but their final regression run could not start after emulator disconnections and repeated emulator process crashes (exit 139), including a fresh isolated AVD and multiple graphics modes. Do not describe this as a fully passing Android suite. Diagnostic output is ignored under `app/build/ui-x86/`.
- Re-run `DailyHomeUiTest`, `LanguageUiTest` and `BetaReadinessTest` on a working emulator/device, then the wider instrumentation suite. The native benchmark is explicitly opt-in and has not run. The manual home regression protocol is in `PLAY_BETA.md`.
- Native streaming/cancellation, GPU-only-text fallback, thermal recovery and background quotas still require real-device tests. An upstream cancel request has no guaranteed maximum completion latency; resources intentionally remain held until completion.
- No physical-device speed, quality, energy or memory results are newly available. No device is newly certified, including the previous Pixel candidates.
- Report server is implemented and tested locally as a WSGI application, but not hosted. Its HTTPS endpoint, private contact, proxy configuration, operational review/retention and published privacy details are still needed. Builds without the endpoint disable sending and belong in internal testing.
- Name/trademark clearance, public privacy verification, full adversarial moderation assessment, legal transparency evaluation and Play Console declarations/upload remain external release gates.

Follow `PLAY_BETA.md` to finish these checks before promoting beyond internal testing.
