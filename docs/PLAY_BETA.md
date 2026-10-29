# SuperMens 0.3.0-beta3: Play readiness and test protocol

This branch prepares a controlled beta. It is **not a hardware certification or approval to publish to production**. The runtime remains LiteRT-LM 0.11.0 with the pinned Gemma 4 E2B weights, Android 15+, ARM64. NPU execution and a smaller model remain separate investigations.

## Implemented

- Home previews open posts without a refresh button; regeneration remains in the post's top bar. Incoming shares clear search/type filters and scroll to the exact received post, including when the archive refresh arrives asynchronously. Ordinary return navigation still restores the saved browsing position.
- The incoming-share request is acknowledged only once that card intersects the grid viewport. Search/filter changes start at the beginning; the model download prompt is hidden while the keyboard is open so it cannot reduce the grid to zero height on smaller screens.
- The home counter includes the app icon: grayscale when the engine is unloaded, colored and pulsing when `LocalAi.loaded` is true. The settings footer links to the repository with a small GitHub icon and BBNSS, without the previous tool attribution.
- Charging-only automatic processing defaults off for new installations, so processing can begin on battery after the model is ready. Users can enable the restriction in Settings. Both saved choices are preserved on updates.
- Severe Android thermal status pauses heavy work, including manual inference, at a checkpoint or by requesting native cancellation. Cooling wakes the queue. Charging does not override thermal pauses.
- GPU multimodal initialization falls back to GPU text/audio before trying CPU. The selected backend and missing vision capability are visible. A successful startup never marks a device as certified.
- Streaming chat previews are explicitly provisional. Stop marks active/pending analysis jobs failed and asks native inference to stop through worker checkpoints. Retry resumes existing checkpoints. Resource ownership is retained until a terminal native callback; cancellation is cooperative, not a hard deadline guarantee.
- Each generation requests cancellation after 180 seconds or an 8,000-character output cap. The cap is enforced during streaming, not by truncating successful output afterward. LiteRT-LM's 4,096-token total context remains in place; no unsupported output-token API is assumed.
- Summaries at most 2,400 UTF-8 bytes use one final title/summary request. Longer input uses bounded UTF-8 chunks and reduction checkpoints. Already generated conversation titles are reused.
- Idle engines are released after five minutes if no heavy work is ready, and on background memory pressure when safe.
- Download/import requires room for the entire incoming model plus 512 MiB. Imports preserve the existing model until digest verification and reject oversized input while copying. Model removal preserves the archive. Suggested 6–8 GB free is a practical margin, not a measured minimum.
- ZIP export is staged before writing the selected destination. Export and restore share a 2,000,000,000-byte uncompressed budget and 9,999-entry budget, including Markdown and manifest. ZIP exports are unencrypted.
- English and Italian stay inside the App Bundle for offline language changes. Explicit cloud-backup/device-transfer exclusions preserve the existing user-controlled migration design.
- Original walnut/brain adaptive icon, monochrome icon, SVG and 512 px Play PNG replace the superhero artwork in active UI. Historical artwork remains in source history/files; it is not the active launcher.
- AI disclosure, editable report preview and explicit consent are available for generated summaries, image descriptions, chat/translation replies and document answers. Diagnostics are optional and contain no archive/prompt/URL/serial/Android ID.
- Free chat includes safety instructions and a small EN/IT guard for explicit requests. This is **not comprehensive moderation**; adversarial/multilingual evaluation remains a production gate.

## Reporting service and privacy: required deployment work

`tools/report_service.py` is a private WSGI receiver for `POST /reports`. Accepted requests return HTTP 201 only after a successful SQLite commit. Payload: `version: 1`, `reason` (`child_safety`, `harmful`, `deceptive`, `other`), selected `response` (up to 16 KB UTF-8), `app_version`, optional allowlisted `diagnostics`. Prompts, URLs and arbitrary diagnostic keys are rejected. Maximum request body is 32 KiB. Nothing is automatically transmitted by the Android app.

Run under a dedicated account with a persistent private database, behind an HTTPS reverse proxy. The included single-process development entry point binds only to localhost:

```sh
python3 tools/report_service.py --database /var/lib/supermens-reports/reports.sqlite3
```

For production use a WSGI server with request timeouts and bounded workers. Configure the proxy for body-size limits, connection timeouts and public-IP rate limits. The app service keeps an additional volatile limit of 60 requests per peer/hour. Behind a proxy this may be a shared limit; it deliberately does not trust arbitrary forwarded headers. Disable access-body logging and redact/minimize proxy access logs. The database is created mode 0600. Store it on encrypted storage, restrict operators and exclude it from public backups.

Reports expire after 30 days at startup and on each accepted submission. Schedule daily cleanup as well (SQLite: `DELETE FROM reports WHERE created < unixepoch()-2592000;`). Apply the same policy to backups. Review `child_safety` reports promptly and other reports regularly; record the disposition and resulting filter/test changes. Do not publish report text as a GitHub issue. Report content itself may be sensitive; only authorized reviewers should open the private database. This service does not send emails or expose a public report-reading API.

Set these build variables once the receiver and private contact are real:

```sh
export SUPERMENS_REPORT_ENDPOINT=https://YOUR-HOST/reports
export SUPERMENS_PRIVACY_EMAIL=YOUR-PRIVATE-CONTACT
```

The endpoint is public configuration, never an embedded credential. Builds with no endpoint show that reporting is unavailable and cannot send. The GitHub experimental beta explicitly documents that limitation. Configure and verify the service before distributing beyond internal testing on Play. Set `SUPERMENS_PUBLIC_RELEASE=true` for an official public release: Gradle rejects missing endpoint/contact. This guard does not prove service health or policy compliance.

