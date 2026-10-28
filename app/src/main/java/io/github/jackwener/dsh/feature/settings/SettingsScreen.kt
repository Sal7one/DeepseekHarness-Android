package io.github.jackwener.dsh.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jackwener.dsh.BuildConfig
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.data.BusySend
import io.github.jackwener.dsh.core.data.Effort
import io.github.jackwener.dsh.core.data.ThemeMode
import io.github.jackwener.dsh.core.data.WorkDetails
import io.github.jackwener.dsh.core.designsystem.BtnSize
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.CodeCard
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.DIcon
import io.github.jackwener.dsh.core.designsystem.DInput
import io.github.jackwener.dsh.core.designsystem.DSwitch
import io.github.jackwener.dsh.core.designsystem.DTopBar
import io.github.jackwener.dsh.core.designsystem.DotKind
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.FilterPill
import io.github.jackwener.dsh.core.designsystem.Fmt
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.Segmented
import io.github.jackwener.dsh.core.designsystem.SettingsCard
import io.github.jackwener.dsh.core.designsystem.SettingsGroupTitle
import io.github.jackwener.dsh.core.designsystem.SettingsRow
import io.github.jackwener.dsh.core.designsystem.StateDot
import io.github.jackwener.dsh.core.designsystem.Tag
import io.github.jackwener.dsh.core.designsystem.Tone
import io.github.jackwener.dsh.core.engine.EngineState
import io.github.jackwener.dsh.core.model.PermissionPreset
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.feature.conversation.Models
import io.github.jackwener.dsh.feature.conversation.PresetUi
import kotlinx.coroutines.launch

private fun openKeyPage(context: android.content.Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun isZh(): Boolean = LocalConfiguration.current.locales[0].language == "zh"

enum class SettingsSection(val label: Int) {
    Account(R.string.acct_nav), General(R.string.set_general), Model(R.string.set_model), Agent(R.string.set_agent), WebSearch(R.string.set_web),
    Usage(R.string.set_usage), Phone(R.string.set_phone), About(R.string.set_about);
    companion object { fun of(name: String?) = entries.firstOrNull { it.name.equals(name, true) } ?: entries.first() }
}

/** dsh settings at ≤767 px: full-screen sheet, horizontal section strip under the title, stacked rows. */
@Composable
fun SettingsScreen(initial: SettingsSection, onBack: () -> Unit, onNotificationApps: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val c = Dsh.colors
    var section by rememberSaveable { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding()) {
        DTopBar(stringResource(R.string.settings), onBack)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsSection.entries.forEach { s -> FilterPill(stringResource(s.label), s == section) { section = s } }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            when (section) {
                SettingsSection.Account -> {
                    val hasKey by vm.hasKeyFlow.collectAsStateWithLifecycle()
                    SettingsGroupTitle(stringResource(R.string.acct_nav))
                    io.github.jackwener.dsh.feature.account.AccountSettingsSection(vm.account, usingApiKey = hasKey)
                }
                SettingsSection.General -> GeneralSection(vm)
                SettingsSection.Model -> ModelSection(vm)
                SettingsSection.Agent -> AgentSection(vm)
                SettingsSection.WebSearch -> WebSection()
                SettingsSection.Usage -> UsageSection(vm)
                SettingsSection.Phone -> PhoneSection(vm, onNotificationApps)
                SettingsSection.About -> AboutSection(vm)
            }
        }
    }
}

