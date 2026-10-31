# Resume checkpoint

Resumed on 2026-10-17. Continue the approved MVP, without post-MVP technologies.
Branch: `feature/offgrid-mvp`; original baseline: `861c44d`.

## Current implementation

Native BLE/GATT discovery and bounded packets; SQLCipher/Room; hardware-only identity;
PIN/biometric session controller; mutual QR/SAS pairing; durable direct delivery and receipts;
creator-controlled group epochs; anonymous public broadcasts; encrypted attachment storage;
native Wi-Fi Direct adapter and authenticated encrypted-file socket protocol; Compose navigation,
chats, groups, QR/camera, photos, SAF, coordinates and settings are integrated.

Read `docs/progress.md` for verification. This is a development checkpoint, not completed MVP acceptance.

## Next work, in order

1. Finish local group notices for deferred/delivered messages and safe cleanup of encrypted
   orphan/pending files after process death. Serialize startup cleanup against new imports.
2. Extend file cancellation tests around simultaneous cleanup/start. Real repository lifecycle
   now has three passing integration tests; screen tests still inject fake repositories.
3. Runtime, source-side file progress, elapsed-time Ack expiry and C0 text validation were checked
   on 2026-10-17: 54 unit tests and 19 selected instrumentation tests PASS. Source progress uses
   a conflated reporting channel; FileAck persists 100%. Tabs/newlines/carriage returns are allowed.
4. Remove unused legacy initialization/UI models if no longer referenced. Improve formatting in
   compact UI/protocol methods without introducing additional architecture layers.
5. Repeat full debug build, unit tests, Lint and instrumentation; build release; verify APK native
   16 KiB alignment. Latest integrated Lint did not finish: PSI walking loop after concurrent edits;
   its Gradle daemon was stopped. Do not call this a passing final Lint run.
6. Run physical acceptance from `docs/device-acceptance.md`: two phones for BLE/text/files,
   three for group/offline/rotation, hardware Keystore and no-GMS installation. No physical radios,
   native Wi-Fi Direct interoperability or hardware TEE acceptance has been demonstrated.

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

## Usage and context request (2026-10-17)

User requested stopping when the five-hour quota has 30% remaining. Local session JSONL events
`event_msg` / `token_count` expose `rate_limits.primary` (`window_minutes=300`, `used_percent`).
Read only this thread via CODEX_THREAD_ID. `/private/tmp/offgrid-usage.py` prints a bounded status;
check between tasks and stop at used_percent >=70. Latest check: 9% used, 91% remaining.

User also requested compaction above 50% context. Latest observed input/window ratio: 53.3%.
No callable compaction tool was exposed; do not claim manual summaries actually freed context.
Current work was finished to a verified checkpoint; user can invoke `/compact` before the next
implementation stage. This file and docs/progress.md preserve the continuation state.
