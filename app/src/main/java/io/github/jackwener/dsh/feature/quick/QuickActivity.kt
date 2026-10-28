package io.github.jackwener.dsh.feature.quick

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.jackwener.dsh.MainActivity
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.data.ThemeMode
import io.github.jackwener.dsh.core.designsystem.DIcon
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshTheme
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.IconBtn
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.Whale
import io.github.jackwener.dsh.core.designsystem.elevated
import io.github.jackwener.dsh.core.designsystem.pressable
import io.github.jackwener.dsh.feature.conversation.ChatStream
import io.github.jackwener.dsh.feature.conversation.ComposerSlot
import io.github.jackwener.dsh.feature.conversation.QuickViewModel
import io.github.jackwener.dsh.feature.conversation.rememberComposerState

/**
 * The summon-anywhere panel (floating ball, QS tile, assistant gesture, share sheet, RUN_TASK):
 * a compact dsh card with the current run's progress and the same composer slot (approval /
 * permission / question cards replace the composer exactly as on the home screen).
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
            val vm: QuickViewModel = hiltViewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            DshTheme(settings.theme) {
                val c = Dsh.colors
                val live by vm.live.collectAsStateWithLifecycle()
                val conv by vm.conversation.collectAsStateWithLifecycle()
                val composer = rememberComposerState(live.sessionId)
                var useContext by remember { mutableStateOf(ScreenContext.text != null) }
                var sentExternal by remember { mutableStateOf(false) }
                LaunchedEffect(externalTask) { if (externalTask != null && !sentExternal) { sentExternal = true; vm.send(externalTask) } }
                val list = rememberLazyListState()
                Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { finish() }, contentAlignment = Alignment.BottomCenter) {
                    Column(
                        Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(8.dp)
                            .elevated(Radius.panel, c.borderL2, 12.dp).clip(Radius.panel).background(c.bgBase)
                            .clickable(remember { MutableInteractionSource() }, null) {}.padding(bottom = 8.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Whale(width = 20.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(live.title.ifBlank { stringResource(R.string.app_name) }, Modifier.weight(1f), style = DshType.s14m, color = c.labelPrimary, maxLines = 1)
                            IconBtn(R.drawable.dsw_right_up_outline, {
                                startActivity(Intent(this@QuickActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, vm.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                finish()
                            }, size = 40.dp, contentDescription = stringResource(R.string.open_in_app))
                            IconBtn(R.drawable.dsw_close_outline, { finish() }, size = 40.dp, contentDescription = stringResource(R.string.close))
                        }
                        if (useContext && ScreenContext.packageName != null) Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).clip(Radius.sm).background(c.selector)
                            .pressable({ useContext = false }, Radius.sm).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DIcon(R.drawable.dsw_fullscreen_outline, size = 12.dp, tint = c.labelSecondary)
                            Text(stringResource(R.string.current_page, appLabel(ScreenContext.packageName!!)), style = DshType.xxs12, color = c.labelSecondary)
                            DIcon(R.drawable.dsw_close_outline, size = 10.dp, tint = c.labelTertiary)
                        }
                        if (!conv.isEmpty || live.running) {
                            ChatStream(conv, live, settings.workDetails, list, onInspect = { open(vm.id) }, onRetry = vm::retry, onOpenSettings = { open(null) },
                                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp), compact = true)
                        }
                        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            ComposerSlot(vm, composer, settings, live, conv, hero = false, prepare = { text ->
                                val ctx = buildString {
                                    if (useContext && ScreenContext.text != null)
                                        append("[The user summoned you while looking at ${ScreenContext.packageName}. Screen content:]\n${ScreenContext.text}\n")
                                    if (conv.isEmpty) shared?.let { append(it) }
                                }
                                useContext = false
                                if (ctx.isBlank()) text else "$ctx\n$text"
                            })
                        }
                    }
                }
            }
        }
    }

    private fun open(sessionId: String?) {
        startActivity(Intent(this, MainActivity::class.java).apply { sessionId?.let { putExtra(MainActivity.EXTRA_SESSION, it) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
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
