# Validation of 0.3.0 beta preparation

## Play internal candidate 0.3.0 / version code 15 — 2026-10-30

- The owner chose a personal internal-track Play test before production. The
  release variant now uses the API-verified `com.bbnss.SuperMens` identity,
  version `0.3.0`, code `15`. The generated manifest and BuildConfig agree.
  `assembleBeta` still produces `it.supermens.offline.beta`, beta4/code 14;
  debug remains `.offline`. No existing package is renamed or uninstalled.
- A fresh Publisher preflight found no visible consumed version codes, including
  15. This is not a guarantee about codes no longer visible through edits.
- JVM: 89 passed. Apps Script: 7 passed. Publication-audit regressions: 7 passed.
  The local separate-terminal signing helper has 7 passing preparation checks;
  those tests never access a real private key or sign an artifact.
- Debug/release lint: zero errors, 118/117 warnings. Release APK/AAB, beta APK
  and debug Android-test APK compile successfully without production credentials.
- The optimized release APK preserves both native callback contracts and their
  implementations. All six ARM64 libraries have compatible ELF PT_LOAD alignment
  in both APK and AAB. The APK passes the ZIP 16 KiB check. Bundletool validates
  the unsigned AAB; its native-library configuration requests 16 KiB alignment.
  A universal APK generated from this AAB also passes ZIP alignment; that local
  packaging probe has a test signature and is not a distribution artifact.
- The working-tree/release-APK publication audit has no findings with exact
  exceptions for the documented public support address and synthetic fixtures.
- The Android regression suite was compiled but could not run in this session:
  the existing API 36 x86_64 verification emulator exited 139 before ADB became
  available. No physical device is connected. These checks do not certify native
  inference quality, device performance or Play delivery; the owner will exercise
  those using the internal Play build.
- The candidate is unsigned. The owner signs with `bbnss-upload` in a separate
  terminal, then the exact signed artifact requires upload-certificate registration
  verification and the shared artifact guard. No Play upload or production rollout
  has been performed at preparation time. ML Kit GenAI production terms, privacy
  publication/Data safety, store assets and actual device-catalog targeting remain
  production gates; the current functional paths are retained for the personal test.

## 0.3.0-beta4 / version code 14 — reporting activation, 2026-10-29

- The owner supplied the deployed Apps Script `/exec` URL. An anonymous GET followed the expected HTTPS Google content redirect and returned the versioned `method_not_allowed` JSON. One synthetic POST without diagnostics returned `{"version":1,"ok":true}`. The owner confirmed receipt at `lumieitalia@gmail.com` for marker `SM-REPORT-20261029-163040Z`.
- Inserted the verified public endpoint in `reporting.properties` and advanced the local candidate to version 0.3.0-beta4 / code 14. The published beta3 download is unchanged. Updated deployment, privacy EN/IT and plan wording to distinguish that earlier APK from the new configured build.
- Added `LiveAiReportTest`, which is skipped unless explicitly enabled with instrumentation argument `liveReport=true`. It uses the actual sender and editable dialog, selects consent, omits diagnostics and sends one synthetic report without retry. It does not read email. Normal Android regression runs cannot automatically send this probe. A separate instrumentation run without `liveReport` returned the expected assumption-skip before submitting, in 0.119 seconds.
- **89 JVM tests pass** with the configured endpoint. `assembleBeta` and `lintBeta` succeed; beta lint has **zero errors and 118 warnings**. The standard experimental debug certificate matches beta3; no production signing key or Play credential is supplied.
- **All 8 report Android tests pass** on API 36 x86_64 in 18.832 seconds: the seven consent/preview/diagnostic/error regressions plus one explicitly enabled real submission through the dialog and `AiReport.send()`. The actual app confirmed the positive JSON reply for `SM-APP-20261029-164722Z`, using version beta4, the edited synthetic response and no diagnostics. The owner also confirmed receipt of this second probe at lumieitalia@gmail.com. The real Android submission and mailbox-delivery check are complete for this candidate. This run uses a separate debug emulator build; it is not native inference or a physical-device beta benchmark.
- The final optimized ARM64 beta APK passes the shared artifact guard against the experimental identity, signature verification, package/version/non-debuggable checks, JNI callback verification, 16 KiB ZIP alignment and ELF alignment of all six native libraries. The verified endpoint is present in its DEX. The local installable copy is ignored under `apk/SuperMens-0.3.0-beta4.apk`, with matching `.apk.sha256` and `.verification.json` files.
- SHA-256 of the beta APK: `1c9a77db8820ff694a98da85e4e0e6212e19619978ded2d92c6971845ea210ed`. Certificate SHA-256: `DB:32:F5:EC:E2:55:41:42:D9:89:01:62:D1:A9:7A:76:34:8C:5F:15:60:50:60:B5:2C:AF:FE:F5:AB:A4:93:37`. No GitHub/Pages/Play publication was performed. Updated EN/IT privacy pages must be published before distribution beyond this maintainer test.


