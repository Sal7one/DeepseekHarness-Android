package io.github.jackwener.dsh

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.designsystem.DshTheme
import io.github.jackwener.dsh.feature.automation.AutomationScreen
import io.github.jackwener.dsh.feature.chat.ChatsScreen
import io.github.jackwener.dsh.feature.chat.ConversationScreen
import io.github.jackwener.dsh.feature.onboarding.OnboardingScreen
import io.github.jackwener.dsh.feature.settings.DoctorScreen
import io.github.jackwener.dsh.feature.settings.KeyScreen
import io.github.jackwener.dsh.feature.settings.MeScreen
import io.github.jackwener.dsh.feature.settings.NotificationAppsScreen
import io.github.jackwener.dsh.feature.settings.PermissionsScreen
import io.github.jackwener.dsh.feature.settings.UsageScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    /** null = still loading. */
    val onboarded = settings.settings.map { it.onboarded }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var pendingSession by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingSession = intent.getStringExtra(EXTRA_SESSION)
        setContent {
            val vm: MainViewModel = hiltViewModel()
            val onboarded by vm.onboarded.collectAsStateWithLifecycle()
            splash.setKeepOnScreenCondition { onboarded == null }
            DshTheme { onboarded?.let { AppNav(it) } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingSession = intent.getStringExtra(EXTRA_SESSION)
    }

    @Composable
    private fun AppNav(onboarded: Boolean) {
        val nav = rememberNavController()
        LaunchedEffect(pendingSession) { pendingSession?.let { nav.navigate("chat/$it"); pendingSession = null } }
        NavHost(nav, startDestination = if (onboarded) "home" else "onboarding") {
            composable("onboarding") {
                OnboardingScreen(onFinished = { prompt ->
                    nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                    prompt?.let { nav.navigate("chat/new?prompt=" + URLEncoder.encode(it, "UTF-8")) }
                })
            }
            composable("home") { Home(nav) }
            composable("chat/{sessionId}?prompt={prompt}", arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("prompt") { type = NavType.StringType; nullable = true; defaultValue = null },
            )) { entry ->
                val prompt = entry.arguments?.getString("prompt")?.let { URLDecoder.decode(it, "UTF-8") }
                ConversationScreen(onBack = { nav.popBackStack() }, autoSend = prompt)
            }
            composable("key") { KeyScreen(onBack = { nav.popBackStack() }) }
            composable("permissions") { PermissionsScreen(onBack = { nav.popBackStack() }) }
            composable("notif_apps") { NotificationAppsScreen(onBack = { nav.popBackStack() }) }
            composable("usage") { UsageScreen(onBack = { nav.popBackStack() }) }
            composable("doctor") { DoctorScreen(onBack = { nav.popBackStack() }) }
        }
    }

    @Composable
    private fun Home(nav: NavHostController) {
        var tab by rememberSaveable { mutableStateOf(0) }
        Scaffold(bottomBar = {
            NavigationBar {
                listOf(Triple(R.string.tab_chats, Icons.Default.Home, 0), Triple(R.string.tab_automation, Icons.Default.PlayArrow, 1),
                    Triple(R.string.tab_me, Icons.Default.Person, 2)).forEach { (label, icon, i) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(stringResource(label)) })
                }
            }
        }) { pad ->
            val m = Modifier.padding(bottom = pad.calculateBottomPadding())
            androidx.compose.foundation.layout.Box(m) {
                when (tab) {
                    0 -> ChatsScreen(onOpen = { nav.navigate("chat/$it") })
                    1 -> AutomationScreen(onCreateViaChat = { nav.navigate("chat/new?prompt=" + URLEncoder.encode(it, "UTF-8")) })
                    else -> MeScreen(onNavigate = { nav.navigate(it) })
                }
            }
        }
    }

    companion object { const val EXTRA_SESSION = "session" }
}
