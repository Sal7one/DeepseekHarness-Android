package io.github.jackwener.dsh.feature.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.data.Effort
import io.github.jackwener.dsh.core.designsystem.Chevron
import io.github.jackwener.dsh.core.designsystem.DIcon
import io.github.jackwener.dsh.core.designsystem.DMenu
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.Fmt
import io.github.jackwener.dsh.core.designsystem.Hairline
import io.github.jackwener.dsh.core.designsystem.IconBtn
import io.github.jackwener.dsh.core.designsystem.MenuDivider
import io.github.jackwener.dsh.core.designsystem.MenuItem
import io.github.jackwener.dsh.core.designsystem.MenuSectionTitle
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.RiskConfirmDialog
import io.github.jackwener.dsh.core.designsystem.elevated
import io.github.jackwener.dsh.core.designsystem.pressable
import io.github.jackwener.dsh.core.model.PermissionPreset
import io.github.jackwener.dsh.core.session.QueuedMessage
import io.github.jackwener.dsh.core.session.TodoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

data class ImageAttachment(val mime: String, val base64: String, val thumb: Bitmap)

@Stable
class ComposerState {
    var text by mutableStateOf("")
    val images = mutableStateListOf<ImageAttachment>()
    val isEmpty get() = text.isBlank() && images.isEmpty()
    fun clear() { text = ""; images.clear() }
}

@Composable
fun rememberComposerState(key: Any?) = remember(key) { ComposerState() }

/** Model catalog shown by the model chip (dsh ui-model-selection). */
val Models = listOf("deepseek-flash" to "DeepSeek-V4.1-Flash", "deepseek-v4-pro" to "DeepSeek-V4-Pro")
fun modelLabel(id: String) = Models.firstOrNull { it.first == id }?.second ?: id
const val CONTEXT_WINDOW = 1_000_000L

object PresetUi {
    @DrawableRes fun icon(p: PermissionPreset) = when (p) {
        PermissionPreset.ReadOnly -> R.drawable.dsw_perm_read_only
        PermissionPreset.Ask -> R.drawable.dsw_perm_workspace_write
        PermissionPreset.Full -> R.drawable.dsw_perm_full_access
    }
    fun label(p: PermissionPreset) = when (p) {
        PermissionPreset.ReadOnly -> R.string.preset_read_only
        PermissionPreset.Ask -> R.string.preset_ask
        PermissionPreset.Full -> R.string.preset_full
    }
    fun desc(p: PermissionPreset) = when (p) {
        PermissionPreset.ReadOnly -> R.string.preset_read_only_desc
        PermissionPreset.Ask -> R.string.preset_ask_desc
        PermissionPreset.Full -> R.string.preset_full_desc
    }
}

/** Actions reachable from the "+" button (the same menu as "/"). */
data class ComposerActions(
    val onNewSession: (() -> Unit)? = null,
    val onTrajectory: (() -> Unit)? = null,
    val onAutomation: (() -> Unit)? = null,
)

/**
 * dsh InputBar: R28 card on the input-major fill with the soft elevation; editor on top,
 * toolbar below: [+] [permission chip] … [context ring] [model chip] [send/stop 34].
 */