## Email reporting integration — 2026-10-29 (source preparation only)

- Replaced the WSGI/SQLite report prototype with `tools/apps-script/Code.gs` and an explicit send-mail-only manifest. Recipient and subject are fixed; strict validation enforces the existing 16,000-byte text limit and 32,768-byte request limit, including UTF-8 and JSON escaping. A persistent script-wide lock/counter caps reservations at 80 per 24-hour window and checks Google's remaining quota. Mail failures consume a reservation and never return success.
- Android performs one POST and follows ContentService redirects with GET only at the expected HTTPS Google content host/path. Confirmation requires positive versioned JSON. Timeout, service rejection and other failures have distinct UI handling; no automatic retry is added. Diagnostics and consent default off; submitted diagnostics match the preview exactly. Free chat is unchanged.
- **7 Apps Script service tests pass**, running the actual script with mocked Google services using Node 24 (`node --test --test-isolation=none tools/apps-script/report_service.test.cjs`). Covered valid submissions, fixed plain-text email headers, omitted/included diagnostics, unexpected fields/types, exact UTF-8/body limits, quota/window exhaustion/reset, Google quota exhaustion, busy lock, email/storage failures, no logging and manifest scopes. These are simulated service tests, not Google delivery checks.
- **89 JVM tests pass** through `:app:testDebugUnitTest`, including all 7 new transport tests. Covered endpoint validation, single POST and GET redirects without body replay, disallowed hosts/schemes/ports/paths/login redirects, POST-preserving redirects, redirect loops, HTTP/MIME errors, oversized replies, request limit, offline errors and timeouts without retry. Zero JVM failures/errors.
- **All 7 `AiReportUiTest` Android tests pass** on an API 36 x86_64 emulator (`am instrument`, 14.696 seconds). Covered initially unchecked consent/diagnostics, only edited text submitted, diagnostics matching the preview exactly, timeout/offline/service errors without retry or success feedback, missing endpoint and positive JSON/payload byte rules. The test setup grants the app's notification permission and waits for the dialog composition before asserting UI state.
- The initial environment restrictions were resolved on retry. Full Gradle `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:bundleDebug :app:assembleDebugAndroidTest` succeeds. Debug lint has **zero errors and 118 warnings**. ARM64 debug APK/AAB and the Android test APK build successfully with the standard debug signing configuration; no production key or Play credential is supplied. These artifacts are for testing, not Play release candidates.
- UI execution used a separate temporary x86_64 build directory and init script; production ABI/output settings remain ARM64. The emulator ran read-only without snapshots, with host graphics and Vulkan disabled after software-rendering launches crashed. No native inference, physical-device certification or broader Android regression-suite success is claimed. Current EN/IT resource keys and reciprocal privacy-page links validate; `git diff --check` passes.
- Updated the preparation plan, EN/IT privacy and [deployment/deletion procedure](REPORTING_APPS_SCRIPT.md). Public `reporting.properties` sets the contact to `lumieitalia@gmail.com`; the endpoint remains empty until the owner deploys and verifies the service. No Google deployment, email delivery, privacy publication, production signing or Play upload was performed. Before the Play build, a synthetic report sent **from the app** must be confirmed in the destination inbox. The earlier beta artifact results below do not validate these source changes.

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
