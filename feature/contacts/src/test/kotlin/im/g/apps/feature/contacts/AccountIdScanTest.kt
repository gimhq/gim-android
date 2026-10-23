package im.g.apps.feature.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountIdScanTest {

    @Test
    fun plainId_isFound() {
        assertEquals(ID, findAccountId(ID))
    }

    @Test
    fun uppercaseId_isLowercased() {
        assertEquals(ID, findAccountId(ID.uppercase()))
    }

    @Test
    fun idInsideUrl_isFound() {
        assertEquals(ID, findAccountId("https://example.com/add?id=$ID"))
    }

    @Test
    fun idInsideSentence_isFound() {
        assertEquals(ID, findAccountId("Hi! Add me: $ID, see you."))
    }

    @Test
    fun malformedIds_areRejected() {
        assertNull(findAccountId(ID.dropLast(1) + "b"))
        assertNull(findAccountId(ID.dropLast(1)))
        assertNull(findAccountId(ID + "a"))
        assertNull(findAccountId("u1" + ID.drop(2)))
        assertNull(findAccountId("hello world"))
        assertNull(findAccountId(""))
    }

    @Test
    fun twoIds_returnsTheFirst() {
        assertEquals(ID, findAccountId("$ID $OTHER_ID"))
    }

    private companion object {
        val ID = "u" + "a".repeat(54) + "q"
        val OTHER_ID = "u" + "b".repeat(54) + "y"
    }
}
