package io.github.jackwener.dsh.feature.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Base64
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.designsystem.DshTheme
import io.github.jackwener.dsh.core.designsystem.Hairline
import io.github.jackwener.dsh.core.designsystem.Pill
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.session.ApprovalOutcome
import io.github.jackwener.dsh.core.session.ChatItem
import io.github.jackwener.dsh.core.session.ConversationState
import io.github.jackwener.dsh.core.session.PendingApproval
import io.github.jackwener.dsh.core.session.PendingQuestion
import io.github.jackwener.dsh.core.session.ToolStatus
import java.util.concurrent.Executors

@Composable
fun MessageList(state: ConversationState, modifier: Modifier = Modifier, onApprove: (PendingApproval, ApprovalOutcome) -> Unit,
                onAnswer: (PendingQuestion, Map<String, Pair<List<String>, String?>>) -> Unit) {
    val list = rememberLazyListState()
    val count = state.items.size + (if (state.approval != null) 1 else 0) + (if (state.question != null) 1 else 0)
    LaunchedEffect(count, state.running) { if (count > 0) list.animateScrollToItem(count) }
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.items, key = { it.key }) { item ->
            when (item) {
                is ChatItem.User -> UserBubble(item.text)
                is ChatItem.Assistant -> AssistantBlock(item)
                is ChatItem.Tool -> ToolStep(item)
                is ChatItem.Notice -> Notice(item)
            }
        }
        state.approval?.let { a -> item(key = "approval-${a.requestKey}") { ApprovalCard(a, onApprove) } }
        state.question?.let { q -> item(key = "question-${q.requestKey}") { QuestionCard(q, onAnswer) } }
        if (state.running && state.approval == null && state.question == null) item(key = "working") {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.waitingForUnlock) Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = DshTheme.colors.warn)
                else CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (state.waitingForUnlock) R.string.waiting_unlock else R.string.thinking),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) = Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
    Surface(shape = RoundedCornerShape(20.dp), color = DshTheme.colors.bubble, modifier = Modifier.widthIn(max = 320.dp)) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AssistantBlock(item: ChatItem.Assistant) = Column(Modifier.fillMaxWidth()) {
    item.reasoning?.let { r ->
        var open by rememberSaveable(item.key) { mutableStateOf(false) }
        Text(if (open) r else "💭 " + r.lineSequence().first().take(60) + "…",
            style = MaterialTheme.typography.bodySmall, color = DshTheme.colors.labelTertiary,
            modifier = Modifier.clickable { open = !open }.animateContentSize().padding(bottom = 4.dp))
    }
    if (item.text.isNotBlank()) Markdown(item.text)
}

