package io.github.jackwener.dsh.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.designsystem.BtnSize
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.DotKind
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.SettingsCard
import io.github.jackwener.dsh.core.designsystem.StateDot
import io.github.jackwener.dsh.core.designsystem.Tag
import io.github.jackwener.dsh.core.designsystem.Tone
import io.github.jackwener.dsh.core.platform.Browser
import io.github.jackwener.dsh.core.platform.ShellAccess
import io.github.jackwener.dsh.core.platform.ShellState

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ShellAccessEntryPoint { fun shellAccess(): ShellAccess }

fun shellAccessOf(context: Context): ShellAccess =
    EntryPointAccessors.fromApplication(context.applicationContext, ShellAccessEntryPoint::class.java).shellAccess()

/** The live Shizuku state; also re-checks on every resume (permission can be revoked inside Shizuku). */
@Composable
fun rememberShellState(): Pair<ShellAccess, ShellState> {
    val context = LocalContext.current
    val access = remember { shellAccessOf(context) }
    val state by access.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access.refresh() }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return access to state
}

/** Short status label for rows and Doctor. */
fun shellStateLabel(context: Context, s: ShellState): String = context.getString(when (s) {
    ShellState.NotInstalled -> R.string.shizuku_missing
    is ShellState.InstalledNotRunning -> R.string.shizuku_stopped
    is ShellState.RunningNoPermission -> R.string.shizuku_unauthorized
    is ShellState.Unsupported -> R.string.shizuku_unsupported
    ShellState.Ready -> R.string.shizuku_ready
})

/** The one-tap fix for the current state (Doctor rows), or null when there is nothing to open. */
fun shellFixIntent(access: ShellAccess, s: ShellState): Intent? = when (s) {
    ShellState.NotInstalled, is ShellState.Unsupported -> access.playStoreIntent()
        ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ShellAccess.RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    is ShellState.InstalledNotRunning, is ShellState.RunningNoPermission -> access.launchIntent()
    ShellState.Ready -> null
}

/**
 * Remembers what the user already did in this guide so the next step happens by itself:
 * after 打开 Shizuku, the moment its server comes up DSH asks for the permission without another tap.
 */
class ShellGuideFlow(private val access: ShellAccess, private val context: Context, private val dark: Boolean, private val toolbar: Int) {
    var openedShizuku by mutableStateOf(false)
    var askedPermission by mutableStateOf(false)

    fun install() {
        val play = access.playStoreIntent()
        if (play != null && runCatching { context.startActivity(play) }.isSuccess) return
        Browser.open(context, ShellAccess.RELEASES_URL, dark, toolbar)
    }
    fun openShizuku() {
        access.launchIntent()?.let { i -> if (runCatching { context.startActivity(i) }.isSuccess) { openedShizuku = true; return } }
        install()
    }
    fun developerOptions() { runCatching { context.startActivity(access.developerOptionsIntent()) } }
    fun requestPermission() { askedPermission = true; access.requestPermission() }
}

@Composable
fun rememberShellGuideFlow(access: ShellAccess, state: ShellState): ShellGuideFlow {
    val context = LocalContext.current
    val c = Dsh.colors
    val flow = remember(access) { ShellGuideFlow(access, context, c.isDark, c.bgBase.toArgb()) }
    LaunchedEffect(state, flow.openedShizuku) {
        // Came back from Shizuku with its server running: go straight to the permission prompt.
        if (flow.openedShizuku && state is ShellState.RunningNoPermission && !state.denied && !flow.askedPermission) flow.requestPermission()
    }
    return flow
}

