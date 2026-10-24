package im.g.apps.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import im.g.apps.AppContainer
import im.g.apps.BuildConfig
import im.g.apps.R
import im.g.apps.feature.chat.ChatRoute
import im.g.apps.feature.chat.ChatViewModel
import im.g.apps.feature.chat.composer.AndroidUriCopier
import im.g.apps.feature.chat.composer.ComposerRoute
import im.g.apps.feature.chat.composer.ComposerViewModel
import im.g.apps.feature.chatlist.ChatListRoute
import im.g.apps.feature.chatlist.ChatListViewModel
import im.g.apps.feature.chatlist.chatListViewModelFactory
import im.g.apps.feature.contacts.GroupInfoRoute
import im.g.apps.feature.contacts.NewChatRoute
import im.g.apps.feature.contacts.NewGroupRoute
import im.g.apps.feature.contacts.NewGroupStep
import im.g.apps.feature.contacts.NewGroupViewModel
import im.g.apps.feature.contacts.groupInfoViewModelFactory
import im.g.apps.feature.contacts.newChatViewModelFactory
import im.g.apps.feature.contacts.newGroupViewModelFactory
import im.g.apps.feature.devices.DevicesRoute
import im.g.apps.feature.devices.devicesViewModelFactory
import im.g.apps.feature.hubs.HubsRoute
import im.g.apps.feature.hubs.hubsViewModelFactory
import im.g.apps.feature.linking.LinkDeviceRoute
import im.g.apps.feature.linking.QrScanner
import im.g.apps.feature.linking.linkDeviceViewModelFactory
import im.g.apps.feature.onboarding.OnboardingRoute
import im.g.apps.feature.onboarding.OnboardingViewModel
import im.g.apps.feature.onboarding.onboardingViewModelFactory
import im.g.apps.feature.settings.AppearanceRoute
import im.g.apps.feature.settings.BlockedPeopleRoute
import im.g.apps.feature.settings.HelpScreen
import im.g.apps.feature.settings.LanguageRoute
import im.g.apps.feature.settings.LicencesScreen
import im.g.apps.feature.settings.NotificationChannelItem
import im.g.apps.feature.settings.NotificationsRoute
import im.g.apps.feature.settings.ProfileRoute
import im.g.apps.feature.settings.RecoveryKeyScreen
import im.g.apps.feature.settings.SettingsActions
import im.g.apps.feature.settings.SettingsRoute
import im.g.apps.feature.settings.SettingsViewModel
import im.g.apps.feature.settings.appearanceViewModelFactory
import im.g.apps.feature.settings.blockedPeopleViewModelFactory
import im.g.apps.feature.settings.languageViewModelFactory
import im.g.apps.feature.settings.profileViewModelFactory
import im.g.apps.feature.settings.settingsViewModelFactory
import im.g.apps.feature.settings.supportedLanguageTags
import im.g.apps.deleteRecoveryKitFiles
import im.g.apps.openFile
import im.g.apps.push.PushChannel
import im.g.apps.push.ensurePushChannels
import im.g.apps.shareRecoveryKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App navigation graph; ViewModels are scoped to their back stack entries. */
@Composable
fun GimNavHost(
    container: AppContainer,
    startDestination: String,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val client by container.manager.client.collectAsState()
    // Outlives the chat list entry, which leaves composition as soon as sign-out drops the client.
    val navScope = rememberCoroutineScope()

    NavHost(navController, startDestination, modifier) {
        composable(Routes.ONBOARDING) {
            val vm: OnboardingViewModel = viewModel(
                // A dropped link must also wipe the keystore and data dir, like sign-out.
                factory = onboardingViewModelFactory(container.manager, container.defaultHubUrl, container::signOut),
            )
            val state by vm.state.collectAsState()
            // Display name -> Welcome, link QR code -> cancel; on the recovery code and the link SAS back is swallowed.
            BackHandler(enabled = state.handlesBack) { vm.back() }
            val context = LocalContext.current
            OnboardingRoute(
                viewModel = vm,
                onFinished = {
                    deleteRecoveryKitFiles(context)
                    container.sync.onSignedIn()
                    navController.navigate(Routes.CHAT_LIST) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                },
                onShareRecoveryKit = { shareRecoveryKit(context, it) },
            )
        }
        composable(Routes.CHAT_LIST) {
            val current = client ?: return@composable
            val vm: ChatListViewModel = viewModel(
                factory = chatListViewModelFactory(
                    client = current,
                    thumbnailPath = { chatId, messageId -> container.mediaPaths.destFor(chatId, messageId, thumbnail = true) },
                ),
            )
            // Re-read whenever the chat list re-enters composition, e.g. after a name change in Settings.
            // (Not produceState: its lint check misses the assignment after withContext.)
            var ownName by remember(current) { mutableStateOf("") }
            LaunchedEffect(current) {
                ownName = withContext(Dispatchers.IO) { current.session()?.displayName.orEmpty() }
            }
            ChatListRoute(
                viewModel = vm,
                ownName = ownName,
                onOpenChat = { navController.navigate(Routes.chat(it)) },
                onNewChat = { navController.navigate(Routes.NEW_CHAT) },
                onNewGroup = { navController.navigate(Routes.NEW_GROUP) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            val current = client ?: return@composable
            val themeMode by container.themeModeStore.mode.collectAsState()
            val vm: SettingsViewModel = viewModel(factory = settingsViewModelFactory(current))
            SettingsRoute(
                viewModel = vm,
                actions = SettingsActions(
                    onBack = { navController.popBackStack() },
                    onOpenProfile = { navController.navigate(Routes.SETTINGS_PROFILE) },
                    onRecoveryKey = { navController.navigate(Routes.SETTINGS_RECOVERY_KEY) },
                    onDevices = { navController.navigate(Routes.DEVICES) },
                    onLinkDevice = { navController.navigate(Routes.LINK_DEVICE) },
                    onNotifications = { navController.navigate(Routes.SETTINGS_NOTIFICATIONS) },
                    onReadReceiptsChange = vm::setReadReceipts,
                    onTypingIndicatorChange = vm::setTypingIndicator,
                    onBlockedPeople = { navController.navigate(Routes.SETTINGS_BLOCKED) },
                    onServers = { navController.navigate(Routes.HUBS) },
                    onAppearance = { navController.navigate(Routes.SETTINGS_APPEARANCE) },
                    onLanguage = { navController.navigate(Routes.SETTINGS_LANGUAGE) },
                    onHelp = { navController.navigate(Routes.SETTINGS_HELP) },
                    onSignOut = {
                        // Main: sign-out resumes from background work and NavController is main-thread only.
                        navScope.launch(Dispatchers.Main) {
                            container.signOut()
                            navController.navigate(Routes.ONBOARDING) { popUpTo(navController.graph.id) { inclusive = true } }
                        }
                    },
                ),
                themeMode = themeMode,
                // Re-read on every visit: a new language recreates the activity anyway.
                languageTag = container.appLocales.current(),
            )
        }
        composable(Routes.SETTINGS_RECOVERY_KEY) {
            RecoveryKeyScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_NOTIFICATIONS) {
            val context = LocalContext.current
            // Channels are otherwise created by the first push; a channel's system settings need it to exist.
            val channels = remember {
                ensurePushChannels(context)
                PushChannel.entries.map { NotificationChannelItem(it.id, context.getString(it.label)) }
            }
            NotificationsRoute(channels = channels, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_APPEARANCE) {
            AppearanceRoute(
                viewModel = viewModel(factory = appearanceViewModelFactory(container.themeModeStore)),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_LANGUAGE) {
            val context = LocalContext.current
            LanguageRoute(
                viewModel = viewModel(factory = languageViewModelFactory(container.appLocales, supportedLanguageTags(context))),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_PROFILE) {
            val current = client ?: return@composable
            val context = LocalContext.current
            ProfileRoute(
                viewModel = viewModel(factory = profileViewModelFactory(current, context.cacheDir)),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_BLOCKED) {
            val current = client ?: return@composable
            BlockedPeopleRoute(
                viewModel = viewModel(factory = blockedPeopleViewModelFactory(current)),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_HELP) {
            HelpScreen(
                appVersion = BuildConfig.VERSION_NAME,
                sdkVersion = BuildConfig.GIM_CORE_VERSION,
                onBack = { navController.popBackStack() },
                onLicences = { navController.navigate(Routes.SETTINGS_LICENCES) },
            )
        }
        composable(Routes.SETTINGS_LICENCES) {
            LicencesScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.LINK_DEVICE) {
            val current = client ?: return@composable
            LinkDeviceRoute(
                viewModel = viewModel(factory = linkDeviceViewModelFactory(current)),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CHAT) { entry ->
            val current = client ?: return@composable
            val chatId = entry.arguments?.getString(Routes.CHAT_ID) ?: return@composable
            val context = LocalContext.current
            val chatVm: ChatViewModel = viewModel(factory = ChatViewModel.factory(current, chatId, container.mediaPathProvider))
            val composerVm: ComposerViewModel =
                viewModel(factory = ComposerViewModel.factory(current, chatId, AndroidUriCopier(context)))
            val snackbar = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            ChatRoute(
                viewModel = chatVm,
                composer = { ComposerRoute(composerVm, snackbar) },
                onMessageLongPress = composerVm::onMessageLongPress,
                snackbarHostState = snackbar,
                onBack = { navController.popBackStack() },
                onOpenGroupInfo = { navController.navigate(Routes.groupInfo(chatId)) },
                onOpenFile = { path, mime ->
                    if (!openFile(context, path, mime)) {
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.open_file_no_app)) }
                    }
                },
            )
        }
        composable(Routes.NEW_CHAT) {
            val current = client ?: return@composable
            NewChatRoute(
                viewModel = viewModel(factory = newChatViewModelFactory(current)),
                onOpenChat = { navController.openChatReplacing(it) },
                onBack = { navController.popBackStack() },
                scanner = { onQrCode, onUnavailable, modifier -> QrScanner(onQrCode, onUnavailable, modifier) },
            )
        }
        composable(Routes.NEW_GROUP) {
            val current = client ?: return@composable
            val vm: NewGroupViewModel = viewModel(factory = newGroupViewModelFactory(current))
            val state by vm.state.collectAsState()
            // Same action as the toolbar back: from the name step return to member selection.
            BackHandler(enabled = state.step == NewGroupStep.NAME) { vm.back() }
            NewGroupRoute(
                viewModel = vm,
                onOpenChat = { navController.openChatReplacing(it) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.GROUP_INFO) { entry ->
            val current = client ?: return@composable
            val chatId = entry.arguments?.getString(Routes.CHAT_ID) ?: return@composable
            GroupInfoRoute(
                viewModel = viewModel(factory = groupInfoViewModelFactory(current, chatId)),
                onBack = { navController.popBackStack() },
                // Back to the chat, which shows "Leaving…" until the own `Left` change arrives.
                onLeftChat = { navController.popBackStack() },
            )
        }
        composable(Routes.DEVICES) {
            val current = client ?: return@composable
            DevicesRoute(
                viewModel = viewModel(factory = devicesViewModelFactory(current)),
                onBack = { navController.popBackStack() },
                // This device was revoked elsewhere: wipe it like a sign-out and show onboarding.
                onSignedOut = {
                    navScope.launch(Dispatchers.Main) {
                        container.signOut()
                        navController.navigate(Routes.ONBOARDING) { popUpTo(navController.graph.id) { inclusive = true } }
                    }
                },
            )
        }
        composable(Routes.HUBS) {
            val current = client ?: return@composable
            HubsRoute(
                viewModel = viewModel(factory = hubsViewModelFactory(current)),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** Opens [chatId] in place of the new chat / new group screens, keeping the chat list below it. */
private fun NavHostController.openChatReplacing(chatId: String) =
    navigate(Routes.chat(chatId)) { popUpTo(Routes.CHAT_LIST) }