@Composable
private fun GeneralSection(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsGroupTitle(stringResource(R.string.set_general))
    SettingsCard {
        SettingsRow(stringResource(R.string.set_permission), stringResource(R.string.set_permission_desc), divider = false, stacked = true) {
            Segmented(PermissionPreset.entries.map { stringResource(PresetUi.label(it)) }, s.defaultPreset.ordinal, { vm.setDefaultPreset(PermissionPreset.entries[it]) })
        }
        SettingsRow(stringResource(R.string.set_appearance), stacked = true) {
            Segmented(listOf(stringResource(R.string.theme_light), stringResource(R.string.theme_dark), stringResource(R.string.theme_system)),
                when (s.theme) { ThemeMode.Light -> 0; ThemeMode.Dark -> 1; ThemeMode.System -> 2 },
                { vm.setTheme(listOf(ThemeMode.Light, ThemeMode.Dark, ThemeMode.System)[it]) })
        }
        SettingsRow(stringResource(R.string.set_language), stringResource(R.string.set_language_desc), onClick = {
            val i = if (Build.VERSION.SDK_INT >= 33) Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${context.packageName}"))
            else PermissionCatalog.appDetails(context)
            context.startActivity(i)
        }, trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = Dsh.colors.labelTertiary) })
        SettingsRow(stringResource(R.string.set_work_details), stringResource(R.string.set_work_details_desc), stacked = true) {
            Segmented(listOf(stringResource(R.string.details_compact), stringResource(R.string.details_standard), stringResource(R.string.details_detailed)),
                s.workDetails.ordinal, { vm.setWorkDetails(WorkDetails.entries[it]) })
        }
        SettingsRow(stringResource(R.string.set_busy_send), stringResource(R.string.set_busy_send_desc), stacked = true) {
            Segmented(listOf(stringResource(R.string.busy_queue), stringResource(R.string.busy_steer)), s.busySend.ordinal, { vm.setBusySend(BusySend.entries[it]) })
        }
    }
    SettingsGroupTitle(stringResource(R.string.set_entry_points))
    SettingsCard {
        SettingsRow(stringResource(R.string.floating_ball), stringResource(R.string.floating_ball_desc), divider = false, trailing = { DSwitch(s.floatingBall, { vm.setBall(it) }) })
        SettingsRow(stringResource(R.string.default_assistant), stringResource(R.string.default_assistant_desc), onClick = { context.startActivity(vm.assistantSettingsIntent()) },
            trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = Dsh.colors.labelTertiary) })
        SettingsRow(stringResource(R.string.allow_external_apps), stringResource(R.string.allow_external_apps_desc),
            trailing = { DSwitch(s.allowExternalApps, { vm.setAllowExternalApps(it) }) })
    }
}

@Composable
private fun ModelSection(vm: SettingsViewModel) {
    val c = Dsh.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val hasKey by vm.hasKeyFlow.collectAsStateWithLifecycle()
    val status by vm.keyStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    SettingsGroupTitle(stringResource(R.string.set_model))
    // dsh provider card: grey fill, API key field, Cancel / Apply.
    Column(Modifier.fillMaxWidth().clip(Radius.lg).background(c.bgModule).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            io.github.jackwener.dsh.core.designsystem.Whale(width = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text("DeepSeek", Modifier.weight(1f), style = DshType.s14m, color = c.labelPrimary)
            Tag(stringResource(if (hasKey) R.string.key_connected else R.string.key_missing), if (hasKey) Tone.Success else Tone.Warn)
        }
        Text("API key", style = DshType.xs13m, color = c.labelSecondary)
        DInput(key, { key = it }, placeholder = vm.maskedKey ?: "sk-…", secret = true, keyboardType = KeyboardType.Password)
        when (status) {
            null -> {}
            "checking" -> Text(stringResource(R.string.key_checking), style = DshType.xxs12, color = c.labelTertiary)
            else -> Text(stringResource(R.string.key_invalid, status!!), style = DshType.xxs12, color = c.error)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.key_get), Modifier.weight(1f).clip(Radius.sm).clickable { openKeyPage(context) }.padding(vertical = 4.dp),
                style = DshType.xs13, color = c.link)
            if (hasKey) DButton(stringResource(R.string.key_clear), { vm.clearKey() }, variant = BtnVariant.Outline, size = BtnSize.Sm)
            DButton(stringResource(R.string.cancel), { key = "" }, variant = BtnVariant.Outline, size = BtnSize.Sm)
            DButton(stringResource(R.string.apply), { scope.launch { if (vm.saveKey(key) == null) key = "" } }, size = BtnSize.Sm, enabled = key.isNotBlank())
        }
    }
    SettingsGroupTitle(stringResource(R.string.set_default_model))
    SettingsCard {
        Models.forEachIndexed { i, (id, label) ->
            SettingsRow(label, if (id == "deepseek-flash") stringResource(R.string.model_flash_desc) else stringResource(R.string.model_pro_desc), onClick = { vm.setModel(id) }, divider = i > 0,
                trailing = { if (s.model == id) DIcon(R.drawable.dsw_check_outline, size = 16.dp, tint = c.labelPrimary) })
        }
        SettingsRow(stringResource(R.string.effort), stringResource(R.string.effort_desc), stacked = true) {
            Segmented(Effort.entries.map { it.label }, s.effort.ordinal, { vm.setEffort(Effort.entries[it]) })
        }
    }
}

