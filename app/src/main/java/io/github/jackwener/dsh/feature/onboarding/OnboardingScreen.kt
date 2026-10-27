package io.github.jackwener.dsh.feature.onboarding

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.designsystem.DshCard
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.model.UseCase
import io.github.jackwener.dsh.feature.settings.ApiKeyForm
import io.github.jackwener.dsh.feature.settings.PermissionCatalog
import io.github.jackwener.dsh.feature.settings.PermissionList
import io.github.jackwener.dsh.feature.settings.SettingsViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    fun finish(uses: Set<UseCase>, onDone: () -> Unit) = viewModelScope.launch {
        // Clipboard and media are low-risk conveniences that come with any choice.
        repo.setGroups(uses.flatMap { it.groups }.toSet() + ToolGroup.Clipboard + ToolGroup.Media + ToolGroup.System)
        repo.setOnboarded()
        onDone()
    }
}

/** Welcome → key → what for → just-what's-needed settings → keep alive → first task. Target: < 3 minutes. */
@Composable
fun OnboardingScreen(onFinished: (firstPrompt: String?) -> Unit, vm: OnboardingViewModel = hiltViewModel(), settingsVm: SettingsViewModel = hiltViewModel()) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var uses by remember { mutableStateOf(setOf(UseCase.ManageFiles, UseCase.OperateApps)) }
    val keyStatus by settingsVm.keyStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val example = stringResource(R.string.onb_try_example)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
        LinearProgressIndicator(progress = { (step + 1) / 5f }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.size(24.dp))
        AnimatedContent(step, modifier = Modifier.weight(1f), label = "onb") { s ->
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (s) {
                    0 -> {
                        Text(stringResource(R.string.onb_welcome_title), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.onb_welcome_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    1 -> {
                        Text(stringResource(R.string.onb_key_title), style = MaterialTheme.typography.headlineSmall)
                        if (settingsVm.hasKey) Text(settingsVm.maskedKey ?: "", style = MaterialTheme.typography.bodyMedium)
                        ApiKeyForm(keyStatus, onSave = { settingsVm.saveKey(it) == null }, onDone = { step = 2 })
                    }
                    2 -> {
                        Text(stringResource(R.string.onb_uses_title), style = MaterialTheme.typography.headlineSmall)
                        listOf(UseCase.ManageFiles to (R.string.use_files to R.string.use_files_desc),
                            UseCase.OperateApps to (R.string.use_apps to R.string.use_apps_desc),
                            UseCase.Automate to (R.string.use_auto to R.string.use_auto_desc),
                            UseCase.ReadNotifications to (R.string.use_notif to R.string.use_notif_desc)).forEach { (u, labels) ->
                            DshCard(onClick = { uses = if (u in uses) uses - u else uses + u }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(labels.first), style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(labels.second), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Checkbox(checked = u in uses, onCheckedChange = null)
                                }
                            }
                        }
                    }
                    3 -> {
                        Text(stringResource(R.string.onb_setup_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.onb_setup_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DshCard { PermissionList(uses.flatMap { it.groups }.toSet()) }
                    }
                    else -> {
                        Text(stringResource(R.string.onb_vendor_title), style = MaterialTheme.typography.headlineSmall)
                        val (tip, intent) = remember { PermissionCatalog.vendorKeepAlive(context) }
                        DshCard {
                            Text(tip, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text(stringResource(R.string.open)) }
                        }
                        Text(stringResource(R.string.onb_try_title), style = MaterialTheme.typography.titleMedium)
                        Text("“$example”", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (step in 3..3) TextButton(onClick = { step++ }) { Text(stringResource(R.string.skip)) } else Spacer(Modifier.size(1.dp))
            when (step) {
                1 -> if (settingsVm.hasKey) Button(onClick = { step = 2 }) { Text(stringResource(R.string.next)) }
                4 -> Button(onClick = { vm.finish(uses) { onFinished(if (UseCase.ManageFiles in uses) example else null) } }) { Text(stringResource(R.string.done)) }
                else -> Button(onClick = { step++ }) { Text(stringResource(R.string.next)) }
            }
        }
    }
}
