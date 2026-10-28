package io.github.jackwener.dsh

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jackwener.dsh.core.data.SecretStore
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.data.ThemeMode
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshTheme
import io.github.jackwener.dsh.feature.automation.AutomationScreen
import io.github.jackwener.dsh.feature.automation.RuleDetailScreen
import io.github.jackwener.dsh.feature.conversation.HomeScreen
import io.github.jackwener.dsh.feature.conversation.HomeViewModel
import io.github.jackwener.dsh.feature.settings.KeyOnboarding
import io.github.jackwener.dsh.feature.settings.NotificationAppsScreen
import io.github.jackwener.dsh.feature.settings.SettingsScreen
import io.github.jackwener.dsh.feature.settings.SettingsSection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(settings: SettingsRepository, secrets: SecretStore) : ViewModel() {
    /** null = still loading (keeps the splash up). */
    val theme = settings.settings.map { it.theme }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val hasKey = secrets.read() != null
}

/**
 * The app opens straight into the conversation (dsh home). Onboarding is only the API key when
 * it is missing; everything else (Android permissions) is asked just in time from the composer slot.
 */
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
            val theme by vm.theme.collectAsStateWithLifecycle()
            splash.setKeepOnScreenCondition { theme == null }
            DshTheme(theme ?: ThemeMode.System) {
                Box(Modifier.fillMaxSize().background(Dsh.colors.bgBase)) { if (theme != null) AppNav(vm.hasKey) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingSession = intent.getStringExtra(EXTRA_SESSION)
    }

    @Composable
    private fun AppNav(hasKey: Boolean) {
        val nav = rememberNavController()
        val home: HomeViewModel = hiltViewModel()
        LaunchedEffect(pendingSession) {
            pendingSession?.let { id -> home.open(id); nav.popBackStack("home", inclusive = false); pendingSession = null }
        }
        val start = remember { if (hasKey) "home" else "key" }
        NavHost(nav, startDestination = start, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
            composable("key") { KeyOnboarding(onDone = { nav.navigate("home") { popUpTo("key") { inclusive = true } } }) }
            composable("home") {
                HomeScreen(home,
                    onSettings = { section -> nav.navigate("settings?section=${section ?: ""}") },
                    onAutomation = { nav.navigate("automation") })
            }
            composable("settings?section={section}", arguments = listOf(navArgument("section") { type = NavType.StringType; defaultValue = "" })) { e ->
                SettingsScreen(SettingsSection.of(e.arguments?.getString("section")), onBack = { nav.popBackStack() }, onNotificationApps = { nav.navigate("notif_apps") })
            }
            composable("notif_apps") { NotificationAppsScreen(onBack = { nav.popBackStack() }) }
            composable("automation") {
                AutomationScreen(onBack = { nav.popBackStack() },
                    onNew = { prefill -> home.newSession(prefill); nav.popBackStack("home", inclusive = false) },
                    onOpenRule = { nav.navigate("rule/$it") })
            }
            composable("rule/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                RuleDetailScreen(onBack = { nav.popBackStack() }, onOpenSession = { sid -> home.open(sid); nav.popBackStack("home", inclusive = false) })
            }
        }
    }

    companion object { const val EXTRA_SESSION = "session" }
}