@Composable
fun Composer(
    state: ComposerState,
    running: Boolean,
    preset: PermissionPreset,
    onPreset: (PermissionPreset) -> Unit,
    model: String,
    effort: Effort,
    onModel: (String) -> Unit,
    onEffort: (Effort) -> Unit,
    contextTokens: Long,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    actions: ComposerActions = ComposerActions(),
    placeholder: String = stringResource(if (hero) R.string.composer_hint_hero else R.string.composer_hint),
    showChips: Boolean = true,
) {
    val c = Dsh.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plusOpen by remember { mutableStateOf(false) }
    var presetOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var confirmFull by remember { mutableStateOf(false) }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        scope.launch { uris.forEach { u -> loadImage(context, u)?.let { if (state.images.size < 4) state.images += it } } }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { importFile(context, uri) }
            if (path != null) state.text = (state.text.trimEnd() + "\n" + context.getString(R.string.attached_file, path)).trimStart()
        }
    }

    Column(
        modifier.fillMaxWidth().elevated(Radius.panel, c.borderL2, 8.dp).clip(Radius.panel).background(c.inputMajor).padding(top = 8.dp),
    ) {
        if (state.images.isNotEmpty()) Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.images.forEach { img ->
                Box(Modifier.size(64.dp)) {
                    androidx.compose.foundation.Image(img.thumb.asImageBitmap(), null, Modifier.size(64.dp).clip(Radius.xl), contentScale = ContentScale.Crop)
                    Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp).clip(CircleShape).background(Color(0x99000000)).pressable({ state.images.remove(img) }, CircleShape),
                        contentAlignment = Alignment.Center) { DIcon(R.drawable.dsw_close_outline, size = 10.dp, tint = Color.White) }
                }
            }
        }
        BasicTextField(
            state.text,
            { v -> state.text = v; if (v == "/") { plusOpen = true } },
            Modifier.fillMaxWidth().heightIn(min = if (hero) 52.dp else 36.dp, max = 216.dp).padding(start = 14.dp, end = 8.dp, top = 4.dp),
            textStyle = DshType.base16.copy(color = c.labelPrimary),
            cursorBrush = SolidColor(c.business),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box {
                    if (state.text.isEmpty()) Text(placeholder, style = DshType.base16, color = c.labelCaption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        // dsh control-row layout: below ~460 px the permission chip drops its label.
        val narrow = maxWidth < 400.dp
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                Box(Modifier.size(28.dp).clip(CircleShape).background(c.selector).pressable({ plusOpen = true }, CircleShape), contentAlignment = Alignment.Center) {
                    DIcon(R.drawable.dsw_plus_outline, size = 14.dp, tint = c.labelPrimary, contentDescription = stringResource(R.string.plus_hint))
                }
                DMenu(plusOpen, { plusOpen = false; if (state.text == "/") state.text = "" }) {
                    val close = { plusOpen = false; if (state.text == "/") state.text = "" }
                    MenuSectionTitle(stringResource(R.string.menu_add))
                    MenuItem(stringResource(R.string.menu_image), { close(); pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, R.drawable.dsw_fullscreen_outline)
                    MenuItem(stringResource(R.string.menu_file), { close(); pickFile.launch(arrayOf("*/*")) }, R.drawable.dsw_paperclip_outline)
                    MenuDivider()
                    MenuSectionTitle(stringResource(R.string.menu_commands))
                    if (showChips) {
                        MenuItem(stringResource(R.string.cmd_permission), { close(); presetOpen = true }, R.drawable.dsw_shield_outline, stringResource(R.string.cmd_permission_desc))
                        MenuItem(stringResource(R.string.cmd_model), { close(); modelOpen = true }, R.drawable.dsw_data_outline, stringResource(R.string.cmd_model_desc))
                    }
                    actions.onNewSession?.let { MenuItem(stringResource(R.string.new_session), { close(); it() }, R.drawable.dsw_new_chat_outline) }
                    actions.onTrajectory?.let { MenuItem(stringResource(R.string.view_trajectory), { close(); it() }, R.drawable.dsw_inspect_outline, stringResource(R.string.cmd_trajectory_desc)) }
                    actions.onAutomation?.let { MenuItem(stringResource(R.string.automation), { close(); it() }, R.drawable.dsw_clock_outline) }
                }
            }
            if (showChips) Box {
                Chip(PresetUi.icon(preset), if (narrow) null else stringResource(PresetUi.label(preset)), { presetOpen = true })
                DMenu(presetOpen, { presetOpen = false }) {
                    PermissionPreset.entries.forEach { p ->
                        MenuItem(stringResource(PresetUi.label(p)), {
                            presetOpen = false
                            if (p == PermissionPreset.Full && preset != PermissionPreset.Full) confirmFull = true else onPreset(p)
                        }, PresetUi.icon(p), stringResource(PresetUi.desc(p)), selected = p == preset)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (showChips && contextTokens > 0) ContextRing(contextTokens)
            if (showChips) Box {
                Row(Modifier.height(28.dp).widthIn(max = 200.dp).clip(Radius.sm).pressable({ modelOpen = true }, Radius.sm).padding(start = 6.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(modelLabel(model).removePrefix("DeepSeek-"), style = DshType.xs13m, color = c.labelSecondary, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Text(effort.label, style = DshType.xs13m, color = c.labelCaption, maxLines = 1)
                    Chevron(Modifier.padding(start = 2.dp), size = 12.dp)
                }
                DMenu(modelOpen, { modelOpen = false }) {
                    MenuSectionTitle(stringResource(R.string.model))
                    Models.forEach { (id, label) -> MenuItem(label, { modelOpen = false; onModel(id) }, R.drawable.dsw_data_outline, selected = id == model) }
                    MenuDivider()
                    MenuSectionTitle(stringResource(R.string.effort))
                    Effort.entries.forEach { e -> MenuItem(e.label, { modelOpen = false; onEffort(e) }, null, stringResource(effortDesc(e)), selected = e == effort) }
                    if (running) Text(stringResource(R.string.model_applies_next), Modifier.padding(8.dp), style = DshType.xxs12, color = c.labelCaption)
                }
            }
            SendButton(running, state.isEmpty, onSend, onStop)
        }
        }
    }
    if (confirmFull) RiskConfirmDialog(
        stringResource(R.string.full_confirm_title), stringResource(R.string.full_confirm_body), stringResource(R.string.full_confirm_check),
        stringResource(R.string.full_confirm_ok), stringResource(R.string.cancel),
        onConfirm = { confirmFull = false; onPreset(PermissionPreset.Full) }, onDismiss = { confirmFull = false },
    )
}

private fun effortDesc(e: Effort) = when (e) {
    Effort.Off -> R.string.effort_off_desc; Effort.Low -> R.string.effort_low_desc
    Effort.High -> R.string.effort_high_desc; Effort.Max -> R.string.effort_max_desc
}

/** Composer chip: height 28, R8, 13/20 weight 500 secondary, 14 dp icon + chevron. */
@Composable
private fun Chip(@DrawableRes icon: Int, label: String?, onClick: () -> Unit) {
    val c = Dsh.colors
    Row(Modifier.height(28.dp).clip(Radius.sm).pressable(onClick, Radius.sm).padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DIcon(icon, size = 14.dp, tint = c.labelSecondary)
        if (label != null) {
            Spacer(Modifier.width(4.dp))
            Text(label, style = DshType.xs13m, color = c.labelSecondary, maxLines = 1)
        }
        Chevron(Modifier.padding(start = 2.dp), size = 12.dp)
    }
}

/** 34 dp blue circle: up arrow to send, rounded square to stop (dsh Send/Stop). */
@Composable
fun SendButton(running: Boolean, empty: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val c = Dsh.colors
    val stop = running && empty
    val enabled = stop || !empty
    Box(Modifier.size(34.dp).alpha(if (enabled) 1f else 0.4f).clip(CircleShape).background(c.info)
        .pressable({ if (stop) onStop() else if (!empty) onSend() }, CircleShape, enabled), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(16.dp)) {
            if (stop) {
                drawRoundRect(Color.White, topLeft = Offset(3.dp.toPx(), 3.dp.toPx()), size = Size(10.dp.toPx(), 10.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()))
            } else {
                val w = 1.6.dp.toPx(); val cx = size.width / 2; val top = 1.5.dp.toPx(); val bottom = size.height - 1.5.dp.toPx()
                drawLine(Color.White, Offset(cx, top + w / 2), Offset(cx, bottom), w, StrokeCap.Round)
                drawLine(Color.White, Offset(cx, top), Offset(cx - 5.dp.toPx(), top + 5.dp.toPx()), w, StrokeCap.Round)
                drawLine(Color.White, Offset(cx, top), Offset(cx + 5.dp.toPx(), top + 5.dp.toPx()), w, StrokeCap.Round)
            }
        }
    }
}

/** dsh ContextMeter: 14 dp ring + percentage; tap for the occupancy panel. */
@Composable
fun ContextRing(tokens: Long) {
    val c = Dsh.colors
    var open by remember { mutableStateOf(false) }
    val frac = (tokens.toFloat() / CONTEXT_WINDOW).coerceIn(0f, 1f)
    val pct = (frac * 100).toInt().coerceAtLeast(if (tokens > 0) 1 else 0)
    Box {
        Row(Modifier.height(28.dp).clip(Radius.sm).pressable({ open = true }, Radius.sm).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(14.dp)) {
                val sw = 2.dp.toPx(); val r = 5.5.dp.toPx()
                val tl = Offset(center.x - r, center.y - r); val sz = Size(r * 2, r * 2)
                drawArc(c.borderL3, 0f, 360f, false, tl, sz, style = Stroke(sw))
                drawArc(c.labelTertiary, -90f, 360f * frac, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Spacer(Modifier.width(4.dp))
            Text("$pct%", style = DshType.xs13, color = c.labelTertiary)
        }
        DMenu(open, { open = false }) {
            Column(Modifier.width(240.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.context_used), style = DshType.xs13, color = c.labelSecondary)
                    Text(" $pct%", style = DshType.xs13.copy(fontWeight = FontWeight(600)), color = c.labelPrimary)
                    Spacer(Modifier.weight(1f))
                    Text("~${Fmt.tokens(tokens)} / ${Fmt.tokens(CONTEXT_WINDOW)}", style = DshType.xxs12, color = c.labelTertiary)
                }
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.borderL2)) {
                    Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape).background(c.kindMessages))
                }
            }
        }
    }
}

