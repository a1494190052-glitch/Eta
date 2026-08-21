package fuck.andes.ui.pages.acp

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.R
import fuck.andes.agent.acp.AcpAgentEnvironmentInstaller
import fuck.andes.agent.acp.AcpOfficialAgents
import fuck.andes.agent.acp.AcpSetupStage
import fuck.andes.ui.components.MiuixDialogActions
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * ACP Agent 一键配置区块（Agent 模式环境设置）。
 *
 * 显示 Alpine 工具环境状态与官方 agent 目录（Codex / Gemini CLI /
 * DeepSeek Harness / Claude Code / OpenCode）的安装状态，提供单个安装
 * 与全部安装；安装成功后自动写入 [fuck.andes.agent.acp.AcpProfileStore]。
 *
 * 用法：在 LazyColumn（MiuixScaffoldPage content）中直接调用。
 */
internal fun LazyListScope.AcpSetupSection(
    context: Context,
    onProfilesChanged: () -> Unit,
) {
    item(key = "acp-setup") {
        AcpSetupSectionContent(context = context, onProfilesChanged = onProfilesChanged)
    }
}

@Composable
private fun AcpSetupSectionContent(
    context: Context,
    onProfilesChanged: () -> Unit,
) {
    val installer = remember(context.applicationContext) {
        AcpAgentEnvironmentInstaller(context.applicationContext)
    }
    val coroutineScope = rememberCoroutineScope()
    var status by remember {
        mutableStateOf(installer.status())
    }
    var stage by remember { mutableStateOf<AcpSetupStage>(AcpSetupStage.Idle) }
    var busyAgentId by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }

    // 初始完整探测（command -v 各 agent），放 IO 线程避免阻塞首帧。
    LaunchedEffect(Unit) {
        status = installer.refreshStatus()
    }

    suspend fun refresh() {
        status = installer.refreshStatus()
    }

    fun installAgent(agentId: String) {
        if (busyAgentId != null) return
        val agent = AcpOfficialAgents.byId(agentId) ?: return
        busyAgentId = agentId
        resultMessage = null
        coroutineScope.launch {
            val error = installer.installAgent(agent) { next ->
                stage = next
            }
            busyAgentId = null
            stage = AcpSetupStage.Idle
            refresh()
            onProfilesChanged()
            resultMessage = error ?: context.getString(R.string.acp_setup_done)
            showResultDialog = true
        }
    }

    fun installAll() {
        if (busyAgentId != null) return
        val pending = AcpOfficialAgents.ALL.filter { status.installed[it.id] != true }
        if (pending.isEmpty()) {
            resultMessage = context.getString(R.string.acp_setup_done)
            showResultDialog = true
            return
        }
        busyAgentId = "__all__"
        resultMessage = null
        coroutineScope.launch {
            val failed = mutableListOf<String>()
            for (agent in pending) {
                stage = AcpSetupStage.InstallingAgent(agent.id, agent.packages.firstOrNull())
                val error = installer.installAgent(agent) { next ->
                    if (next !is AcpSetupStage.InstallingAgent) stage = next
                }
                if (error != null) failed.add(agent.name)
            }
            busyAgentId = null
            stage = AcpSetupStage.Idle
            refresh()
            onProfilesChanged()
            resultMessage = if (failed.isEmpty()) {
                context.getString(R.string.acp_setup_done)
            } else {
                context.getString(R.string.acp_setup_failed) + ": " + failed.joinToString(", ")
            }
            showResultDialog = true
        }
    }

    val busy = busyAgentId != null
    val alpineReady = status.alpineReady

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    ) {
        SmallTitle(stringResource(R.string.acp_setup_title))
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
            BasicComponent(
                title = when {
                    stage is AcpSetupStage.InstallingAlpine ->
                        context.getString(R.string.acp_setup_alpine_installing)
                    alpineReady -> context.getString(R.string.acp_setup_alpine_ready)
                    else -> context.getString(R.string.acp_setup_alpine_pending)
                },
                summary = stringResource(R.string.acp_setup_summary),
                endActions = {
                    TextButton(
                        text = stringResource(R.string.acp_setup_install_all),
                        enabled = !busy,
                        onClick = { installAll() },
                    )
                },
            )
        }
        if (alpineReady) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AcpOfficialAgents.ALL.forEach { agent ->
                    val installed = status.installed[agent.id] == true
                    val isBusy = busyAgentId == agent.id || busyAgentId == "__all__"
                    Card(modifier = Modifier.fillMaxWidth()) {
                        BasicComponent(
                            title = agent.name,
                            summary = agent.description,
                            endActions = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (installed) {
                                        Icon(
                                            painter = painterResource(LucideR.drawable.lucide_ic_check),
                                            contentDescription = null,
                                            tint = Color(0xFF00BD13),
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = stringResource(R.string.acp_setup_installed),
                                            style = MiuixTheme.textStyles.body2,
                                            color = Color(0xFF00BD13),
                                            modifier = Modifier.padding(start = 6.dp),
                                        )
                                    } else {
                                        TextButton(
                                            text = when {
                                                isBusy -> context.getString(R.string.acp_setup_installing)
                                                stage is AcpSetupStage.Verifying &&
                                                    (stage as AcpSetupStage.Verifying).agentId == agent.id ->
                                                    context.getString(R.string.acp_setup_verify)
                                                else -> context.getString(R.string.acp_setup_install)
                                            },
                                            enabled = !busy,
                                            onClick = { installAgent(agent.id) },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showResultDialog) {
        OverlayDialog(
            show = true,
            title = stringResource(
                if (resultMessage?.startsWith(context.getString(R.string.acp_setup_failed)) == true) {
                    R.string.acp_setup_failed
                } else {
                    R.string.acp_setup_done
                }
            ),
            summary = resultMessage.orEmpty(),
            onDismissRequest = { showResultDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_done),
                onCancel = { showResultDialog = false },
                onConfirm = {
                    showResultDialog = false
                    resultMessage = null
                },
            )
        }
    }
}
