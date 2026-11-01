# Execution ledger — plan: docs/implementation-plan.md

Baseline: 861c44d. Existing staged deletions and untracked replacements belong to the user.
Ruling: use a feature branch in the current checkout to preserve the selected working files and index; no separate worktree copies stale HEAD.
Baseline build: duplicate annotations and AAR compileSdk mismatch (36.1 vs dependencies requiring 37).
Pre-flight: discovery feeds authenticated transport; transport carries protocol; storage owns durable queue; session gates storage and radios.
Task 1: in progress.

Build foundation: assembleDebug, testDebugUnitTest, lintDebug PASS (2026-10-16).
Session policy: RED 3/7 failures → GREEN 7/7; full unit suite PASS.
Ruling: PIN is required after foreground idle timeout; biometric resume only follows a previously unlocked session.

Validation correction: overlapping Gradle invocations caused a transient missing-style AAPT failure; all subsequent builds are serialized.

Task 1: complete; supported SQLCipher 4.19.1, ZXing 3.5.3, Navigation 2.9.8, SDK 37.1/min 31.
Hilt application compiled. Security/framing: RED 12 failures on stubs → GREEN all 24 unit tests.
Task 2: interfaces complete, fake transport and session integration pending.

Task 4 (identity slice): hardware-only signing/agreement repository compiles; device round-trip instrumentation added, NOT RUN.
Ruling: generated artifacts moved to /private/tmp/offgrid-build via an untracked init script for this session; duplicated XML/cache directories in app/build broke resource parsing and clean. Source/default build configuration unchanged.
Checkpoint: external build assembleDebug/testDebugUnitTest/lintDebug PASS, 29 unit tests, 0 failures. SQLCipher arm64/x86_64 LOAD alignments 16384 verified.
Ruling: crypto/protocol work precedes radio acceptance because a second physical phone is unavailable; no radio acceptance claims.

Storage: Room/SQLCipher instrumentation RED 4/5 failures → GREEN 5/5 (queue atomicity, deduplication, recipient Ack, restart/retry, encrypted header/wrong key, history reset).
PIN envelope: RED 4 failures → GREEN 41 unit tests. PBKDF2-HMAC-SHA256 210000 iterations + AES-GCM; outer protection uses a non-exportable Keystore AES key.
Vault/session mutation check: removing secret clearing produced 2/4 instrumentation failures; restoration pending GREEN.
BLE implementation compiles; no physical interoperability acceptance. Beacon uses private test manufacturer 0xffff plus app UUID in scan response, full UUID fits legacy advertising size.

Vault/session GREEN: 4/4 emulator tests; storage 5/5; unit suite 41/41. Lint identified missing coarse-location declaration alongside fine; fixed before next checkpoint.

Session completion epochs: RED delayed-auth test → GREEN 42/42 unit tests.
Checkpoint: storage/vault instrumentation 9/9 PASS; lint 0 errors (15 warnings, mostly dependency update recommendations); native BLE service waits for service registration and rejects callbacks from stopped generations.

## User-requested pause checkpoint (2026-10-16)

Implemented and integrated QR/SAS pairing, direct messages, group membership/key rotation,
anonymous public messages, encrypted attachment storage/socket delivery, and Compose screens.
Native Wi-Fi Direct negotiates addresses/services; physical interoperability remains untested.

Earlier focused emulator checks PASS: session controller 5/5, pairing 3/3, public broadcast 3/3,
PIN UI 2/2, storage 5/5, vault 4/4. These were not all repeated in the final selected run.

Final checkpoint command PASS: testDebugUnitTest + connectedDebugAndroidTest selecting
GroupDeliveryTest, DirectDeliveryTest, FileDeliveryTest and MessengerScreenTest.
- Unit: 52 tests, 0 failures/errors/skips.
- Selected instrumentation: 27 tests, 0 failures/errors/skips.
- Includes actual local TCP encrypted-file round trip, malformed size rejection, interruption,
  duplicate Ack/tombstones, group rejoin replay regression, deferred read receipts, and photo draw.
- Local TCP uses fake FileLink adapters and is not native Wi-Fi Direct hardware acceptance.

