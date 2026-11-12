package im.g.apps.feature.onboarding

import im.g.apps.core.bridge.fake.FakeRecoveryKitCodec

/** [OnboardingCallbacks] that do nothing unless overridden, for stateless screen tests. */
internal fun testCallbacks(
    onFinishIntro: () -> Unit = {},
    onUseAnotherServer: () -> Unit = {},
    onCopyRecoveryKit: (String) -> Unit = {},
    onSaveToPasswordManager: suspend (String, String) -> PasswordSaveResult = { _, _ -> PasswordSaveResult.SAVED },
    onShareRecoveryKit: (String) -> Unit = {},
    onContinueFromKit: () -> Unit = {},
    onKitCheckChange: (String) -> Unit = {},
    onRetryLink: () -> Unit = {},
) = OnboardingCallbacks(
    onFinishIntro = onFinishIntro,
    onServerUrlChange = {},
    onUseAnotherServer = onUseAnotherServer,
    onCreateAccount = {},
    onBack = {},
    onDisplayNameChange = {},
    onSubmitDisplayName = {},
    formatRecoveryKit = FakeRecoveryKitCodec::format,
    onCopyRecoveryKit = onCopyRecoveryKit,
    onSaveToPasswordManager = onSaveToPasswordManager,
    onShareRecoveryKit = onShareRecoveryKit,
    onContinueFromKit = onContinueFromKit,
    onKitCheckChange = onKitCheckChange,
    onConfirmKitCheck = {},
    onLinkDevice = {},
    onCancelLink = {},
    onRetryLink = onRetryLink,
    onCopyLinkCode = {},
    onConfirmLink = {},
    onRejectLink = {},
    onStartRestore = {},
    onRestoreKitTextChange = {},
    onRestoreUserIdChange = {},
    onRestoreCodeChange = {},
    onRevokeOtherDevicesChange = {},
    onRestore = {},
)
