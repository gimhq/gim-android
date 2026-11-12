# gim-android - Task List

> Updated: 2026-11-12

## Usage

Each task is a single line linking to its detail file. All detailed information lives in `docs/task/<timestamp>-<feature-slug>.md`.

### Format

- [ ] [**20260907-1428-add-endpoint Add endpoint**](20260907-1428-add-endpoint.md) `P1`

### Status Markers

| Marker | Meaning |
|--------|---------|
| `[ ]`  | Pending |
| `[-]`  | In progress |
| `[x]`  | Completed |
| `[~]`  | Closed / Won't do |
| `[d]`  | Deleted detail file; index entry retained |

### Priority: P0 (blocking) > P1 (high) > P2 (medium) > P3 (low)

### Rules

- Only update the checkbox marker; never delete the line or change its other content. If the detail file is deleted, mark the entry `[d]`.
- Record change history and deletion reasons in `docs/changelog.md`; update affected dependency and plan references.
- New tasks append to the end.
- See each `<timestamp>-<feature-slug>.md` for full details, except `[d]` entries whose files have been deleted; consult `docs/changelog.md` for their history.

---

## Tasks

- [x] [**20261023-0755-product-ui-i18n-settings Product-grade UI, languages, account and settings**](20261023-0755-product-ui-i18n-settings.md) `P1`
- [ ] [**20261024-1415-native-speaker-copy-review Native-speaker review of zh-CN, zh-TW and es copy**](20261024-1415-native-speaker-copy-review.md) `P2`
- [x] [**20261024-1415-core-preview-text-locale Localize chat preview text produced by gim-core**](20261024-1415-core-preview-text-locale.md) `P2`
- [x] [**20261024-1415-avatar-initials-talkback Stop TalkBack reading avatar initials in contacts and group info**](20261024-1415-avatar-initials-talkback.md) `P2`
- [x] [**20261024-1415-settings-e2e-flake Fix occasional SettingsE2eTest failure**](20261024-1415-settings-e2e-flake.md) `P2`
- [x] [**20261024-1415-contacts-peer-photos Show peer photos in contacts rows**](20261024-1415-contacts-peer-photos.md) `P3`
- [x] [**20261024-1415-forced-theme-cold-start Apply forced light or dark theme from the first frame**](20261024-1415-forced-theme-cold-start.md) `P3`
- [ ] [**20261024-1415-new-chat-camera-real-phone Check the New chat QR scanner camera on real phones**](20261024-1415-new-chat-camera-real-phone.md) `P3`
- [-] [**20261112-1924-icon-one-name-no-usernames App icon, one-name onboarding, no username UI**](20261112-1924-icon-one-name-no-usernames.md) `P1`
