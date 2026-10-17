# OffGrid MVP implementation plan

Spec: README.md, актуальный контракт MVP имеет приоритет над исходным ТЗ.

## Constraints
Android API 31+, no GMS/network requirement; foreground unlocked exchange only.
One small independently verified change per commit; push after coherent batches of about 5 commits.
Preserve existing user changes, no blanket staging, no force push.

## Tasks
- [ ] 1. Contract, dependency alignment, working debug build/tests/lint.
- [ ] 2. Hilt application, domain contracts, errors, fake transport.
- [ ] 3. Runtime permissions, BLE discovery, GATT framing, Wi-Fi Direct transfer.
- [ ] 4. Identity keys, encrypted database secret, PIN/biometric/session lifecycle.
- [ ] 5. Versioned QR, scanner, authenticated mutual pairing, contacts.
- [ ] 6. Encrypted direct messages, durable queue/Ack/deduplication, receipts.
- [ ] 7. Dark theme/navigation, chats/nearby/detail/message UI.
- [ ] 8. Encrypted attachments, progress/retry, photos/location.
- [ ] 9. Owner-controlled groups, key epochs, per-recipient delivery, UI.
- [ ] 10. Public mode/expiry, settings, edge cases, release validation.

## Verification
Each behavioral task: failing regression test then implementation then passing tests.
Baseline and final: ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug.
Final: assembleRelease, native 16 KiB alignment, instrumentation tests when device available.
Physical acceptance: offline/no-GMS installation; 2-phone text/files/disconnect/reconnect;
3-phone groups/offline recipient/membership rotation. Do not report these as passed without devices.

## Review focus
- Lost Ack and reconnect cannot duplicate stored messages.
- Forged peer IDs cannot replace trusted keys or impersonate group members.
- Lock/background cannot retain open database or continue radio exchange.
- Failed PIN/biometric and process death cannot bypass cold-start PIN.
- Untrusted frames/files cannot allocate unbounded memory or escape private storage.
