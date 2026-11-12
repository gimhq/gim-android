# 20261112-1924-icon-one-name-no-usernames App icon, one-name onboarding, no username UI

- **status**: completed
- **priority**: P1
- **owner**: bkd-l1/0xhhlawj
- **createdAt**: 2026-11-12 19:24

## Description

Phase A of campaign l1-0xhhlawj-20261112-1924 (no SDK change needed):

- App icon from gim-static: adaptive icon (white glyph from `svg/icon-white.svg` on the app icon's
  `#4040E0` -> `#1C1C88` gradient), round icon and monochrome themed icon; `android:icon` and
  `android:roundIcon` in the manifest. The app shows no in-app logo, so none is replaced.
- Onboarding asks for one name (the display name, `createIdentity`); the username step is gone and the
  recovery kit check finishes onboarding.
- No username UI: the Profile username section, the settings header `@username`, and search by username are
  removed. New chat, new group and add member take an Account ID (pasted, or scanned in New chat) only.

Phase B (after the SDK lane): re-vendor the SDK, recovery key reveal in Settings, note to self, and removal of
the remaining handle code (`GimClient.claimHandle`, `releaseHandle`, `findUser`, `Session.handle`,
`UserProfile.handle` and the fakes). Phase B is tracked in
[20261112-1924-recovery-key-note-to-self](20261112-1924-recovery-key-note-to-self.md).

## ActiveForm

Removed usernames and added the app icon

## Dependencies

- **blocked by**: (none)
- **blocks**: 20261112-1924-recovery-key-note-to-self

## Notes

(none)
