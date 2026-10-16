package im.g.apps.push

import android.content.Context

/** Debug builds: the gim-push-mock client, off until a mock URL is configured (see [MockPushProvider]). */
fun createPushProvider(context: Context): PushProvider? = MockPushProvider(context)
