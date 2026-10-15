package im.g.apps.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import im.g.apps.AppContainer
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
import im.g.apps.feature.onboarding.OnboardingRoute
import im.g.apps.feature.onboarding.OnboardingViewModel
import im.g.apps.feature.onboarding.onboardingViewModelFactory
import im.g.apps.openFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                factory = onboardingViewModelFactory(container.manager, container.defaultHubUrl),
            )
            val state by vm.state.collectAsState()
            // Display name -> Welcome; on the recovery code back is swallowed until it is acknowledged.
            BackHandler(enabled = state.handlesBack) { vm.back() }
            OnboardingRoute(
                viewModel = vm,
                onFinished = {
                    container.sync.onSignedIn()
                    navController.navigate(Routes.CHAT_LIST) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                },
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
            ChatListRoute(
                viewModel = vm,
                onOpenChat = { navController.navigate(Routes.chat(it)) },
                onNewChat = { navController.navigate(Routes.NEW_CHAT) },
                onNewGroup = { navController.navigate(Routes.NEW_GROUP) },
                onSignOut = {
                    // Main: sign-out resumes from background work and NavController is main-thread only.
                    navScope.launch(Dispatchers.Main) {
                        container.signOut()
                        navController.navigate(Routes.ONBOARDING) { popUpTo(navController.graph.id) { inclusive = true } }
                    }
                },
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
                onLeftChat = { navController.popBackStack(Routes.CHAT_LIST, inclusive = false) },
            )
        }
    }
}

/** Opens [chatId] in place of the new chat / new group screens, keeping the chat list below it. */
private fun NavHostController.openChatReplacing(chatId: String) =
    navigate(Routes.chat(chatId)) { popUpTo(Routes.CHAT_LIST) }
