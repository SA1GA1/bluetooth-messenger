# MVP completion checkpoint

Completed software scope on 2026-10-17. Stop here as requested; no post-MVP work.
Branch: `feature/offgrid-mvp`; original baseline: `861c44d`.

## Completion status

Software MVP is complete. User explicitly excluded physical-device acceptance and requested
stopping after completion. Do not resume feature work automatically or add post-MVP technologies.
Implementation commit: `af4f808`; final documentation follows on the same branch.

Implemented: native BLE/GATT discovery and bounded packets; SQLCipher/Room; hardware-only identity;
PIN/biometric session lifecycle; mutual QR/SAS pairing; durable direct delivery and receipts;
creator-controlled group epochs and local delivery notices; anonymous public broadcasts;
encrypted attachments with recovery, progress and cancellation; native Wi-Fi Direct/socket adapter;
Compose chats/groups/QR/camera/photos/SAF/coordinates/settings and history paging.

Final verification on the completed implementation:
- Debug and unsigned release builds: PASS.
- Unit tests: 55/55, no failures/errors/skips.
- Emulator instrumentation: 63/63, no failures/errors/skips.
- Lint: 0 errors, 16 nonblocking warnings (target API, template resources/label, KTX suggestions).
- Release ZIP alignment: PASS; all 10 arm64/x86_64 native libraries in each APK have ELF LOAD
  alignment >=16384.
- Independent branch review: two Important findings reproduced RED and fixed GREEN; no Critical
  or Minor findings. Direct packets cannot inject group messages; strict iterative JSON preflight
  bounds nesting before recursive parsing. No outstanding software findings from that review.

The first combined final test attempt could not attach to the emulator and ran zero tests.
After emulator restart the entire 63-test suite passed; this is the final instrumentation result.
`KeystoreIdentityTest` is intentionally excluded on the emulator and remains a hardware check.
Physical BLE/Wi-Fi Direct, StrongBox/TEE and no-GMS acceptance have NOT been demonstrated.
See `docs/device-acceptance.md` when the user elects to perform that separate acceptance.

APK artifacts for this workspace:
- `/private/tmp/offgrid-build/app/outputs/apk/debug/app-debug.apk` (debug-signed, installable).
- `/private/tmp/offgrid-build/app/outputs/apk/release/app-release-unsigned.apk` (unsigned build).

## Build environment

Use serialized Gradle invocations; do not edit Kotlin sources while any Gradle analysis runs.
Generated files in the Documents checkout developed duplicate directories/XML. This session uses
`/private/tmp/offgrid-build.init.gradle`:

```groovy
allprojects {
    layout.buildDirectory.set(new File('/private/tmp/offgrid-build', project.name))
}
```

```sh
./gradlew --offline --project-cache-dir /private/tmp/offgrid-gradle-project \
  --init-script /private/tmp/offgrid-build.init.gradle \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

An API 36.1 emulator was used, not a hardware acceptance device. Strict hardware identity can reject
an emulator; never add a silent software fallback to make onboarding pass.

## Git instructions and preserved changes

- Keep small lowercase English commits; push coherent batches of about five, never force routinely.
- User explicitly requested redistributed author AND committer dates: consecutive days from
  2026-10-17, varying 3–7 commits/day, non-round distinct times, +03:00. Continue from the latest
  assigned date and its capacity. The current day-capacity pattern is 3,5,7,4,6, repeated.
- Helper `/private/tmp/offgrid-git-dates.py commit --only <paths> -m '<message>'` calculates the next
  timestamp. If temporary files disappear, inspect `git log --reverse --format='%aI %cI %s' 861c44d..HEAD`
  and reconstruct the same policy. Future author dates do not schedule GitHub publication or
  guarantee contribution squares; contributions require default-branch inclusion and linked email.
- Existing implementation history was rewritten only after explicit user request; default `main`
  remains at baseline. Local backups are under `refs/codex/backups/`; do not push those refs.
- README.md is now ignored/untracked at the user's request and remains on disk as the specification.
- Preserve the user's `.idea/deviceManager.xml` staged deletion/untracked replacement and
  staged/working differences in `gradle/wrapper/gradle-wrapper.properties`. Never blanket-stage.

## User stop and quota instructions

Earlier 30% and 15% quota stop thresholds were explicitly revoked by the user.
The latest instruction is to finish the software MVP, commit everything belonging to this work,
and stop. That instruction takes priority over continuing merely because quota remains.
Context was compacted during this development run; `docs/progress.md` is the execution ledger.
