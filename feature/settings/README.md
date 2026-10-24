# :feature:settings

Settings home and its screens: the own profile header, "Account and devices" (your recovery key, linked devices, link
a new device, create a new recovery key, delete account), "Notifications" (one row per push channel, each opening the phone's settings for it), "Privacy" (read
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

## New recovery key and account deletion

- `NewRecoveryKeyViewModel`: warning -> `rotateRecoveryCode()` -> the new kit (Account ID and key, copy flagged as
  sensitive, "Save as a file" through the app's `shareRecoveryKit`; the text from `RecoveryKitCodec.format`) -> the
  key's last four characters typed back (`matchesKeyEnding`) -> done. The old key already stopped working on the kit,
  so leaving before the check asks first. Hubs in `unsyncedHubIds` keep the old key until a later rotation reaches
  them; the kit says so, naming them by URL from `hubs()` when every URL is known, else counting them. `GimNavHost`
  deletes the shared kit files when the flow ends.
- `DeleteAccountViewModel`: an "I understand" box enables the red button, which runs `AppContainer.deleteAccount`
  (`GimClientManager.deleteAccount`, then the same platform wipe as sign-out). It cannot be cancelled; a failure deletes
  nothing and can be retried. On success `GimNavHost` opens onboarding with an empty back stack, after a dialog naming
  the servers that could not be reached, if any (URLs are read before deleting).

## Extension points (step 6)

This entry is built but hidden behind `ChatActions.AVAILABLE` in `:feature:contacts`; the strings exist, so showing it
is turning the switch on and wiring its callbacks in `GimNavHost`. Nothing behind a switch fakes behaviour.

- Chat actions: `ChatActionsGroup` in `feature/contacts/.../GroupInfoScreen.kt` (mute, pin, archive, block, report;
  `GroupInfoActions.onChatAction`), behind `ChatActions.AVAILABLE`. Direct chats and the chat list long-press reuse the
  `ChatAction` list.
