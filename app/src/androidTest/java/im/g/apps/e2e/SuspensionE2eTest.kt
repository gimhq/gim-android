package im.g.apps.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.SendFailure
import im.g.apps.testing.HubAdmin
import org.junit.Test
import org.junit.runner.RunWith
import im.g.apps.core.designsystem.R as DesignR

/**
 * A hub admin suspends the signed-in app user (POST /admin/api/users/{user_id}/suspend), then lifts the suspension;
 * see [RefusedUserE2eBase] for the flow and the instrumentation arguments.
 */
@RunWith(AndroidJUnit4::class)
class SuspensionE2eTest : RefusedUserE2eBase() {
    override val logTag = "SuspensionE2eTest"

    override val refusal = object : Refusal {
        override val cause = SendFailure.USER_SUSPENDED
        override val message = DesignR.string.gim_error_user_suspended

        override fun apply(admin: HubAdmin, userId: String) = admin.suspendUser(userId)

        override fun lift(admin: HubAdmin, userId: String) {
            admin.unsuspendUser(userId)
        }
    }

    @Test
    fun suspendedUserKeepsChatsAndSendsAgainAfterUnsuspend() = runRefusalFlow()
}