@Composable
private fun AgentSection(vm: SettingsViewModel) {
    val c = Dsh.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    SettingsGroupTitle(stringResource(R.string.set_capabilities))
    Text(stringResource(R.string.set_capabilities_desc), Modifier.padding(start = 4.dp, bottom = 8.dp), style = DshType.xs13, color = c.labelTertiary)
    SettingsCard {
        ToolGroup.entries.forEachIndexed { i, g ->
            val (title, desc) = groupLabel(g)
            SettingsRow(stringResource(title), stringResource(desc), divider = i > 0, trailing = { DSwitch(g in s.enabledGroups, { vm.setGroup(g, it) }) })
        }
    }
    SettingsGroupTitle(stringResource(R.string.set_agent_loop))
    SettingsCard {
        var daily by remember(s.dailyTokenLimit) { mutableStateOf(if (s.dailyTokenLimit > 0) s.dailyTokenLimit.toString() else "") }
        var session by remember(s.sessionTokenLimit) { mutableStateOf(if (s.sessionTokenLimit > 0) s.sessionTokenLimit.toString() else "") }
        SettingsRow(stringResource(R.string.session_limit), stringResource(R.string.limit_hint), divider = false, stacked = true) {
            DInput(session, { session = it.filter(Char::isDigit) }, placeholder = "0", keyboardType = KeyboardType.Number)
        }
        SettingsRow(stringResource(R.string.daily_limit), stringResource(R.string.limit_hint), stacked = true) {
            DInput(daily, { daily = it.filter(Char::isDigit) }, placeholder = "0", keyboardType = KeyboardType.Number)
        }
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterEnd) {
            DButton(stringResource(R.string.save), { vm.setLimits(daily.toLongOrNull() ?: 0, session.toLongOrNull() ?: 0) }, size = BtnSize.Sm)
        }
    }
}

private fun groupLabel(g: ToolGroup) = when (g) {
    ToolGroup.Files -> R.string.grp_files to R.string.grp_files_desc
    ToolGroup.Apps -> R.string.grp_apps to R.string.grp_apps_desc
    ToolGroup.Screen -> R.string.grp_screen to R.string.grp_screen_desc
    ToolGroup.System -> R.string.grp_system to R.string.grp_system_desc
    ToolGroup.Notifications -> R.string.grp_notif to R.string.grp_notif_desc
    ToolGroup.Comms -> R.string.grp_comms to R.string.grp_comms_desc
    ToolGroup.Media -> R.string.grp_media to R.string.grp_media_desc
    ToolGroup.Clipboard -> R.string.grp_clip to R.string.grp_clip_desc
    ToolGroup.Browser -> R.string.grp_browser to R.string.grp_browser_desc
    ToolGroup.Rules -> R.string.grp_rules to R.string.grp_rules_desc
    ToolGroup.VScreen -> R.string.grp_vscreen to R.string.grp_vscreen_desc
}

@Composable
private fun WebSection() {
    SettingsGroupTitle(stringResource(R.string.set_web))
    SettingsCard {
        SettingsRow(stringResource(R.string.web_search_title), stringResource(R.string.web_search_desc), divider = false, trailing = { Tag(stringResource(R.string.enabled), Tone.Success) })
        SettingsRow(stringResource(R.string.web_fetch_title), stringResource(R.string.web_fetch_desc), trailing = { Tag(stringResource(R.string.enabled), Tone.Success) })
    }
}

@Composable
private fun UsageSection(vm: SettingsViewModel) {
    val c = Dsh.colors
    val usage by vm.usage.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val today = usage.firstOrNull { it.day == java.time.LocalDate.now().toString() }
    SettingsGroupTitle(stringResource(R.string.usage_today))
    SettingsCard {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Stat(stringResource(R.string.usage_input), Fmt.tokens(today?.inputTokens ?: 0))
            Stat(stringResource(R.string.usage_cached), Fmt.tokens(today?.cachedTokens ?: 0))
            Stat(stringResource(R.string.usage_output), Fmt.tokens(today?.outputTokens ?: 0))
        }
        if (s.dailyTokenLimit > 0) SettingsRow(stringResource(R.string.daily_limit), Fmt.tokens(s.dailyTokenLimit))
    }
    SettingsGroupTitle(stringResource(R.string.usage_30d))
    SettingsCard {
        val max = usage.maxOfOrNull { it.inputTokens + it.outputTokens }?.coerceAtLeast(1) ?: 1
        if (usage.isEmpty()) SettingsRow(stringResource(R.string.usage_none), divider = false)
        usage.forEachIndexed { i, u ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row { Text(u.day, Modifier.weight(1f), style = DshType.xs13, color = c.labelSecondary)
                    Text("${Fmt.tokens(u.inputTokens + u.outputTokens)} tok", style = DshType.mono12, color = c.labelTertiary) }
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(c.borderL2)) {
                    Box(Modifier.fillMaxWidth((u.inputTokens + u.outputTokens).toFloat() / max).height(4.dp).clip(CircleShape).background(c.kindMessages))
                }
            }
            if (i < usage.lastIndex) io.github.jackwener.dsh.core.designsystem.HDivider()
        }
    }
}

