package im.g.apps.core.bridge.uniffi

import im.g.apps.core.bridge.ParsedRecoveryKit
import im.g.apps.core.bridge.RecoveryKitCodec
import im.g.apps.core.bridge.RecoveryKitLabels

/** [RecoveryKitCodec] calling gim-core's top-level functions; loads `libgim_core.so` on the first call. */
object UniffiRecoveryKitCodec : RecoveryKitCodec {
    override fun format(userId: String, recoveryCode: String, labels: RecoveryKitLabels): String =
        im.g.gimcore.formatRecoveryKit(userId, recoveryCode, labels.toCore())

    override fun parse(text: String): ParsedRecoveryKit = im.g.gimcore.parseRecoveryKit(text).toMirror()

    override fun keyEnding(recoveryCode: String): String = im.g.gimcore.recoveryKeyEnding(recoveryCode)

    override fun matchesKeyEnding(recoveryCode: String, typed: String): Boolean =
        im.g.gimcore.matchesRecoveryKeyEnding(recoveryCode, typed)
}
