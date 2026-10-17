# Physical acceptance checklist

Status: NOT RUN. One physical phone available; no radio interoperability claims.

## Two devices, different vendors where possible
- Install APK offline on an Android 12+ device without Google Play services.
- Bluetooth/Wi-Fi enabled, no internet route: discover each other and pair using one QR.
- Reject a pairing request; mismatched confirmation codes must not establish trust.
- Send text in both directions; disconnect before Ack, reconnect, expect one stored copy.
- Queue while peer absent, restart sender, return peer, expect automatic delivery.
- Send a 10 MiB photo and a 50 MiB file; reject larger input before copying/allocating.
- Interrupt a transfer, retry from zero; corrupted or incomplete content cannot be opened.
- Deny/revoke permissions and disable radios during a session; show actionable state.
- Background or lock either device; no further exchange until unlock.
- Cold start requires PIN; resume offers enabled biometrics once, then PIN on failure.

## Three devices
- Creator adds two contacts, both accept the signed group roster and key epoch.
- Send with one recipient absent; status counts recipients independently.
- Restart sender, restore recipient, expect queued messages without duplicates.
- Remove a member, deliver new key epoch to remaining members.
- Former member cannot decrypt new-epoch messages or issue membership changes.
- Simultaneous membership/message events use signed owner epoch, never silent rollback.

Record model, Android build, APK commit, permissions, expected/actual result and logs
without PIN, keys, plaintext messages, contact names or precise coordinates.
