package io.github.jackwener.dsh.feature.chat

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.engine.EngineState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(onOpen: (String) -> Unit, vm: ChatsViewModel = hiltViewModel()) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_chats)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onOpen("new") }, icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.new_chat)) })
        },
    ) { pad ->
        if (sessions.isEmpty()) Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.empty_chats), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.padding(pad)) {
            items(sessions, key = { it.id }) { s ->
                ListItem(
                    headlineContent = { Text(s.title.ifBlank { "…" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(DateUtils.getRelativeTimeSpanString(s.updatedAt).toString()) },
                    trailingContent = { if (s.running) Text("●", color = MaterialTheme.colorScheme.secondary) },
                    modifier = Modifier.clickable { onOpen(s.id) }.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(onBack: () -> Unit, autoSend: String? = null, vm: ConversationViewModel = hiltViewModel()) {
    androidx.compose.runtime.LaunchedEffect(autoSend) { if (autoSend != null && vm.state.value.items.isEmpty() && !vm.state.value.running) vm.send(autoSend) }
    val state by vm.state.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(state.title.ifBlank { stringResource(R.string.new_chat) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                )
                if (engine == EngineState.Starting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().navigationBarsPadding()) {
            MessageList(state, Modifier.weight(1f).padding(horizontal = 16.dp), onApprove = vm::approve, onAnswer = vm::answer)
            val msg = when {
                error == "no_key" -> stringResource(R.string.no_key)
                error != null -> error
                engine is EngineState.Failed -> stringResource(R.string.engine_failed, (engine as EngineState.Failed).message)
                else -> null
            }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
            Composer(state.running, onSend = { vm.send(it) }, onStop = vm::stop, modifier = Modifier.padding(12.dp))
        }
    }
}

