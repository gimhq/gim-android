package im.g.apps.core.bridge.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApplyDiffsTest {
    private val base = listOf("a", "b", "c")

    @Test
    fun reset_replaces_all_items() {
        assertEquals(listOf("x", "y"), base.applyDiffs(listOf(ListDiff.Reset(listOf("x", "y")))))
    }

    @Test
    fun insert_at_start_middle_and_end() {
        assertEquals(listOf("x", "a", "b", "c"), base.applyDiffs(listOf(ListDiff.Insert(0, "x"))))
        assertEquals(listOf("a", "x", "b", "c"), base.applyDiffs(listOf(ListDiff.Insert(1, "x"))))
        assertEquals(listOf("a", "b", "c", "x"), base.applyDiffs(listOf(ListDiff.Insert(3, "x"))))
    }

    @Test
    fun set_replaces_item_at_index() {
        assertEquals(listOf("a", "x", "c"), base.applyDiffs(listOf(ListDiff.Set(1, "x"))))
    }

    @Test
    fun remove_drops_item_at_index() {
        assertEquals(listOf("b", "c"), base.applyDiffs(listOf(ListDiff.Remove(0))))
        assertEquals(listOf("a", "b"), base.applyDiffs(listOf(ListDiff.Remove(2))))
    }

    @Test
    fun push_back_and_push_front() {
        assertEquals(listOf("a", "b", "c", "x"), base.applyDiffs(listOf(ListDiff.PushBack("x"))))
        assertEquals(listOf("x", "a", "b", "c"), base.applyDiffs(listOf(ListDiff.PushFront("x"))))
    }

    @Test
    fun clear_empties_list() {
        assertEquals(emptyList<String>(), base.applyDiffs(listOf(ListDiff.Clear)))
    }

    @Test
    fun truncate_keeps_prefix_and_ignores_longer_length() {
        assertEquals(listOf("a"), base.applyDiffs(listOf(ListDiff.Truncate(1))))
        assertEquals(emptyList<String>(), base.applyDiffs(listOf(ListDiff.Truncate(0))))
        assertEquals(base, base.applyDiffs(listOf(ListDiff.Truncate(5))))
    }

    @Test
    fun empty_diff_list_returns_equal_copy() {
        val result = base.applyDiffs(emptyList())
        assertEquals(base, result)
    }

    @Test
    fun does_not_mutate_receiver() {
        val source = mutableListOf("a", "b")
        source.applyDiffs(listOf(ListDiff.Clear))
        assertEquals(listOf("a", "b"), source)
    }

    @Test
    fun applies_sequence_in_order() {
        val diffs = listOf(
            ListDiff.Clear,
            ListDiff.PushBack("b"),
            ListDiff.PushFront("a"),
            ListDiff.PushBack("d"),
            ListDiff.Insert(2, "c"),
            ListDiff.Set(0, "A"),
            ListDiff.Remove(1),
            ListDiff.PushBack("e"),
            ListDiff.Truncate(3),
        )
        assertEquals(listOf("A", "c", "d"), base.applyDiffs(diffs))
    }

    @Test
    fun reset_mid_sequence_discards_earlier_changes() {
        val diffs = listOf(ListDiff.PushBack("x"), ListDiff.Reset(listOf("r")), ListDiff.PushBack("s"))
        assertEquals(listOf("r", "s"), base.applyDiffs(diffs))
    }

    @Test
    fun out_of_range_indices_throw() {
        assertThrows(IndexOutOfBoundsException::class.java) { base.applyDiffs(listOf(ListDiff.Insert(4, "x"))) }
        assertThrows(IndexOutOfBoundsException::class.java) { base.applyDiffs(listOf(ListDiff.Set(3, "x"))) }
        assertThrows(IndexOutOfBoundsException::class.java) { base.applyDiffs(listOf(ListDiff.Remove(3))) }
        assertThrows(IndexOutOfBoundsException::class.java) { base.applyDiffs(listOf(ListDiff.Truncate(-1))) }
    }
}
