# 20261023-0755-product-ui-i18n-settings Product-grade UI, languages, account explanations and settings

- **status**: approved
- **createdAt**: 2026-10-23 07:55
- **approvedAt**: 2026-10-23 09:27
- **relatedTask**: 20261023-0755-product-ui-i18n-settings

## Context

Goal (user, 2026-10-23): take the Android app from demo to a basic product,
benchmarked against Signal and Telegram, so that a 12-year-old can set it up
and use it alone. Covers UI polish, multiple languages, a clear explanation of
the account model, and settings.

Current state:

- Modules: `:app`, `:core:bridge`, `:core:designsystem`, and the features
  `onboarding`, `chatlist`, `chat`, `contacts`, `linking`, `devices`, `hubs`
  (about 9.7k lines of main Kotlin, 56 JVM and 21 device test files).
- Navigation: onboarding, then the chat list. Devices, Hubs, Link device and
  Sign out sit in the chat list overflow menu. There is no Settings screen,
  no profile screen, and no way to change the display name or handle after
  onboarding, although the SDK offers `setDisplayName`, `claimHandle` and
  `releaseHandle`.
- Languages: English only (`values/strings.xml` per module), no
  `localeConfig`, no in-app language choice.
- Wording is protocol-level: "hub", "handle", "user ID" (`u` + 55
  characters), "revoke", "verified/unverified", "primary/home hub".
- Onboarding asks for a hub address first. Restoring needs the user ID and
  the 39-character recovery code, but the user ID is shown only under
  Devices; the recovery screen shows the code alone, once.
- Theme: light and dark color schemes exist, but `GimColors` (chat-specific
  colors) has only a light set.
- SDK API gaps that limit scope (`gim-client-sdk/docs/API.md`): no own
  avatar, no mute/pin/archive, no read-receipt or typing toggles, no way to
  show the recovery code again, no account deletion, no block or report.

## Proposal

Work in six steps, each a reviewable commit with green gates.

### 1. Plain words (glossary) and English copy rewrite

User-facing terms, used the same way on every screen:

| Today | New |
|---|---|
| Hub | Server (hub management goes under Settings > Advanced) |
| Handle | Username |
| User ID | Account ID |
| Recovery code | Recovery key |
| Revoke device | Remove device |
| Verified / Unverified hub | Connected / Not checked |
| Home / Primary hub | Main server |

Rewrite all strings at about a 12-year-old reading level: short sentences,
one idea per sentence, say what to do next. Errors explain what happened and
what to try.

### 2. Onboarding and account explanation

- Welcome: three short intro pages: no phone number or email, your account
  lives on your devices, messages are locked so only the chat can read them.
- Create account: name first; the server step is skipped when a default
  server exists (release builds), with "Use another server" under a small
  link.
- "Recovery kit" instead of the bare code: Account ID and recovery key
  together, explained as "a spare key to your account". Options: copy,
  save to the password manager (Android Autofill/Credential Manager), or
  share as a text file. Then a short check: type the last 4 characters of
  the key before continuing.
- Restore: accepts the recovery kit text pasted in one field (Account ID and
  key are parsed out), with the two separate fields as a fallback.
- Username step explains what it is for ("friends can find you with it").

### 3. Settings (new `:feature:settings`)

Entry: your avatar in the chat list top bar (Signal pattern, user decision
2026-10-23); the overflow menu goes away. Sections:

- **Profile**: name (edit), username (claim, change, remove), Account ID with
  copy, and a "My QR code" card that friends can scan to start a chat.
- **Account and devices**: what the recovery key is and why to keep it,
  linked devices (today's Devices screen, reworded), link a new device.
- **Notifications**: per-channel switches through the system notification
  settings (deep link per channel).
- **Appearance**: theme (system, light, dark), with dark `GimColors`.
- **Language**: in-app language picker (`AppCompatDelegate.setApplicationLocales`,
  `localeConfig` generated from the resources).
- **Advanced**: servers (today's Hubs screen, reworded).
- **Help and about**: "How gim works" (the account explanation again), app
  and SDK version, open-source licences.
- **Sign out**, separated, with a confirm dialog that says the recovery kit
  is needed to come back.

New chat gains "Scan QR code" next to the username search.

### 4. Languages

- Move every remaining hard-coded text into resources; make lint
  `HardcodedText` and `MissingTranslation` errors.
- Ship English, Simplified Chinese (`zh-CN`), Traditional Chinese (`zh-TW`)
  and Spanish (`es`) in this step (user, 2026-10-23); the structure allows
  more languages later.
- Plurals, date and time formats follow the selected locale.

### 5. UI polish against Signal and Telegram

- Chat list: larger avatars with initials and stable colors, relative times,
  unread badges, message status ticks, clearer empty state with a "Start a
  chat" action, swipe-free layout (no mute/archive in the SDK).
- Chat: bubble spacing and grouping by sender and time, date chips, reply
  and reaction affordances that are discoverable by long-press, a composer
  with attachment and send buttons that stay put.
- Accessibility: 48 dp touch targets, content descriptions, font scaling up
  to 200 % without clipping, TalkBack order on every new screen.
- Consistent top bars, dialogs and snackbars from `:core:designsystem`.

### 6. Account safety features (waits on the SDK)

Built once a gim-client-sdk build with the APIs is vendored into
`:core:bridge` (`rotateRecoveryCode()` exists today; the rest need gim-core
and gim-hub changes):

- Profile: own profile photo.
- Privacy: read-receipt and typing switches, blocked users.
- Chat actions: mute, pin, archive, block, report.
- Account: "Create a new recovery key" (a lost key cannot be shown again;
  the new kit replaces it, and hubs in `unsyncedHubIds` keep the old key
  until a later rotation reaches them), delete account.

Step 3 lays out the Settings screens so these entries have a place; they stay
hidden until the API exists, with no fake behaviour.

### Verification

- JVM unit tests for the new ViewModels (settings, profile edit, recovery kit
  parsing and check, language choice) and Robolectric Compose tests for the
  new screens, written first per change.
- Existing gates: `./gradlew lint testDebugUnitTest assembleDebug
  assembleRelease`; lint fails on missing translations.
- Device E2E suite against gim-hub stays green; add one onboarding-to-first-
  message flow with the new screens.
- Manual pass on an emulator in each language and in dark mode, with
  screenshots attached to the change.

## Risks

- Large diff across most feature modules; mitigated by the five separate
  steps.
- Renaming terms in the UI only: code, logs and the SDK keep hub, handle and
  user ID, so support conversations need the glossary.
- Translations written here need a native-speaker review before release.
- Saving the recovery key to a password manager depends on the device's
  provider; copy and file share remain available.
- Building needs the Android SDK and a JDK in a sibling container (none in
  this environment); device E2E needs an emulator, which needs `/dev/kvm`.

## Scope

In: steps 1 to 6 in gim-android. Step 6 (after a new SDK build is vendored): own profile photo; mute,
pin, archive; read-receipt and typing switches; block and report; a new
recovery key after onboarding; account deletion (user, 2026-10-23).

Out: the SDK, gim-core and gim-hub work for step 6, planned in
`gim-client-sdk/docs/plan/20261023-0930-account-safety-features.md`.

## Alternatives

- Bottom navigation (Chats / Contacts / Settings, Telegram style) instead of
  the avatar entry: more discoverable for children, but a contacts list does
  not exist yet in the SDK; can follow later.
- Keep "hub" as the user-facing term: matches the code but means nothing to
  the target user.
