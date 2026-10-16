package im.g.apps.core.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingLeavesTest {
    private val self = Session("uself", "d-1", "", "Sofia Costa")

    private fun left(name: String, atMs: Long) = TimelineItem.MembershipChange(name, MembershipChangeKind.LEFT, atMs)

    @Test
    fun own_left_matches_the_display_name_or_the_user_id_after_the_request() {
        assertTrue(listOf(left("Sofia Costa", 2_000)).hasOwnLeft(self, sinceMs = 1_000))
        assertTrue(listOf(left("uself", 1_000)).hasOwnLeft(self, sinceMs = 1_000))
    }

    @Test
    fun earlier_changes_others_and_other_kinds_do_not_count() {
        assertFalse(listOf(left("Sofia Costa", 500)).hasOwnLeft(self, sinceMs = 1_000))
        assertFalse(listOf(left("Lukas Weber", 2_000)).hasOwnLeft(self, sinceMs = 1_000))
        val added = TimelineItem.MembershipChange("Sofia Costa", MembershipChangeKind.ADDED, 2_000)
        assertFalse(listOf(added, TimelineItem.DateSeparator(0)).hasOwnLeft(self, sinceMs = 1_000))
    }

    @Test
    fun in_memory_store_adds_removes_and_clears() {
        val store = InMemoryPendingLeaveStore()
        store.add("chat-1", 10)
        store.add("chat-2", 20)
        store.remove("chat-1")
        assertEquals(mapOf("chat-2" to 20L), store.pending.value)
        store.clear()
        assertEquals(emptyMap<String, Long>(), store.pending.value)
    }

    @Test
    fun short_user_ids_replace_bare_ids_but_not_names() {
        val id = "u" + "a".repeat(54) + "q"
        assertEquals("aaaaaaaa", shortUserId(id))
        assertEquals("aaaaaaaa", displayNameOrShortId(id))
        assertEquals("Sofia Costa", displayNameOrShortId("Sofia Costa"))
        assertEquals("aaaaaaaa", UserProfile(id, "", "").displayLabel)
        assertEquals("aaaaaaaa", UserProfile(id, "", id).displayLabel)
        assertEquals("Sofia Costa", UserProfile(id, "", "Sofia Costa").displayLabel)
    }
}
