package io.github.jackwener.dsh.feature.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.designsystem.DshCard
import io.github.jackwener.dsh.core.designsystem.SectionHeader
import io.github.jackwener.dsh.core.designsystem.Status
import io.github.jackwener.dsh.core.designsystem.StatusRow
import io.github.jackwener.dsh.core.model.ToolGroup
import kotlinx.coroutines.launch

@Composable
fun isZh() = LocalContext.current.resources.configuration.locales[0].language == "zh"

fun groupLabel(g: ToolGroup, zh: Boolean) = when (g) {
    ToolGroup.Files -> if (zh) "文件" else "Files"
    ToolGroup.Apps -> if (zh) "应用" else "Apps"
    ToolGroup.Screen -> if (zh) "屏幕操作" else "Screen control"
    ToolGroup.System -> if (zh) "系统设置" else "System settings"
    ToolGroup.Notifications -> if (zh) "通知" else "Notifications"
    ToolGroup.Comms -> if (zh) "联系人/短信/日历" else "Contacts/SMS/Calendar"
    ToolGroup.Media -> if (zh) "媒体控制" else "Media control"
    ToolGroup.Clipboard -> if (zh) "剪贴板" else "Clipboard"
    ToolGroup.Browser -> if (zh) "浏览器" else "Browser"
    ToolGroup.Rules -> if (zh) "自动化" else "Automations"
    ToolGroup.VScreen -> if (zh) "独立屏幕（需 Shizuku）" else "Agent screen (Shizuku)"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeScreen(onNavigate: (String) -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val zh = isZh()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_me)) }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            DshCard(onClick = { onNavigate("key") }) {
                Text(stringResource(R.string.api_key), style = MaterialTheme.typography.titleSmall)
                Text(vm.maskedKey ?: "—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.model), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("deepseek-flash" to "V4.1 Flash", "deepseek-v4-pro" to "V4 Pro").forEach { (id, label) ->
                        FilterChip(selected = s.model == id, onClick = { vm.setModel(id) }, label = { Text(label) })
                    }
                }
            }
            SectionHeader(stringResource(R.string.capabilities))
            DshCard {
                ToolGroup.entries.forEach { g ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(groupLabel(g, zh), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = g in s.enabledGroups, onCheckedChange = { vm.setGroup(g, it) })
                    }
                }
            }
            SectionHeader(if (zh) "入口" else "Entry points")
            DshCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.floating_ball), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = s.floatingBall, onCheckedChange = { on ->
                        if (on && !android.provider.Settings.canDrawOverlays(context))
                            context.startActivity(PermissionCatalog.all.first { it.id == "overlay" }.intent(context)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        vm.setBall(on)
                    })
                }
                TextButton(onClick = { context.startActivity(vm.assistantSettingsIntent()) }) { Text(stringResource(R.string.default_assistant)) }
            }
            SectionHeader(if (zh) "更多" else "More")
            DshCard {
                listOf("permissions" to R.string.permissions, "notif_apps" to R.string.notification_apps, "usage" to R.string.usage, "doctor" to R.string.doctor).forEach { (route, label) ->
                    ListItem(headlineContent = { Text(stringResource(label)) },
                        supportingContent = if (route == "usage") ({ Text(stringResource(R.string.tokens_today, (usage.firstOrNull()?.let { it.inputTokens + it.outputTokens } ?: 0).toString())) }) else null,
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        modifier = Modifier.clickable { onNavigate(route) })
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        content(Modifier.padding(pad).fillMaxSize())
    }
}

@Composable
fun PermissionList(groups: Set<ToolGroup>?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val zh = isZh()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val items = remember(groups) { if (groups == null) PermissionCatalog.all else PermissionCatalog.forGroups(groups) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { p ->
            val ok = remember(tick) { p.check(context) }
            StatusRow(p.title(zh), p.why(zh), if (ok) Status.Ok else Status.Warn) {
                if (!ok) TextButton(onClick = {
                    if (p.runtime != null) runtime.launch(p.runtime.toTypedArray())
                    else p.intent(context)?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }) { Text(stringResource(R.string.grant)) }
            }
            if (!ok && p.restrictedSetting) TextButton(onClick = { context.startActivity(PermissionCatalog.appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                Text(stringResource(R.string.restricted_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun PermissionsScreen(onBack: () -> Unit) = SubScreen(stringResource(R.string.permissions), onBack) { m ->
    Column(m.verticalScroll(rememberScrollState()).padding(16.dp)) { DshCard { PermissionList(null) } }
}

@Composable
fun KeyScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) = SubScreen(stringResource(R.string.api_key), onBack) { m ->
    val status by vm.keyStatus.collectAsStateWithLifecycle()
    ApiKeyForm(status, onSave = { k -> vm.saveKey(k) == null }, onDone = onBack, modifier = m.padding(16.dp))
}

@Composable
fun ApiKeyForm(status: String?, onSave: suspend (String) -> Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var key by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.onb_key_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("sk-…") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        when (status) {
            "checking" -> Text(stringResource(R.string.onb_key_checking))
            null -> {}
            else -> Text(stringResource(R.string.onb_key_invalid, status), color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://platform.deepseek.com/api_keys")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                Text(stringResource(R.string.onb_key_get))
            }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.Button(onClick = { scope.launch { if (onSave(key)) onDone() } }, enabled = key.isNotBlank() && status != "checking") {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable
fun NotificationAppsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) = SubScreen(stringResource(R.string.notification_apps), onBack) { m ->
    val s by vm.settings.collectAsStateWithLifecycle()
    val apps = remember { vm.installedApps() }
    LazyColumn(m) {
        items(apps, key = { it.second }) { (label, pkg) ->
            ListItem(headlineContent = { Text(label) }, supportingContent = { Text(pkg) },
                trailingContent = { Checkbox(checked = pkg in s.notificationApps, onCheckedChange = { vm.toggleNotificationApp(pkg) }) },
                modifier = Modifier.clickable { vm.toggleNotificationApp(pkg) })
        }
    }
}

@Composable
fun UsageScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) = SubScreen(stringResource(R.string.usage), onBack) { m ->
    val usage by vm.usage.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    var daily by remember(s.dailyTokenLimit) { mutableStateOf(s.dailyTokenLimit.toString()) }
    var session by remember(s.sessionTokenLimit) { mutableStateOf(s.sessionTokenLimit.toString()) }
    Column(m.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DshCard {
            OutlinedTextField(daily, { daily = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.daily_limit)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(session, { session = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.session_limit)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { vm.setLimits(daily.toLongOrNull() ?: 0, session.toLongOrNull() ?: 0) }) { Text(stringResource(R.string.save)) }
        }
        DshCard {
            if (usage.isEmpty()) Text("—")
            usage.forEach { u ->
                Row { Text(u.day, Modifier.weight(1f)); Text("in ${u.inputTokens} · out ${u.outputTokens} · cache ${u.cachedTokens}", style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
fun DoctorScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) = SubScreen(stringResource(R.string.doctor), onBack) { m ->
    val checks by vm.doctor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.runDoctor() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.runDoctor() }
    LazyColumn(m.padding(horizontal = 16.dp)) {
        items(checks) { c ->
            StatusRow(c.title, c.detail, when (c.ok) { true -> Status.Ok; false -> Status.Error; null -> Status.Off }) {
                c.fix?.let { f -> if (c.ok != true) TextButton(onClick = { runCatching { context.startActivity(f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text(stringResource(R.string.open)) } }
            }
            HorizontalDivider()
        }
    }
}
