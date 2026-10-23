# :feature:settings

Settings home and its screens: the own profile header, "Account and devices" (your recovery key, linked devices, link
a new device), "Notifications" (one row per push channel, each opening the phone's settings for it),
the profile (name, username, Account ID and "My QR code"), "Look and language" (appearance: phone setting, light or
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

## Extension points (step 6)

These entries are built but hidden until the SDK has their APIs (plan
`docs/plan/20261023-0755-product-ui-i18n-settings.md`, step 6). The strings exist, so showing one is flipping its flag
(`AccountSafety.AVAILABLE` here, `ChatActions.AVAILABLE` in `:feature:contacts`) and wiring its callbacks in
`GimNavHost`. Nothing behind a flag fakes behaviour.

- Own photo: `ProfileScreen` shows "Change photo" (`ProfileActions.onChangePhoto`, not wired yet) only when
  `AccountSafety.AVAILABLE` is true; flip it once the SDK can set an own photo, then wire the callback.
- Read receipts and typing indicator: `PrivacySection` in `SettingsScreen.kt` (switch values from
  `SettingsScreen(privacy = PrivacySettings(...))`, changes to `SettingsActions.onReadReceiptsChange` and
  `onTypingIndicatorChange`). Waits for SDK read-receipt and typing-indicator settings (get and set).
- Blocked people: the "Blocked people" row in `PrivacySection` (`SettingsActions.onBlockedPeople`). Waits for the SDK
  block/unblock and blocked-users list APIs; the list screen is still to build.
- Chat actions: `ChatActionsGroup` in `feature/contacts/.../GroupInfoScreen.kt` (mute, pin, archive, block, report;
  `GroupInfoActions.onChatAction`), behind `ChatActions.AVAILABLE`. Waits for SDK mute, pin and archive per chat, and
  block and report. Direct chats and the chat list long-press reuse the `ChatAction` list.
- Create a new recovery key: the row in `AccountSection` (`SettingsActions.onNewRecoveryKey`). Uses the SDK's
  `rotateRecoveryCode()` (exists today) once the flow is built: show the new kit like onboarding; hubs in
  `unsyncedHubIds` keep the old key until a later rotation reaches them.
- Delete account: the row in `AccountSection` (`SettingsActions.onDeleteAccount`). Waits for an SDK delete-account
  API; on success, wipe the phone like sign-out.
