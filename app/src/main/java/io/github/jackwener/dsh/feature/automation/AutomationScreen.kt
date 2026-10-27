package io.github.jackwener.dsh.feature.automation

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.data.RuleDao
import io.github.jackwener.dsh.core.data.RuleEntity
import io.github.jackwener.dsh.core.designsystem.DshCard
import io.github.jackwener.dsh.core.designsystem.Pill
import io.github.jackwener.dsh.core.model.RuleAction
import io.github.jackwener.dsh.core.model.RuleSpec
import io.github.jackwener.dsh.core.model.Trigger
import io.github.jackwener.dsh.core.trigger.TriggerHub
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RuleRow(val entity: RuleEntity, val spec: RuleSpec?)

@HiltViewModel
class AutomationViewModel @Inject constructor(private val hub: TriggerHub, dao: RuleDao) : ViewModel() {
    val rules = dao.observe().map { list -> list.map { RuleRow(it, hub.decode(it)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun toggle(id: Long, on: Boolean) = viewModelScope.launch { hub.setEnabled(id, on) }
    fun delete(id: Long) = viewModelScope.launch { hub.delete(id) }
}

fun describeTrigger(t: Trigger, zh: Boolean): String = when (t) {
    is Trigger.Time -> t.at?.let { (if (zh) "每天 " else "Daily ") + it + if (t.days.isNotEmpty()) " (${t.days.joinToString(",")})" else "" }
        ?: t.everyMinutes?.let { if (zh) "每 $it 分钟" else "Every $it min" } ?: (if (zh) "一次性" else "Once")
    is Trigger.Wifi -> (if (t.connected) (if (zh) "连上 Wi‑Fi" else "Wi‑Fi connected") else (if (zh) "断开 Wi‑Fi" else "Wi‑Fi disconnected")) + (t.ssid?.let { " $it" } ?: "")
    is Trigger.Bluetooth -> (if (t.connected) (if (zh) "蓝牙连接" else "Bluetooth connected") else (if (zh) "蓝牙断开" else "Bluetooth disconnected")) + (t.deviceName?.let { " $it" } ?: "")
    is Trigger.Power -> if (t.connected) (if (zh) "开始充电" else "Charger connected") else (if (zh) "拔掉充电器" else "Charger removed")
    is Trigger.BatteryLow -> (if (zh) "电量降到 " else "Battery at ") + "${t.percent}%"
    is Trigger.Headset -> if (t.plugged) (if (zh) "插入耳机" else "Headset plugged") else (if (zh) "拔出耳机" else "Headset unplugged")
    is Trigger.Screen -> t.event
    is Trigger.Notification -> (if (zh) "收到通知 " else "Notification ") + listOfNotNull(t.packageName, t.keyword).joinToString(" · ")
    is Trigger.AppOpened -> (if (zh) "打开 " else "Opened ") + t.packageName
    is Trigger.Geofence -> (if (t.enter) (if (zh) "到达" else "Arrive") else (if (zh) "离开" else "Leave")) + " ${"%.4f".format(t.lat)},${"%.4f".format(t.lng)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(onCreateViaChat: (String) -> Unit, vm: AutomationViewModel = hiltViewModel()) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val zh = io.github.jackwener.dsh.feature.settings.isZh()
    var creating by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_automation)) }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.new_rule)) }) },
    ) { pad ->
        if (rules.isEmpty()) Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.empty_rules), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        } else LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rules, key = { it.entity.id }) { r ->
                DshCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(r.spec?.name ?: "#${r.entity.id}", style = MaterialTheme.typography.titleMedium)
                            r.spec?.let { s ->
                                Text(describeTrigger(s.trigger, zh), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Pill(if (s.action is RuleAction.Steps) (if (zh) "固定动作 · 不耗 token" else "Fixed steps · no tokens") else (if (zh) "Agent 执行" else "Agent run"))
                            }
                            r.entity.lastFiredAt?.let { Text(DateUtils.getRelativeTimeSpanString(it).toString(), style = MaterialTheme.typography.labelSmall) }
                        }
                        Switch(checked = r.entity.enabled, onCheckedChange = { vm.toggle(r.entity.id, it) })
                        IconButton(onClick = { vm.delete(r.entity.id) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
    if (creating) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.new_rule)) },
            text = { OutlinedTextField(text, { text = it }, placeholder = { Text(stringResource(R.string.new_rule_hint)) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = {
                creating = false
                onCreateViaChat((if (zh) "帮我创建一个自动化：" else "Create an automation: ") + text)
            }, enabled = text.isNotBlank()) { Text(stringResource(R.string.next)) } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.reject)) } })
    }
}
