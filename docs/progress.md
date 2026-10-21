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
