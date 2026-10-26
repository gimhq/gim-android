# Glossary

The words the app shows to people, and the older terms they replace. Use the
user-facing word in every piece of text a user reads, the same way on every
screen.

| Old term (code, logs, SDK) | User-facing word | What it means |
|---|---|---|
| Hub | Server | The computer that passes your messages on. It can't read them. |
| Handle | Username | A name friends can type to find you. |
| User ID | Account ID | A long code that is your account's real address. |
| Recovery code | Recovery key | A spare key that brings your account back on a new phone. |
| Revoke device | Remove device | Sign a phone or tablet out of your account. |
| Verified hub | Connected | The app reached this server and it answered. |
| Unverified hub | Not checked | The app has not reached this server yet. |
| Home hub / Primary hub | Main server | The server your account lives on. |
| Keep server | Keep server | Backs up your privacy settings and receives the reports you send. |

Plurals follow the same mapping (hubs → servers, handles → usernames).

## Writing rules

Write for a 12-year-old. Signal and Telegram are the benchmark.

- Short sentences, one idea each. Plain everyday words; no protocol jargon
  ("federation", "session", "identity key", "payload", "endpoint").
- Say what to do next when there is a next step.
- Errors say what happened, then what to try: "Couldn't send. Check your
  internet and try again." Never blame the user. No raw error codes.
- Buttons and menu items are short verbs: "Remove", "Try again", "Copy".
- Titles use sentence case: "Linked devices", not "Linked Devices".
- `contentDescription` says what the control does: "Send message", "Go back".
- Keep every format placeholder (`%1$s`, `%2$d`, ...) with the same index,
  type and meaning, and keep plurals quantities.
- All user-visible text lives in string resources. Lint reports
  `HardcodedText` as an error.
- Every translated locale must have every translatable string and every
  plural quantity of its language. Lint reports `MissingTranslation` and
  `MissingQuantity` as errors. Brand names, URLs, sample values and bare
  formats are `translatable="false"`; do not translate them.

## Translations

Use these words in every translation, the same way on every screen. They
are a first draft: native-speaker review is still pending.

| English | Simplified Chinese (zh-CN) | Traditional Chinese (zh-TW) | Spanish (es) |
|---|---|---|---|
| Server | 服务器 | 伺服器 | servidor |
| Username | 用户名 | 使用者名稱 | nombre de usuario |
| Account ID | 账号 ID | 帳號 ID | ID de cuenta |
| Recovery key | 恢复密钥 | 復原金鑰 | clave de recuperación |
| Remove device | 移除设备 | 移除裝置 | Quitar dispositivo |
| Connected | 已连接 | 已連線 | Conectado |
| Not checked | 未检查 | 尚未檢查 | Sin comprobar |
| Main server | 主服务器 | 主要伺服器 | servidor principal |
| Keep server | 保管服务器 | 保管伺服器 | servidor de copia |
| Recovery kit | 恢复包 | 復原包 | kit de recuperación |
| Linked devices | 已关联的设备 | 已連結的裝置 | Dispositivos vinculados |
| Chat | 聊天 | 聊天 | chat |
| Message | 消息 | 訊息 | mensaje |
| Settings | 设置 | 設定 | Ajustes |
| Sign out | 退出登录 | 登出 | Cerrar sesión |

Tone, matching Signal and Telegram in each language:

- zh-CN: plain and friendly. Address the reader as 你, not 您.
- zh-TW: plain and friendly, with Taiwan vocabulary (伺服器, 帳號, 訊息,
  設定, 登出), not a character conversion of zh-CN. Address the reader as 你,
  not 您.
- es: informal "tú" form, neutral words that read well in Latin America and
  Spain.

## Code keeps the old terms

Only the text a user reads changes. Code, identifiers, string resource keys,
logs, test tags and the gim-client-sdk keep the old terms (hub, handle, user
ID, ...). Support and developers can use the table above to map between what
a user sees and what the code and logs say.
