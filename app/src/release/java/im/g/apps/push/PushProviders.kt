package im.g.apps.push

import android.content.Context

/** Release builds have no push platform in M2 (no FCM, UnifiedPush or Play services): no push. */
fun createPushProvider(context: Context): PushProvider? = null
