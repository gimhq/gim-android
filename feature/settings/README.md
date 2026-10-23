# :feature:settings

Settings home and its screens: the own profile header, "Account and devices" (linked devices, link a new device),
the profile (name, username, Account ID and "My QR code"), "Advanced" (servers), "Help and about" (how gim works, versions, open-source licences) and sign out.

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
