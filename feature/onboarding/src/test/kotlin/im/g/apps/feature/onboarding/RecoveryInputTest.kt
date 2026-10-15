package im.g.apps.feature.onboarding

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryInputTest {
    @Test
    fun format_groups_symbols_in_blocks_of_four() {
        assertEquals("", formatRecoveryCode(""))
        assertEquals("7BQ", formatRecoveryCode("7BQ"))
        assertEquals("7BQ2", formatRecoveryCode("7BQ2"))
        assertEquals("7BQ2-K", formatRecoveryCode("7BQ2K"))
        assertEquals(CODE, formatRecoveryCode(normalizeRecoveryCode(CODE.lowercase())))
    }

    @Test
    fun normalize_drops_separators_and_maps_ambiguous_letters() {
        assertEquals("7BQ2K9MX10", normalizeRecoveryCode(" 7bq2-k9mx\tlo\n"))
        assertEquals("1", normalizeRecoveryCode("i"))
    }

    @Test
    fun transformation_shows_groups_and_keeps_the_cursor_on_the_same_symbol() {
        val symbols = "7BQ2K9MX4T"
        val transformed = RecoveryCodeTransformation.filter(AnnotatedString(symbols))
        assertEquals("7BQ2-K9MX-4T", transformed.text.text)
        val mapping = transformed.offsetMapping
        // Every symbol keeps its position relative to the cursor, and the end maps to the end.
        for (offset in 0..symbols.length) {
            val shown = mapping.originalToTransformed(offset)
            assertEquals(offset, mapping.transformedToOriginal(shown))
        }
        assertEquals(12, mapping.originalToTransformed(10))
        assertEquals(4, mapping.originalToTransformed(4))
        assertEquals(6, mapping.originalToTransformed(5))
        // The cursor right after a separator belongs before the next symbol.
        assertEquals(4, mapping.transformedToOriginal(5))
    }

    private companion object {
        const val CODE = "7BQ2-K9MX-4TVA-0HZR-PC63-W8NE-1SJD-5GFY-QK2M-V7R"
    }
}