Review fixes: stale leave cannot evict a rejoined membership; stale removal cannot erase a new
queue; membership changes are serialized; history clearing cancels scoped Read controls;
incomplete files stay unread; file plaintext length must agree with authenticated ciphertext;
transfer cleanup rejects replacement reservations until finished; photo disposal captures the
actual bitmap; group details hide unsupported deletion and inactive membership controls.

Latest integrated Lint was stopped after an observed PSI walking loop while sources changed;
no final integrated Lint/release pass is claimed. Ruling: freeze source edits for the entire Gradle
invocation, including analysis. Full validation and remaining MVP details are in docs/resume.md.

Git ruling requested by user: distribute implementation author/committer dates from 2026-10-17
over consecutive days with 3–7 commits/day and distinct non-round times. Rewrote the implementation
branch with force-with-lease after explicit authorization; main/baseline and user edits preserved.
README.md is ignored/untracked but retained locally. Paused by explicit user request; resume from
docs/resume.md without repeating completed implementation or claiming MVP acceptance complete.

## Resumed 2026-10-17

Real AndroidSessionController + DefaultMessengerRepository integration: 3/3 emulator tests PASS.
Covers authenticated delivery/Ack, cancellation of a GATT write without losing the runtime,
background cancellation/database closure/private observable clearing, and delivery after PIN unlock.
Usage telemetry is available in this thread's local session token_count events: check primary
300-minute used_percent between tasks and stop at >=70%, per user request.

RED→GREEN: non-printing C0 text rejection (ordinary tabs/newlines retained); source-side file
progress (0→100 after authenticated transfer); elapsed-time delivery expiry despite forward/backward
wall-clock changes. Final command testDebugUnitTest + selected DirectDeliveryTest/FileDeliveryTest/
MessengerRuntimeTest PASS: 54 unit tests, 19 instrumentation tests, no failures/errors/skips.
Ruling: wire text rejects C0 controls except tab, LF and CR, preventing their 6x JSON escaping from
overflowing the bounded packet; normal user-visible text and the 16 KiB UTF-8 limit are preserved.
Latest quota check: 91% remaining. Context exceeded the user's 50% compaction threshold; no
compaction tool is exposed. Recorded the checkpoint for user-triggered /compact before new work.

2026-10-17 continuation after compaction: five-hour quota observed at 19% used; stop at 70%.
Encrypted recovery: new store test RED (orphan survives) → GREEN 7/7; runtime unlock recovery
RED (orphan survives) → GREEN real MessengerRuntimeTest 4/4 and full unit suite 55/55.
New commands wait for initialization of the exact reopened database; transport subscribes before
runtime readiness. Completed attachment IDs retain ciphertext; uncommitted/orphan and pending
ciphertext are removed before imports or incoming offers can start.

Group delivery notices: service regression RED (0 notices instead of 2) → GREEN GroupDeliveryTest
7/7. Local notices batch committed Ack/Read deliveries per recipient; notification marking and
insertion share one Room transaction, so opening the database again cannot duplicate them.
Ruling: store the notification marker in the unreleased v1 outbox schema, consistent with previous
unreleased schema edits; already installed development builds need a local reset after this change.
No migration from a published version is being removed; cost is resetting test/development data.

Repository group notices: RED (no deferred notice) → GREEN 12/12 runtime/group instrumentation,
55/55 unit tests. Text/location and attachment commands now explain absent recipients; committed
delivery batches are announced by maintenance and remain deduplicated after unlock.
File startup cancellation: RED BindException when rebinding a leaked socket → GREEN FileDeliveryTest
11/11, including replacement admission blocked until Wi-Fi cleanup finishes. Listener is closed
when task launch throws or its job completes before entering the body; normal TCP round trip passes.

Recovery failure: RED (uncaught initialization error; commands wait indefinitely) → GREEN
MessengerRuntimeTest 6/6. Initialization publishes either success or failure for the exact open
Room instance. Failed startup rejects commands, shows an error, does not subscribe to packets,
and remains cancellable by session lock. Test scope captures unexpected coroutine exceptions.