/** Queue dock (dsh QueueDock): tucked above the composer; edit pulls a message back into the draft. */
@Composable
fun QueueDock(queue: List<QueuedMessage>, onEdit: (QueuedMessage) -> Unit, onDelete: (QueuedMessage) -> Unit, onSendNow: (QueuedMessage) -> Unit) {
    if (queue.isEmpty()) return
    val c = Dsh.colors
    var open by remember { mutableStateOf(queue.size == 1) }
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Radius.lg).background(c.menuFill)
        .then(Modifier.padding(bottom = 4.dp))) {
        if (queue.size > 1) Row(Modifier.fillMaxWidth().height(36.dp).pressable({ open = !open }, Radius.md).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            DIcon(R.drawable.dsw_queue_outline, size = 14.dp, tint = c.labelTertiary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.queued_n, queue.size), Modifier.weight(1f), style = DshType.xs13m, color = c.labelSecondary)
            Chevron(open = open)
        }
        if (open || queue.size == 1) queue.forEach { q ->
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (queue.size == 1) { DIcon(R.drawable.dsw_queue_outline, size = 14.dp, tint = c.labelTertiary); Spacer(Modifier.width(6.dp)) }
                Text(q.text, Modifier.weight(1f), style = DshType.xs13, color = c.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconBtn(R.drawable.dsw_edit_outline, { onEdit(q) }, size = 32.dp, iconSize = 14.dp, contentDescription = stringResource(R.string.queue_edit))
                IconBtn(R.drawable.dsw_trash_outline, { onDelete(q) }, size = 32.dp, iconSize = 14.dp, contentDescription = stringResource(R.string.queue_delete))
                IconBtn(R.drawable.dsw_paper_plane_outline, { onSendNow(q) }, size = 32.dp, iconSize = 14.dp, contentDescription = stringResource(R.string.queue_steer))
            }
        }
    }
}