/** Title + explanation (+ the 3-step mini guide) for the state. [toolTitle]: the waiting tool, JIT only. */
@Composable
fun ColumnScope.ShellGuideBody(s: ShellState, toolTitle: String?) {
    val c = Dsh.colors
    val title = when (s) {
        ShellState.NotInstalled -> stringResource(R.string.shz_title_install)
        is ShellState.InstalledNotRunning -> stringResource(if (s.wasReady) R.string.shz_title_stopped else R.string.shz_title_start)
        is ShellState.RunningNoPermission -> stringResource(R.string.shz_title_permission)
        is ShellState.Unsupported -> stringResource(R.string.shz_title_old)
        ShellState.Ready -> stringResource(R.string.shz_title_ready)
    }
    Text(title, style = DshType.s14m.copy(lineHeight = DshType.base16.lineHeight), color = c.labelPrimary)
    if (toolTitle != null && !s.ready) Text(stringResource(R.string.shz_jit_body, toolTitle), style = DshType.xs13, color = c.labelSecondary)
    val body = when (s) {
        ShellState.NotInstalled -> stringResource(R.string.shz_body_install)
        is ShellState.InstalledNotRunning -> if (s.wasReady) stringResource(R.string.shz_body_stopped) else null
        is ShellState.RunningNoPermission -> stringResource(if (s.denied) R.string.shz_body_denied else R.string.shz_body_permission)
        is ShellState.Unsupported -> stringResource(R.string.shz_body_old)
        ShellState.Ready -> stringResource(if (toolTitle != null) R.string.shz_body_resuming else R.string.shz_body_ready)
    }
    body?.let { Text(it, style = DshType.xs13, color = if (toolTitle != null) c.labelTertiary else c.labelSecondary) }
    if (s is ShellState.InstalledNotRunning && !s.wasReady) {
        listOf(R.string.shz_step1, R.string.shz_step2, R.string.shz_step3).forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.Top) {
                Text("${i + 1}", Modifier.width(16.dp), style = DshType.xs13m, color = c.labelTertiary)
                Text(stringResource(step), style = DshType.xs13, color = c.labelSecondary)
            }
        }
    }
}

/** Buttons for the state; [onDecline] (不用了) is shown when the caller has a fallback to go to. */
@Composable
fun ShellGuideActions(s: ShellState, flow: ShellGuideFlow, onDecline: (() -> Unit)?, enabled: Boolean = true, size: BtnSize = BtnSize.Md) {
    if (onDecline != null && !s.ready) DButton(stringResource(R.string.shz_decline), onDecline, variant = BtnVariant.Outline, enabled = enabled, size = size)
    when (s) {
        ShellState.NotInstalled -> DButton(stringResource(R.string.shz_install), flow::install, enabled = enabled, size = size)
        is ShellState.Unsupported -> DButton(stringResource(R.string.shz_update), flow::install, enabled = enabled, size = size)
        is ShellState.InstalledNotRunning -> {
            if (!s.wasReady) DButton(stringResource(R.string.shz_dev_options), flow::developerOptions, variant = BtnVariant.Outline, enabled = enabled, size = size)
            DButton(stringResource(R.string.shz_open), flow::openShizuku, enabled = enabled, size = size)
        }
        is ShellState.RunningNoPermission ->
            if (s.denied) DButton(stringResource(R.string.shz_open), flow::openShizuku, enabled = enabled, size = size)
            else DButton(stringResource(R.string.shz_grant), flow::requestPermission, enabled = enabled, size = size)
        ShellState.Ready -> {}
    }
}

/**
 * Standalone Shizuku card for 设置 → 手机权限 and the onboarding 增强能力 step: status, guidance and
 * — once ready — the one-time "start on boot (wireless debugging)" tip.
 */
@Composable
fun ShellAccessCard(modifier: Modifier = Modifier, onDecline: (() -> Unit)? = null) {
    val c = Dsh.colors
    val (access, s) = rememberShellState()
    val flow = rememberShellGuideFlow(access, s)
    var tipDismissed by remember { mutableStateOf(access.bootTipDismissed) }
    SettingsCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StateDot(when (s) { ShellState.Ready -> DotKind.Done; is ShellState.Unsupported -> DotKind.Error; ShellState.NotInstalled -> DotKind.Idle; else -> DotKind.Warn })
                Text("Shizuku", Modifier.weight(1f), style = DshType.xs13m, color = c.labelTertiary)
                Tag(shellStateLabel(LocalContext.current, s), if (s.ready) Tone.Success else Tone.Neutral)
            }
            ShellGuideBody(s, null)
            if (s.ready && access.bootTipApplicable && !tipDismissed) {
                Box(Modifier.size(2.dp))
                Text(stringResource(R.string.shz_boot_tip), style = DshType.xs13, color = c.labelSecondary)
            }
        }
        val showTip = s.ready && access.bootTipApplicable && !tipDismissed
        if (!s.ready || showTip) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (showTip) {
                DButton(stringResource(R.string.shz_tip_ok), { access.dismissBootTip(); tipDismissed = true }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                DButton(stringResource(R.string.shz_open), { access.dismissBootTip(); tipDismissed = true; flow.openShizuku() }, size = BtnSize.Sm)
            } else ShellGuideActions(s, flow, onDecline, size = BtnSize.Sm)
        }
    }
}
