package io.github.jackwener.dsh.feature.quick

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jackwener.dsh.MainActivity
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.designsystem.DshTheme
import io.github.jackwener.dsh.core.engine.EngineManager
import io.github.jackwener.dsh.core.session.SessionStore
import io.github.jackwener.dsh.feature.chat.Composer
import io.github.jackwener.dsh.feature.chat.ConversationController
import io.github.jackwener.dsh.feature.chat.MessageList
import javax.inject.Inject

@HiltViewModel
class QuickViewModel @Inject constructor(store: SessionStore, engine: EngineManager) : ConversationController(store, engine, null)

/**
 * The summon-anywhere panel: opened by the floating button, quick-settings tile, the system
 * assistant gesture, share sheet, or other apps (RUN_TASK). Sits over the current app.
 */
@AndroidEntryPoint
class QuickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (!intent.getBooleanExtra(EXTRA_FROM_ASSIST, false) && savedInstanceState == null) ScreenContext.captureFromAccessibility()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = sharedPayload(intent)
        val externalTask = intent.getStringExtra(EXTRA_TASK)?.takeIf { intent.action == ACTION_RUN_TASK }
        setContent {
            DshTheme {
                val vm: QuickViewModel = hiltViewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                var useContext by remember { mutableStateOf(ScreenContext.text != null) }
                var sentExternal by remember { mutableStateOf(false) }
                if (externalTask != null && !sentExternal) { sentExternal = true; vm.send(externalTask) }
                Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { finish() }, contentAlignment = Alignment.BottomCenter) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {}.imePadding(),
                    ) {
                        Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    startActivity(Intent(this@QuickActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, vm.id)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish()
                                }) { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) }
                                IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, null) }
                            }
                            if (useContext && ScreenContext.packageName != null) AssistChip(onClick = { useContext = false },
                                label = { Text(stringResource(R.string.current_page, appLabel(ScreenContext.packageName!!))) },
                                trailingIcon = { Icon(Icons.Default.Close, null) })
                            if (state.items.isNotEmpty() || state.running || state.approval != null)
                                MessageList(state, Modifier.heightIn(max = 420.dp), onApprove = vm::approve, onAnswer = vm::answer)
                            Composer(state.running, onSend = { text ->
                                val ctx = buildString {
                                    if (useContext && ScreenContext.text != null)
                                        append("[The user summoned you while looking at ${ScreenContext.packageName}. Screen content:]\n${ScreenContext.text}\n")
                                    shared?.let { append(it) }
                                }.ifBlank { null }
                                useContext = false
                                vm.send(text, ctx)
                            }, onStop = vm::stop)
                        }
                    }
                }
            }
        }
    }

    private fun appLabel(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /** Shared text/files become context for the first message. Files are referenced by name; content URIs are readable while the panel lives. */
    private fun sharedPayload(i: Intent): String? {
        if (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE) return null
        val text = i.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val uris: List<Uri> = (if (i.action == Intent.ACTION_SEND) listOfNotNull(i.getParcelableExtra(Intent.EXTRA_STREAM))
            else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList())
        val names = uris.map { u ->
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: u.toString()
        }
        return buildString {
            append("[The user shared with you:]\n")
            text?.let { append(it).append('\n') }
            if (names.isNotEmpty()) append("files: ").append(names.joinToString()).append(" (search shared storage by name to access them)\n")
        }
    }

    override fun onDestroy() { if (isFinishing) ScreenContext.clear(); super.onDestroy() }

    companion object {
        const val EXTRA_FROM_ASSIST = "from_assist"
        const val ACTION_RUN_TASK = "io.github.jackwener.dsh.RUN_TASK"
        const val EXTRA_TASK = "task"
    }
}
