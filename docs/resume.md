# Resume checkpoint

Paused at the user's request on 2026-10-16. Continue the approved MVP, without post-MVP technologies.
Branch: `feature/offgrid-mvp`; original baseline: `861c44d`.

## Current implementation

Native BLE/GATT discovery and bounded packets; SQLCipher/Room; hardware-only identity;
PIN/biometric session controller; mutual QR/SAS pairing; durable direct delivery and receipts;
creator-controlled group epochs; anonymous public broadcasts; encrypted attachment storage;
native Wi-Fi Direct adapter and authenticated encrypted-file socket protocol; Compose navigation,
chats, groups, QR/camera, photos, SAF, coordinates and settings are integrated.

Read `docs/progress.md` for verification. This is a development checkpoint, not completed MVP acceptance.

## Next work, in order

1. Review and test integrated `DefaultMessengerRepository` runtime across unlock/background/reconnect.
   Existing protocol tests construct components directly; screen tests inject fake repositories.
   Test the actual facade and cancellation boundaries, especially simultaneous file cleanup/start.
2. Finish remaining MVP details: source-side file progress; local group notices for deferred/delivered
   messages; safe cleanup of encrypted orphan/pending files after process death. Review queue Ack
   timeout against wall-clock changes; live timeouts should use elapsed time.
3. Handle text containing many JSON-escaped control characters: the 16 KiB text limit must not
   overflow the 64 KiB wire packet limit. Preserve ordinary newlines/tabs and document validation.
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
