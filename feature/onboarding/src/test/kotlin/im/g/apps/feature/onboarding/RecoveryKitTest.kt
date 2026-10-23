package im.g.apps.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryKitTest {
    @Test
    fun format_writes_the_kit_lines() {
        val text = formatRecoveryKit(" ${USER_ID.uppercase()} ", SYMBOLS.lowercase(), LABELS)
        assertEquals(
            "Recovery kit\nAccount ID: $USER_ID\nRecovery key: $CODE\n\nKeep this safe.\n",
            text,
        )
    }

    @Test
    fun format_then_parse_round_trips() {
        for (code in listOf(CODE, SYMBOLS)) {
            assertEquals(FULL, parseRecoveryKit(formatRecoveryKit(USER_ID, code, LABELS)))
        }
    }

    @Test
    fun parse_ignores_the_labels() {
        val spanish = RecoveryKitLabels("Kit de recuperación", "ID de cuenta", "Clave de recuperación", "Guárdalo.")
        val chinese = RecoveryKitLabels("恢复工具包", "账户 ID", "恢复密钥", "请妥善保管。")
        for (labels in listOf(spanish, chinese)) {
            assertEquals(FULL, parseRecoveryKit(formatRecoveryKit(USER_ID, CODE, labels)))
        }
        val fullWidth = "账户 ID：$USER_ID\n恢复密钥：$CODE\n"
        assertEquals(FULL, parseRecoveryKit(fullWidth))
    }

    @Test
    fun parse_accepts_lowercase_and_spaced_keys() {
        assertEquals(FULL, parseRecoveryKit("Account ID: $USER_ID\nRecovery key: ${CODE.lowercase()}\n"))
        assertEquals(FULL, parseRecoveryKit("Account ID: $USER_ID\nRecovery key: ${CODE.replace('-', ' ')}\n"))
        assertEquals(FULL, parseRecoveryKit("$USER_ID $SYMBOLS"))
    }

    @Test
    fun parse_finds_the_kit_in_surrounding_text() {
        val kit = formatRecoveryKit(USER_ID, CODE, LABELS).replace("\n", "\r\n")
        val text = "Hi,\r\n\r\nhere is my kit:\r\n\r\n$kit" +
            "\r\nmy key is ${CODE.replace('-', ' ')} again.\r\n"
        assertEquals(FULL, parseRecoveryKit(text))
        assertEquals(FULL, parseRecoveryKit("Account ID: ${USER_ID.uppercase()}\r\nRecovery key: $CODE\r\n"))
    }

    @Test
    fun parse_returns_whatever_part_is_there() {
        assertEquals(ParsedRecoveryKit(null, SYMBOLS), parseRecoveryKit("my key is $CODE"))
        assertEquals(ParsedRecoveryKit(USER_ID, null), parseRecoveryKit("Account ID: $USER_ID"))
        assertEquals(ParsedRecoveryKit(null, null), parseRecoveryKit(""))
        assertEquals(ParsedRecoveryKit(null, null), parseRecoveryKit("  \n\t "))
        assertEquals(ParsedRecoveryKit(null, null), parseRecoveryKit("hello, this is not a kit at all!"))
    }

    @Test
    fun parse_rejects_bad_tokens() {
        val badPadding = CODE.dropLast(1) + "S"
        assertEquals(ParsedRecoveryKit(USER_ID, null), parseRecoveryKit("$USER_ID\n$badPadding"))
        // A user ID glued to other letters is not a whole token.
        assertEquals(ParsedRecoveryKit(null, null), parseRecoveryKit("x$USER_ID"))
    }

    @Test
    fun parse_never_takes_the_user_id_as_the_key() {
        // A valid user ID followed by more symbols must not be read as (part of) a key.
        assertEquals(ParsedRecoveryKit(USER_ID, null), parseRecoveryKit("$USER_ID-0000"))
        assertEquals(ParsedRecoveryKit(USER_ID, SYMBOLS), parseRecoveryKit("$USER_ID-$CODE"))
    }

    @Test
    fun key_ending_is_the_last_four_symbols() {
        assertEquals("MV7R", recoveryKeyEnding(CODE))
        assertEquals("MV7R", recoveryKeyEnding(SYMBOLS.lowercase()))
    }

    @Test
    fun key_ending_match_ignores_case_and_separators() {
        val code = SYMBOLS.dropLast(4) + "ABCD"
        assertTrue(matchesRecoveryKeyEnding(code, "ABCD"))
        assertTrue(matchesRecoveryKeyEnding(code, "abcd"))
        assertTrue(matchesRecoveryKeyEnding(code, "ab-cd"))
        assertTrue(matchesRecoveryKeyEnding(code, " a b c d "))
        assertFalse(matchesRecoveryKeyEnding(code, "ABCE"))
        assertFalse(matchesRecoveryKeyEnding(code, "BCD"))
        assertFalse(matchesRecoveryKeyEnding(code, "ABCDE"))
        assertFalse(matchesRecoveryKeyEnding(code, ""))
    }

    private companion object {
        val USER_ID = "u" + "abcdefghijklmnopqrstuvwxyz234567".repeat(2).take(54) + "q"
        const val CODE = "7BQ2-K9MX-4TVA-0HZR-PC63-W8NE-1SJD-5GFY-QK2M-V7R"
        val SYMBOLS = CODE.replace("-", "")
        val FULL = ParsedRecoveryKit(USER_ID, SYMBOLS)
        val LABELS = RecoveryKitLabels("Recovery kit", "Account ID", "Recovery key", "Keep this safe.")
    }
}