@Composable
private fun ToolStep(t: ChatItem.Tool) {
    var open by rememberSaveable(t.key) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(Hairline, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (t.status) {
                    ToolStatus.Running -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    ToolStatus.Done -> Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = DshTheme.colors.success)
                    ToolStatus.Failed -> Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    ToolStatus.Rejected -> Icon(Icons.Default.Close, null, Modifier.size(16.dp), tint = DshTheme.colors.labelTertiary)
                }
                Spacer(Modifier.width(8.dp))
                Text(toolLabel(t.name), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                Text(argsSummary(t.arguments), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (open) {
                Spacer(Modifier.size(6.dp))
                Mono(t.arguments)
                t.result?.let { Spacer(Modifier.size(6.dp)); Mono(it.take(4000)) }
                t.imageBase64?.let { b64 ->
                    val bmp = remember(b64) { runCatching { Base64.decode(b64, 0).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull() }
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(top = 6.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Mono(text: String) = Surface(color = DshTheme.colors.codeBlock, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
    Text(text, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
}

@Composable
private fun Notice(n: ChatItem.Notice) = Text(n.text, style = MaterialTheme.typography.bodySmall,
    color = if (n.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)

fun toolLabel(name: String) = name.replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun argsSummary(args: String) = args.removePrefix("{").removeSuffix("}").replace("\"", "").take(120)

@Composable
fun ApprovalCard(a: PendingApproval, onDecide: (PendingApproval, ApprovalOutcome) -> Unit) {
    val context = LocalContext.current
    val danger = a.risk == Risk.dangerous
    Surface(shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary),
        color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(toolLabel(a.toolName), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Pill(a.risk.name, color = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (danger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
            }
            a.reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            a.arguments?.takeIf { it.isNotBlank() }?.let { Mono(prettyJson(it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onDecide(a, ApprovalOutcome.Reject) }) { Text(stringResource(R.string.reject)) }
                if (!danger && a.risk != Risk.sensitive)
                    TextButton(onClick = { onDecide(a, ApprovalOutcome.AllowSession) }) { Text(stringResource(R.string.allow_session)) }
                Button(onClick = {
                    if (danger) confirmWithDeviceCredential(context) { onDecide(a, ApprovalOutcome.AllowOnce) }
                    else onDecide(a, ApprovalOutcome.AllowOnce)
                }) { Text(stringResource(R.string.allow_once)) }
            }
        }
    }
}

private fun prettyJson(raw: String) = runCatching {
    kotlinx.serialization.json.Json { prettyPrint = true }.let { it.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), it.parseToJsonElement(raw)) }
}.getOrDefault(raw)

/** Dangerous actions require biometric or screen-lock confirmation. */
private fun confirmWithDeviceCredential(context: Context, onOk: () -> Unit) {
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val builder = BiometricPrompt.Builder(context).setTitle(context.getString(R.string.notif_approval_title))
    if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(authenticators) else @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
    runCatching {
        builder.build().authenticate(CancellationSignal(), Executors.newSingleThreadExecutor(), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { android.os.Handler(context.mainLooper).post(onOk) }
        })
    }.onFailure { onOk() } // no lock screen configured: fall back to the explicit tap
}

@Composable
fun QuestionCard(q: PendingQuestion, onAnswer: (PendingQuestion, Map<String, Pair<List<String>, String?>>) -> Unit) {
    val selected = remember(q.requestKey) { mutableStateMapOf<String, List<String>>() }
    val custom = remember(q.requestKey) { mutableStateMapOf<String, String>() }
    Surface(shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
        color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            q.questions.forEach { question ->
                Text(question.text, style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    question.options.forEach { opt ->
                        val on = selected[question.id]?.contains(opt) == true
                        FilterChip(selected = on, onClick = {
                            val cur = selected[question.id] ?: emptyList()
                            selected[question.id] = if (question.multi) (if (on) cur - opt else cur + opt) else listOf(opt)
                        }, label = { Text(opt) })
                    }
                }
                OutlinedTextField(custom[question.id] ?: "", { custom[question.id] = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("…") })
            }
            Button(onClick = {
                onAnswer(q, q.questions.associate { it.id to ((selected[it.id] ?: emptyList()) to custom[it.id]?.takeIf { s -> s.isNotBlank() }) })
            }) { Text(stringResource(R.string.send)) }
        }
    }
}

@Composable
fun Composer(running: Boolean, onSend: (String) -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier, initial: String = "") {
    var text by rememberSaveable { mutableStateOf(initial) }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(Hairline, MaterialTheme.colorScheme.outline), shadowElevation = 2.dp) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.text.BasicTextField(
                text, { text = it }, Modifier.weight(1f).padding(vertical = 12.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.secondary),
                maxLines = 6,
                decorationBox = { inner ->
                    if (text.isEmpty()) Text(stringResource(R.string.input_hint), style = MaterialTheme.typography.bodyLarge,
                        color = DshTheme.colors.labelTertiary)
                    inner()
                })
            if (running && text.isBlank()) FilledIconButton(onClick = onStop) { Icon(Icons.Default.Close, stringResource(R.string.stop)) }
            else FilledIconButton(onClick = { if (text.isNotBlank()) { onSend(text.trim()); text = "" } }, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
            }
        }
    }
}
