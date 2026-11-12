# 20261112-1924-recovery-key-note-to-self Recovery key reveal, note to self, no handle code

- **status**: completed
- **priority**: P1
- **owner**: bkd-l1/0xhhlawj
- **createdAt**: 2026-11-12 19:24

## Description

Phase B of campaign l1-0xhhlawj-20261112-1924, on gim-client-sdk `2e2e5d4`:

- Re-vendor the SDK (`scripts/sync-gim-core.sh`); remove the handle API from the bridge (`claimHandle`,
  `releaseHandle`, `findUser`, `Session.handle`, `UserProfile.handle`) and the fakes; add
  `GimClient.recoveryCode()` and `ChatSummary.isNoteToSelf`.
- Settings > Your recovery key: device authentication (BiometricPrompt, strong biometric or device credential)
  before the key is shown; grouped key with copy, save the kit and the kit QR; `FLAG_SECURE` while shown; hidden
  again when the screen stops. Without a key on this device (`recoveryCode()` is null) the screen says so and
  offers "Create a new recovery key" (the rotation flow with its last-4 check).
- Note to self (`createDirectChat(ownUserId)`): pinned on top of the chat list with a localized title and the
  own avatar with a note badge; mute is its only chat action. Entry points: New chat row, Profile row, and
  tapping yourself in group info.
- Interop script, `InteropTest` and `RestoreE2eTest` follow the new CLI (`gim register <DISPLAY_NAME>`,
  Account IDs everywhere, `gim recovery-key show`).

## ActiveForm

Added the recovery key reveal and the note to self

## Dependencies

- **blocked by**: 20261112-1924-icon-one-name-no-usernames
- **blocks**: (none)

## Notes

The note to self across two own devices is not covered by an automated test here; the fake and the SDK tests
cover the chat itself.
