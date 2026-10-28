package io.github.jackwener.dsh.feature.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.data.BusySend
import io.github.jackwener.dsh.core.data.Effort
import io.github.jackwener.dsh.core.data.SecretStore
import io.github.jackwener.dsh.core.data.ThemeMode
import io.github.jackwener.dsh.core.data.WorkDetails
import io.github.jackwener.dsh.core.model.PermissionPreset
import io.github.jackwener.dsh.core.data.Settings as DshSettings
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.data.UsageDao
import io.github.jackwener.dsh.core.data.UsageEntity
import io.github.jackwener.dsh.core.engine.EngineManager
import io.github.jackwener.dsh.core.engine.EngineState
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.platform.ShizukuShell
import io.github.jackwener.dsh.service.FloatingBallService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

data class DoctorCheck(val title: String, val ok: Boolean?, val detail: String, val fix: Intent? = null)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    private val repo: SettingsRepository,
    private val secrets: SecretStore,
    private val engine: EngineManager,
    val shizuku: ShizukuShell,
    val account: io.github.jackwener.dsh.core.account.AccountRepository,
    usage: UsageDao,
) : ViewModel() {
    val settings: StateFlow<DshSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, DshSettings())
    val usage: StateFlow<List<UsageEntity>> = usage.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val engineState: StateFlow<EngineState> = engine.state
    val doctor = MutableStateFlow<List<DoctorCheck>>(emptyList())
    val keyStatus = MutableStateFlow<String?>(null)

    val hasKey get() = secrets.read() != null
    val hasKeyFlow = MutableStateFlow(secrets.read() != null)
    fun clearKey() = viewModelScope.launch { secrets.clear(); hasKeyFlow.value = false; engine.stop() }
    val maskedKey get() = secrets.read()?.let { it.take(5) + "…" + it.takeLast(4) }

    fun setGroup(g: ToolGroup, on: Boolean) = viewModelScope.launch { repo.setGroupEnabled(g, on) }
    fun setModel(m: String) = viewModelScope.launch { repo.setModel(m) }
    fun setEffort(e: Effort) = viewModelScope.launch { repo.setEffort(e) }
    fun setTheme(t: ThemeMode) = viewModelScope.launch { repo.setTheme(t) }
    fun setWorkDetails(w: WorkDetails) = viewModelScope.launch { repo.setWorkDetails(w) }
    fun setBusySend(b: BusySend) = viewModelScope.launch { repo.setBusySend(b) }
    fun setDefaultPreset(p: PermissionPreset) = viewModelScope.launch { repo.setDefaultPreset(p) }
    fun setAllowExternalApps(on: Boolean) = viewModelScope.launch { repo.setAllowExternalApps(on) }
    fun restartEngine() = viewModelScope.launch { engine.stop() }
    val engineLog: String? get() = io.github.jackwener.dsh.core.engine.EngineDiagnostics.logTail(context, 4000)
    val lastEngineExit: String? get() = ReliabilityDoctor.lastExitText(context)
    /** Bumped on resume so permission rows re-check their status. */
    val permissionTick = MutableStateFlow(0)
    fun setBall(on: Boolean) = viewModelScope.launch { repo.setFloatingBall(on); FloatingBallService.setEnabled(context, on) }
    fun setLimits(daily: Long, session: Long) = viewModelScope.launch { repo.setLimits(daily, session) }
    fun toggleNotificationApp(pkg: String) = viewModelScope.launch {
        val cur = settings.value.notificationApps
        repo.setNotificationApps(if (pkg in cur) cur - pkg else cur + pkg)
    }

    /** Validates against DeepSeek's /models before saving. Returns null on success, else the error. */
    suspend fun saveKey(key: String): String? {
        keyStatus.value = "checking"
        val err = validateKey(key.trim())
        keyStatus.value = err
        if (err == null) { secrets.write(key.trim()); hasKeyFlow.value = true; engine.stop() }
        return err
    }

    fun installedApps(): List<Pair<String, String>> = context.packageManager.getInstalledApplications(0)
        .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName.contains("messaging") || it.packageName.contains("mms") }
        .map { context.packageManager.getApplicationLabel(it).toString() to it.packageName }
        .sortedBy { it.first.lowercase() }

    fun runDoctor() = viewModelScope.launch {
        val zh = context.resources.configuration.locales[0].language == "zh"
        val checks = mutableListOf<DoctorCheck>()
        PermissionCatalog.forGroups(settings.value.enabledGroups).forEach {
            checks += DoctorCheck(it.title(zh), it.check(context), it.why(zh), it.intent(context))
        }
        val (tip, intent) = PermissionCatalog.vendorKeepAlive(context)
        checks += DoctorCheck(if (zh) "厂商后台限制" else "Vendor background limits", null, tip, intent)
        checks += DoctorCheck("Shizuku", if (!shizuku.installed) null else shizuku.granted,
            when { !shizuku.installed -> if (zh) "未安装（可选增强）" else "Not installed (optional)"
                   !shizuku.running -> if (zh) "已安装但未启动" else "Installed, not running"
                   !shizuku.granted -> if (zh) "未授权" else "Not authorized"
                   else -> if (zh) "可用" else "Ready" })
        val signedIn = account.signedIn
        checks += DoctorCheck(if (zh) "模型凭据" else "Model credential", hasKey || signedIn, when {
            hasKey -> "API key " + (maskedKey ?: "")
            signedIn -> if (zh) "DeepSeek 账号" else "DeepSeek account"
            else -> if (zh) "未登录，也未设置 API Key" else "Not signed in, no API key"
        })
        val engineDetail = when (val s = engine.state.value) {
            EngineState.Ready -> (if (zh) "运行中，启动用时 " else "Running, started in ") + "${engine.startupMillis} ms"
            is EngineState.Failed -> s.message
            else -> if (zh) "空闲（按需启动）" else "Idle (starts on demand)"
        }
        checks += DoctorCheck(if (zh) "Agent 引擎" else "Agent engine", engine.state.value !is EngineState.Failed, engineDetail)
        checks += DoctorCheck(if (zh) "存储空间" else "Storage", (context.filesDir.usableSpace > 500_000_000L),
            "${context.filesDir.usableSpace / 1_000_000} MB free")
        checks += withContext(Dispatchers.IO) { ReliabilityDoctor.checks(context, zh) }
        doctor.value = checks
    }

    private suspend fun validateKey(key: String): String? = withContext(Dispatchers.IO) {
        if (key.length < 20) return@withContext "too short"
        runCatching {
            val c = URL("https://api.deepseek.com/models").openConnection() as HttpURLConnection
            c.setRequestProperty("Authorization", "Bearer $key"); c.connectTimeout = 10_000; c.readTimeout = 10_000
            val code = c.responseCode
            c.disconnect()
            if (code == 200) null else "HTTP $code"
        }.getOrElse { it.message ?: "network error" }
    }

    fun assistantSettingsIntent() = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
