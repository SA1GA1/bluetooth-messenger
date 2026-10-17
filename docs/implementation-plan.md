# OffGrid MVP implementation plan

Spec: [согласованный контракт MVP](mvp-spec.md). README.md содержит краткое описание проекта.

## Constraints
Android API 31+, no GMS/network requirement; foreground unlocked exchange only.
One small independently verified change per commit; push after coherent batches of about 5 commits.
Preserve existing user changes, no blanket staging, no force push.

## Tasks
Software implementation and automated validation are complete. Physical acceptance is tracked
separately below and excluded from the completion requested by the user on 2026-10-17.

- [x] 1. Contract, dependency alignment, working debug build/tests/lint.
- [x] 2. Hilt application, domain contracts, errors, fake transport.
- [x] 3. Runtime permissions, BLE discovery, GATT framing, Wi-Fi Direct transfer.
- [x] 4. Identity keys, encrypted database secret, PIN/biometric/session lifecycle.
- [x] 5. Versioned QR, scanner, authenticated mutual pairing, contacts.
- [x] 6. Encrypted direct messages, durable queue/Ack/deduplication, receipts.
- [x] 7. Dark theme/navigation, chats/nearby/detail/message UI.
- [x] 8. Encrypted attachments, progress/retry, photos/location.
- [x] 9. Owner-controlled groups, key epochs, per-recipient delivery, UI.
- [x] 10. Public mode/expiry, settings, edge cases, release validation.

## Verification
Each behavioral task: failing regression test then implementation then passing tests.
Baseline and final: ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug.
Final: assembleRelease, native 16 KiB alignment, instrumentation tests when device available.
Physical acceptance: offline/no-GMS installation; 2-phone text/files/disconnect/reconnect;
3-phone groups/offline recipient/membership rotation. Do not report these as passed without devices.

Final results on implementation commit `af4f808`: debug/release builds PASS, 55 unit tests PASS,
63 emulator instrumentation tests PASS, Lint 0 errors / 16 warnings, release ZIP and 64-bit native
ELF 16 KiB alignment PASS. Both Important findings from the independent review have regression
tests and are fixed. Hardware-only identity instrumentation remains a physical-device check.

## Physical acceptance (outside this completion)
- [ ] Two-phone BLE/text/files/reconnect and offline/no-GMS installation.
- [ ] Three-phone groups/offline recipients/membership rotation.
- [ ] Hardware StrongBox/TEE identity and native Wi-Fi Direct interoperability.

See `docs/device-acceptance.md`. User requested stopping after software MVP completion;
do not start roadmap features or merge the development branch automatically.

## Review focus
- Lost Ack and reconnect cannot duplicate stored messages.
- Forged peer IDs cannot replace trusted keys or impersonate group members.
- Lock/background cannot retain open database or continue radio exchange.
- Failed PIN/biometric and process death cannot bypass cold-start PIN.
- Untrusted frames/files cannot allocate unbounded memory or escape private storage.
