# :feature:settings

Settings home and its screens: the own profile header, "Account and devices" (your recovery key, linked devices, link
a new device), "Notifications" (one row per push channel, each opening the phone's settings for it), "Privacy" (read
receipts and typing indicator switches, "Blocked people" with unblock), the profile (photo, name, username, Account ID
and "My QR code"), "Look and language" (appearance: phone setting, light or
dark; in-app language), "Advanced" (servers), "Help and about" (how gim works, versions, open-source licences) and sign out.

Appearance is a `ThemeMode` in `ThemeModeStore` (shared preferences `gim_settings`), applied app-wide by `MainActivity`.
The language is a per-app locale set through `AppLocales` (AppCompat, API 26+). To add a language: add its
`values-<locale>/strings.xml` translations, then add its tag to `settings_language_tags` in
`src/main/res/values/arrays.xml`; the app's localeConfig is generated from the resource folders.

The screens reached from here that live in other modules (devices, servers, link device) are wired in the app's
`GimNavHost`; this module only exposes callbacks for them in `SettingsActions`. `NotificationsRoute` takes the push
channels as `NotificationChannelItem`s, built in `GimNavHost` from the app's `PushChannel` after `ensurePushChannels`
(a channel's system settings only open once the channel exists).

## Adding a row or section

1. Add the strings to `src/main/res/values/strings.xml` (English only; follow `docs/glossary.md`).
2. Add a callback to `SettingsActions` (default `{}`).
3. Add a `SettingsRow` to the matching section composable in `SettingsScreen.kt`, or a new
   `private fun XxxSection(actions: SettingsActions)` that starts with a `SettingsSectionHeader` and is called
   from `SettingsScreen` in display order.
4. Wire the callback in `GimNavHost` (new route in `Routes`) and cover the row in `SettingsScreenTest`.

## Privacy and own photo

- The privacy switches live in `SettingsViewModel` (`client.privacy()`, `setReadReceipts`, `setTypingIndicators`): a
  switch moves at once and moves back with a snackbar if saving fails. gim-core syncs them to the user's other devices.
- `BlockedPeopleScreen` lists `privacy().blockedUserIds`, named after the direct chat's peer in the chat list when there
  is one, else by Account ID, and unblocks behind a confirmation. Blocking happens from chat info.
- `ProfilePhotos` holds the photo files in the app cache: `load` downloads a user's photo with `fetchUserAvatar` into
  `gim/avatars/`; `prepare` crops a picked picture to a centred square of at most 512 x 512 and writes a JPEG for
  `setAvatar`. `ProfileRoute` picks the picture with the system photo picker (no storage permission). Only the profile
  header shows the own photo for now; the Settings header keeps the initials.

## Extension points (step 6)

These entries are built but hidden. Each has its own switch in `AccountSafety` (`ChatActions.AVAILABLE` in
`:feature:contacts`); the strings exist, so showing one is turning its switch on and wiring its callbacks in
`GimNavHost`. Nothing behind a switch fakes behaviour.

- Chat actions: `ChatActionsGroup` in `feature/contacts/.../GroupInfoScreen.kt` (mute, pin, archive, block, report;
  `GroupInfoActions.onChatAction`), behind `ChatActions.AVAILABLE`. Direct chats and the chat list long-press reuse the
  `ChatAction` list.
- Create a new recovery key: the row in `AccountSection` (`SettingsActions.onNewRecoveryKey`), behind
  `AccountSafety.NEW_RECOVERY_KEY`. Uses the SDK's `rotateRecoveryCode()` once the flow is built: show the new kit like
  onboarding; hubs in `unsyncedHubIds` keep the old key until a later rotation reaches them.
- Delete account: the row in `AccountSection` (`SettingsActions.onDeleteAccount`), behind
  `AccountSafety.DELETE_ACCOUNT`. Uses the SDK's `deleteAccount()`; on success, wipe the phone like sign-out.
