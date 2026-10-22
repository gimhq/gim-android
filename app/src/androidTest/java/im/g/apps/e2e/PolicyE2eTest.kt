package im.g.apps.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.SendFailure
import im.g.apps.testing.HubAdmin
import org.junit.Test
import org.junit.runner.RunWith
import im.g.apps.core.designsystem.R as DesignR

/**
 * The hub's user policy stops admitting the signed-in app user: listed (POST /admin/api/users/policy/entries) with
 * `GIM_HUB_USER_POLICY` set to `deny` (PUT /admin/api/settings/GIM_HUB_USER_POLICY); then the stored mode is deleted
 * (back to the default `open`) and the entry removed, also in teardown so later classes find an open hub. See
 * [RefusedUserE2eBase] for the flow and the instrumentation arguments.
 */
@RunWith(AndroidJUnit4::class)
class PolicyE2eTest : RefusedUserE2eBase() {
    override val logTag = "PolicyE2eTest"

    override val refusal = object : Refusal {
        override val cause = SendFailure.USER_NOT_ADMITTED
        override val message = DesignR.string.gim_error_user_not_admitted

        override fun apply(admin: HubAdmin, userId: String) {
            admin.addPolicyEntry(userId)
            admin.setUserPolicy("deny")
        }

        override fun lift(admin: HubAdmin, userId: String) {
            admin.resetUserPolicy()
            admin.removePolicyEntry(userId)
        }
    }

    @Test
    fun deniedUserKeepsChatsAndSendsAgainWhenAdmitted() = runRefusalFlow()
}