@Composable
private fun Stat(label: String, value: String) = Column {
    Text(value, style = DshType.l20, color = Dsh.colors.labelPrimary)
    Text(label, style = DshType.xxs12, color = Dsh.colors.labelTertiary)
}

/** 手机权限: every Android permission the tools may need, with status and a jump to its system page. */
@Composable
private fun PhoneSection(vm: SettingsViewModel, onNotificationApps: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val zh = isZh()
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    SettingsGroupTitle(stringResource(R.string.set_phone))
    Text(stringResource(R.string.set_phone_desc), Modifier.padding(start = 4.dp, bottom = 8.dp), style = DshType.xs13, color = c.labelTertiary)
    SettingsCard {
        PermissionCatalog.all.forEachIndexed { i, p ->
            val granted = remember(tick) { p.check(context) }
            SettingsRow(p.title(zh), p.why(zh), divider = i > 0,
                leading = { StateDot(if (granted) DotKind.Done else DotKind.Idle) },
                trailing = {
                    if (granted) Text(stringResource(R.string.granted), style = DshType.xs13, color = c.labelTertiary)
                    else DButton(stringResource(R.string.go_grant), {
                        val rt = p.runtime
                        if (rt != null) runtime.launch(rt.toTypedArray())
                        else runCatching { context.startActivity((p.intent(context) ?: PermissionCatalog.appDetails(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                })
        }
    }
    Text(stringResource(R.string.restricted_hint), Modifier.padding(start = 4.dp, top = 8.dp), style = DshType.xxs12, color = c.labelCaption)
    SettingsGroupTitle(stringResource(R.string.set_more))
    SettingsCard {
        SettingsRow(stringResource(R.string.notification_apps), stringResource(R.string.notification_apps_desc), divider = false, onClick = onNotificationApps,
            trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary) })
        val (tip, intent) = remember { PermissionCatalog.vendorKeepAlive(context) }
        SettingsRow(stringResource(R.string.keep_alive), tip, onClick = { runCatching { context.startActivity(intent) } },
            trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary) })
        SettingsRow("Shizuku", when {
            !vm.shizuku.installed -> stringResource(R.string.shizuku_missing)
            !vm.shizuku.running -> stringResource(R.string.shizuku_stopped)
            !vm.shizuku.granted -> stringResource(R.string.shizuku_unauthorized)
            else -> stringResource(R.string.shizuku_ready)
        }, leading = { StateDot(if (vm.shizuku.granted) DotKind.Done else DotKind.Idle) })
    }
}

@Composable
private fun AboutSection(vm: SettingsViewModel) {
    val c = Dsh.colors
    val checks by vm.doctor.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.runDoctor() }
    SettingsGroupTitle(stringResource(R.string.set_about))
    SettingsCard {
        SettingsRow("DSH", stringResource(R.string.version, BuildConfig.VERSION_NAME), divider = false,
            leading = { io.github.jackwener.dsh.core.designsystem.Whale(width = 22.dp) })
        SettingsRow(stringResource(R.string.engine), when (val e = engine) {
            EngineState.Ready -> stringResource(R.string.engine_ready); EngineState.Starting -> stringResource(R.string.starting_engine)
            EngineState.Stopped -> stringResource(R.string.engine_idle); is EngineState.Failed -> e.message.take(300)
        }, leading = { StateDot(when (engine) { EngineState.Ready -> DotKind.Done; is EngineState.Failed -> DotKind.Error; else -> DotKind.Idle }) },
            trailing = { DButton(stringResource(R.string.restart_engine), { vm.restartEngine(); vm.runDoctor() }, variant = BtnVariant.Outline, size = BtnSize.Sm) })
        SettingsRow(stringResource(R.string.about_disclaimer), divider = true)
    }
    SettingsGroupTitle(stringResource(R.string.doctor))
    SettingsCard {
        checks.forEachIndexed { i, ch ->
            SettingsRow(ch.title, ch.detail, divider = i > 0,
                leading = { StateDot(when (ch.ok) { true -> DotKind.Done; false -> DotKind.Error; null -> DotKind.Idle }) },
                onClick = ch.fix?.let { f -> { runCatching { vm.context.startActivity(f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } })
        }
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterEnd) {
            DButton(stringResource(R.string.doctor_rerun), { vm.runDoctor() }, variant = BtnVariant.Outline, size = BtnSize.Sm, icon = R.drawable.dsw_refresh_outline)
        }
    }
    vm.engineLog?.let { log ->
        SettingsGroupTitle(stringResource(R.string.engine_log))
        CodeCard(log, label = "engine.log", maxHeight = 320.dp)
    }
    vm.lastEngineExit?.let { record ->
        SettingsGroupTitle(stringResource(R.string.engine_last_exit))
        CodeCard(record, label = "exit / tombstone", maxHeight = 320.dp)
    }
}

/** Notification whitelist: which apps' notifications the agent may read (default none). */
@Composable
fun NotificationAppsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val c = Dsh.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val apps = remember { vm.installedApps() }
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding()) {
        DTopBar(stringResource(R.string.notification_apps), onBack)
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { DInput(q, { q = it }, placeholder = stringResource(R.string.search)) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).navigationBarsPadding()) {
            items(apps.filter { q.isBlank() || it.first.contains(q, true) || it.second.contains(q, true) }, key = { it.second }) { (label, pkg) ->
                SettingsRow(label, pkg, trailing = { DSwitch(pkg in s.notificationApps, { vm.toggleNotificationApp(pkg) }) })
            }
        }
    }
}

