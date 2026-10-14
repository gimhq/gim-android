package im.g.apps.core.bridge.diff

import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.ChatListDiff
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.TimelineDiff
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.applyDiffs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VectorDiffTest {
    private fun apply(start: List<String>, vararg diffs: VectorDiff<String>): List<String> =
        start.toMutableList().apply { applyVectorDiffs(diffs.toList()) }

    @Test
    fun append_adds_values_at_the_end() {
        assertEquals(listOf("a", "b", "c"), apply(listOf("a"), VectorDiff.Append(listOf("b", "c"))))
    }

    @Test
    fun clear_removes_everything() {
        assertEquals(emptyList<String>(), apply(listOf("a", "b"), VectorDiff.Clear))
    }

    @Test
    fun push_front_and_push_back() {
        assertEquals(listOf("x", "a", "y"), apply(listOf("a"), VectorDiff.PushFront("x"), VectorDiff.PushBack("y")))
    }

    @Test
    fun pop_front_and_pop_back() {
        assertEquals(listOf("b"), apply(listOf("a", "b", "c"), VectorDiff.PopFront, VectorDiff.PopBack))
    }

    @Test
    fun pop_on_empty_list_throws() {
        assertThrows(IndexOutOfBoundsException::class.java) { apply(emptyList(), VectorDiff.PopFront) }
        assertThrows(IndexOutOfBoundsException::class.java) { apply(emptyList(), VectorDiff.PopBack) }
    }

    @Test
    fun insert_before_index_and_at_size_appends() {
        assertEquals(listOf("a", "x", "b"), apply(listOf("a", "b"), VectorDiff.Insert(1, "x")))
        assertEquals(listOf("a", "b", "x"), apply(listOf("a", "b"), VectorDiff.Insert(2, "x")))
        assertThrows(IndexOutOfBoundsException::class.java) { apply(listOf("a"), VectorDiff.Insert(2, "x")) }
    }

    @Test
    fun set_replaces_item() {
        assertEquals(listOf("a", "B"), apply(listOf("a", "b"), VectorDiff.Set(1, "B")))
        assertThrows(IndexOutOfBoundsException::class.java) { apply(listOf("a"), VectorDiff.Set(1, "x")) }
    }

    @Test
    fun remove_drops_item_at_index() {
        assertEquals(listOf("a", "c"), apply(listOf("a", "b", "c"), VectorDiff.Remove(1)))
        assertThrows(IndexOutOfBoundsException::class.java) { apply(listOf("a"), VectorDiff.Remove(1)) }
    }

    @Test
    fun truncate_keeps_prefix_and_ignores_longer_length() {
        assertEquals(listOf("a"), apply(listOf("a", "b", "c"), VectorDiff.Truncate(1)))
        assertEquals(listOf("a", "b"), apply(listOf("a", "b"), VectorDiff.Truncate(5)))
        assertEquals(emptyList<String>(), apply(listOf("a", "b"), VectorDiff.Truncate(0)))
        assertThrows(IndexOutOfBoundsException::class.java) { apply(listOf("a"), VectorDiff.Truncate(-1)) }
    }

    @Test
    fun reset_replaces_the_list() {
        assertEquals(listOf("x", "y"), apply(listOf("a"), VectorDiff.Reset(listOf("x", "y"))))
    }

    @Test
    fun batch_indices_refer_to_the_list_after_the_previous_diff() {
        val result = apply(
            listOf("a", "b", "c"),
            VectorDiff.Remove(0), // b c
            VectorDiff.Insert(2, "d"), // b c d
            VectorDiff.Set(0, "B"), // B c d
            VectorDiff.PushFront("z"), // z B c d
            VectorDiff.Remove(3), // z B c
            VectorDiff.Insert(1, "y"), // z y B c
        )
        assertEquals(listOf("z", "y", "B", "c"), result)
    }

    @Test
    fun chat_list_diffs_map_every_variant() {
        fun chat(id: String) = ChatSummary(id, ChatKind.DIRECT, id, null, null, 0, 0, 2)
        val list = mutableListOf<ChatSummary>()
        list.applyDiffs(
            listOf(
                ChatListDiff.Reset(listOf(chat("a"))),
                ChatListDiff.Append(listOf(chat("b"), chat("c"))), // a b c
                ChatListDiff.PushFront(chat("f")), // f a b c
                ChatListDiff.PushBack(chat("g")), // f a b c g
                ChatListDiff.PopFront, // a b c g
                ChatListDiff.PopBack, // a b c
                ChatListDiff.Insert(1, chat("i")), // a i b c
                ChatListDiff.Set(0, chat("s")), // s i b c
                ChatListDiff.Remove(2), // s i c
                ChatListDiff.Truncate(2), // s i
            ),
        )
        assertEquals(listOf("s", "i"), list.map { it.chatId })
        list.applyDiffs(listOf(ChatListDiff.Clear))
        assertEquals(emptyList<ChatSummary>(), list)
    }

    @Test
    fun timeline_diffs_map_every_variant() {
        fun item(day: Long) = TimelineItem.DateSeparator(day)
        val list = mutableListOf<TimelineItem>()
        list.applyDiffs(
            listOf(
                TimelineDiff.Reset(listOf(item(1))),
                TimelineDiff.Append(listOf(item(2), item(3))),
                TimelineDiff.PushFront(item(6)),
                TimelineDiff.PushBack(item(7)),
                TimelineDiff.PopFront,
                TimelineDiff.PopBack,
                TimelineDiff.Insert(1, item(9)),
                TimelineDiff.Set(0, item(10)),
                TimelineDiff.Remove(2),
                TimelineDiff.Truncate(2),
            ),
        )
        assertEquals(listOf(item(10), item(9)), list)
        list.applyDiffs(listOf(TimelineDiff.Clear))
        assertEquals(emptyList<TimelineItem>(), list)
    }
}