/** Todo dock (dsh TodoPanel): collapsed header with progress, expands to the checklist. */
@Composable
fun TodoDock(todos: List<TodoItem>) {
    if (todos.isEmpty()) return
    val c = Dsh.colors
    var open by remember { mutableStateOf(false) }
    val done = todos.count { it.status == "completed" }
    val active = todos.count { it.status == "in_progress" }
    val pending = todos.size - done - active
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Radius.lg).background(c.menuFill).border0().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().pressable({ open = !open }, Radius.sm).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            DIcon(R.drawable.dsw_checklist_outline, size = 14.dp, tint = c.labelSecondary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.todos), style = DshType.xs13m.copy(lineHeight = 24.sp), color = c.labelPrimary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.todo_progress, done, active, pending), Modifier.weight(1f), style = DshType.xxs12, color = c.labelTertiary, maxLines = 1)
            Chevron(open = open)
        }
        if (open) Box(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
            TodoList(todos.map { it.content to it.status })
        }
    }
}

private fun Modifier.border0() = this

private suspend fun loadImage(context: Context, uri: Uri): ImageAttachment? = withContext(Dispatchers.IO) {
    runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 1600) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@runCatching null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val thumb = Bitmap.createScaledBitmap(bmp, 128, (128f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
        ImageAttachment("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), thumb)
    }.getOrNull()
}

/** Copies a picked document into the agent's home so dsh's file tools can read it. */
private fun importFile(context: Context, uri: Uri): String? = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: "file_${System.currentTimeMillis()}"
    val dir = File(context.filesDir, "home/attachments").apply { mkdirs() }
    val dest = File(dir, name.replace('/', '_'))
    context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
    dest.path
}.getOrNull()