Publish the updated privacy page with actual contact and report-hosting information before enabling reports for users. Populate Play Data safety using the exact resolved ML Kit SDKs and the report path; do not claim no data collection merely because inference is local. Evaluate applicable EU transparency requirements and machine-readable output marking separately; visible labels alone are not a legal certification. Source: [ML Kit disclosure](https://developers.google.com/ml-kit/android-data-disclosure), [Play AI content policy](https://support.google.com/googleplay/android-developer/answer/13985936?hl=en).

## Build and automated verification

Use JDK 17/21, SDK 36 and the committed Gradle wrapper. Compile release artifacts without production signing credentials; official signing happens separately in the maintainer's terminal. For private testing, `assembleBeta` creates an optimized APK with the standard debug signature under the separate package `it.supermens.offline.beta` (SuperMens Beta). The regular installed app is preserved; the beta needs its own model and an optional archive import. See DEVELOPMENT.md.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:bundleRelease
./gradlew :app:assembleBeta :app:lintBeta
python3 tools/verify_native_callbacks.py app/build/outputs/apk/beta/app-beta.apk \
  --dexdump "$ANDROID_HOME/build-tools/36.0.0/dexdump"
python3 -m unittest discover -s tools -p test_report_service.py -v
python3 tools/publication_audit.py
```

Inspect the AAB with bundletool, generate APKs and verify both ZIP and native ELF 16 KB alignment. Install the Play-delivered release, not only a debug APK. Verify an update from 0.2.8 preserves archive, charging preference and model without a new download. Change EN/IT offline after a Play install. Exercise TalkBack, large font, edge-to-edge, denied microphone/notification permissions, system camera and third-party document providers.

Implementation-session validation is in `BETA_VALIDATION.md`; historical 0.2.8 test results are not new results for this branch.

## Device and performance protocol

Use `testing/device-results.csv` for physical measurements. Every listed device is a **candidate**, not approved hardware. Begin with Pixel 7a/9a, two recent Snapdragon phones from different manufacturers, then Exynos/MediaTek and a lower-memory device. Pixel 4a with LineageOS is a separate stress case. Record exact OS/ROM, RAM, SoC, runtime/backend and app version. Do not use NPU presence or branded RAM alone as eligibility.

Use only public/synthetic content: a 500–1,000-word note, 10-page and long PDFs, OCR image/photo, 1-minute and 10-minute audio, multi-turn chat, EN/IT translation, then a mixed queue for 30 minutes. Repeat short cases three times, separate cold load from warm inference, and preserve numbers/negations/names in expected answers. Exercise source text containing malicious instructions and unavailable/incomplete social pages.

Home regression check: scroll to an older day, share a new link from another app and confirm the exact card appears. Repeat from a type filter and from an active search with the keyboard open, then rotate/recreate the Activity. Search results must remain visible without dismissing the download prompt manually. Confirm refresh exists only inside the post, the settings footer opens the repository via BBNSS/GitHub, and the home badge follows actual engine loading/unloading (grayscale, then colored/pulsing, then grayscale again).

A real native text smoke benchmark is opt-in after installing/verifying the weights:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=it.supermens.local.NativeBenchmarkTest \
  -Pandroid.testInstrumentationRunnerArguments.nativeBenchmark=true
```

It writes `benchmark-text.json` in the app-specific external directory using synthetic input and records three runs. Other instrumented tests continue to run without weights. Missing weights cause a skipped native benchmark, never a fake passing inference result. This benchmark is only a first text smoke test, not the complete protocol.

Record load time, first token, complete response, PSS, energy and thermal status. Use `tools/adb_profile.sh --package it.supermens.offline.beta` with ADB over Wi-Fi for unplugged energy tests, compare the same idle baseline, screen brightness and battery range, and allow cooling between trials. The private profiler CSV contains the ADB serial and must not be published. In-app diagnostics intentionally omit it. Battery percentage over a few minutes is insufficient to certify energy consumption.

Test interrupted Wi-Fi/mobile downloads, low storage, corrupt weights, reboot, model preservation on app update, background/Doze, denied notifications, thermal pause/resume and cancellation. Test ZIPs around byte/entry limits, bad paths, truncated files and available temporary space. No failure may remove the existing archive or installed model.

Initial product targets: warm short question <=30 seconds, standard note summary <=60 seconds, short transcription <=2x recording duration. These are proposed acceptance targets, not measured performance or Play requirements. A device that repeatedly throttles, crashes, loses data or cannot deliver an advertised feature does not qualify. Record failures and a decision per feature/backend rather than averaging them away.

## Console and public-release gates

1. Confirm `it.supermens.offline`, resolve the retired app-signing certificate and configure Play App Signing. Version code is now 13. Do not substitute the shared upload key for the app-signing identity of an existing installation. Reserve the final public name only after checking app-store and relevant trademark conflicts.
2. Distribute to 5–10 internal testers, then approximately 20–30 closed testers for two weeks. Internal testers bypass device exclusion rules. Give reviewers download/setup instructions and a synthetic sample; a crawler spending a few minutes before weights are ready does not test inference.
3. Complete listing assets, content rating, audience, privacy/Data safety, app access and foreground-service declarations. Avoid claims of Google affiliation, universal NPU support or zero network transmission.
4. Close remaining report-service, safety and device-measurement gates. Verify public privacy/contact URLs from independent networks. Keep the first release restricted to actually qualified devices using catalog exclusions/RAM/SoC rules, reviewing real variants and newly added models. Do not infer a universal allowlist from the manifest.
5. Monitor Android vitals and user reports by hardware, OS and backend. First publication cannot use a percentage staged rollout; use closed testing and restricted availability first. Percentage rollout applies to later updates. Excluding a device later does not repair existing installations.

No Console upload, public hosting deployment, legal name clearance or physical-device certification has been performed by this code change.