/**
 * First run without a credential (dsh onboarding's model step): whale + 探索未至之境, then the
 * choice — 登录 DeepSeek 账号 (primary; the sign-in dialog and Custom Tab take over) or 使用 API
 * Key (the key card). Straight into the conversation once either exists.
 */
@Composable
fun Onboarding(showKey: Boolean, onShowKey: (Boolean) -> Unit, onDone: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    val status by vm.keyStatus.collectAsStateWithLifecycle()
    val account = vm.account
    val accountState by account.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    LaunchedEffect(accountState.signedIn) { if (accountState.signedIn) onDone() }
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            io.github.jackwener.dsh.core.designsystem.Whale(width = 34.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.hero_title), style = DshType.xl24.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(500)), color = c.labelPrimary)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onb_tagline), style = DshType.s14, color = c.labelTertiary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        SettingsCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.acct_login_title), style = DshType.base16m, color = c.labelPrimary)
                Text(stringResource(R.string.acct_login_desc), style = DshType.xs13, color = c.labelTertiary)
                DButton(stringResource(R.string.onb_sign_in), { account.startSignIn() }, Modifier.fillMaxWidth(), size = BtnSize.Lg,
                    enabled = !accountState.active)
                DButton(stringResource(R.string.onb_use_key), { onShowKey(!showKey) }, Modifier.fillMaxWidth(), variant = BtnVariant.Outline, size = BtnSize.Lg)
                if (showKey) {
                    HDividerSpaced()
                    Text(stringResource(R.string.onb_key_body), style = DshType.xs13, color = c.labelTertiary)
                    DInput(key, { key = it }, placeholder = "sk-…", secret = true, keyboardType = KeyboardType.Password)
                    when (status) {
                        null -> {}
                        "checking" -> Text(stringResource(R.string.key_checking), style = DshType.xxs12, color = c.labelTertiary)
                        else -> Text(stringResource(R.string.key_invalid, status!!), style = DshType.xxs12, color = c.error)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.key_get), Modifier.weight(1f).clip(Radius.sm).clickable { openKeyPage(context) }.padding(vertical = 6.dp),
                            style = DshType.xs13, color = c.link)
                        DButton(stringResource(R.string.continue_), { scope.launch { if (vm.saveKey(key) == null) onDone() } }, enabled = key.isNotBlank() && status != "checking")
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.onb_disclaimer), style = DshType.xxs12, color = c.labelCaption, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun HDividerSpaced() = io.github.jackwener.dsh.core.designsystem.HDivider(Modifier.padding(vertical = 4.dp))
