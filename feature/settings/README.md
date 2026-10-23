# :feature:settings

Settings home and its screens: the own profile header, "Account and devices" (linked devices, link a new device),
the profile (name, username, Account ID and "My QR code"), "Look and language" (appearance: phone setting, light or
dark; in-app language), "Advanced" (servers), "Help and about" (how gim works, versions, open-source licences) and sign out.

Appearance is a `ThemeMode` in `ThemeModeStore` (shared preferences `gim_settings`), applied app-wide by `MainActivity`.
The language is a per-app locale set through `AppLocales` (AppCompat, API 26+). To add a language: add its
`values-<locale>/strings.xml` translations, then add its tag to `settings_language_tags` in
`src/main/res/values/arrays.xml`; the app's localeConfig is generated from the resource folders.

The screens reached from here that live in other modules (devices, servers, link device) are wired in the app's
`GimNavHost`; this module only exposes callbacks for them in `SettingsActions`.

## Adding a row or section

1. Add the strings to `src/main/res/values/strings.xml` (English only; follow `docs/glossary.md`).
2. Add a callback to `SettingsActions` (default `{}`).
3. Add a `SettingsRow` to the matching section composable in `SettingsScreen.kt`, or a new
   `private fun XxxSection(actions: SettingsActions)` that starts with a `SettingsSectionHeader` and is called
   from `SettingsScreen` in display order.
4. Wire the callback in `GimNavHost` (new route in `Routes`) and cover the row in `SettingsScreenTest`.

## Extension points (step 6)

- Own photo: `ProfileScreen` shows "Change photo" (`ProfileActions.onChangePhoto`, not wired yet) only when
  `AccountSafety.AVAILABLE` is true; flip it once the SDK can set an own photo, then wire the callback.
