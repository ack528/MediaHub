package com.localtg.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localtg.AppContainer
import com.localtg.data.*
import com.localtg.ui.tg.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Read the live status on entry/refresh; do not add a permanent polling loop. */
@Composable
fun ServerManagementPage(c: AppContainer) {
    val account by c.session.activeId.collectAsState()
    val scope = rememberCoroutineScope()
    val tg = LocalTg.current
    var status by remember(account) { mutableStateOf<JsonObject?>(null) }
    var config by remember(account) { mutableStateOf<ServerConfiguration?>(null) }
    val changes = remember(account) { mutableStateMapOf<String, JsonElement>() }
    var loadedNamespace by remember(account) { mutableStateOf("") }
    var busy by remember(account) { mutableStateOf(false) }
    var message by remember(account) { mutableStateOf("") }
    var editing by remember(account) { mutableStateOf<ServerConfigField?>(null) }
    var confirm by remember(account) { mutableStateOf<String?>(null) }
    suspend fun refresh() {
        val ns = c.session.ns()
        busy = true
        try {
            val fresh = c.api.serverManagementStatus()
            if (ns != c.session.ns()) return
            status = fresh
            val freshConfig = if (fresh["canManage"]?.jsonPrimitive?.booleanOrNull == true) c.api.serverConfiguration() else null
            if (ns != c.session.ns()) return
            config = freshConfig
            loadedNamespace = ns
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = if (e is ApiException && e.http == 404) "服务端需升级到支持远程配置的版本" else friendlyError(e) }
        finally { busy = false }
    }
    LaunchedEffect(account) { if (!c.isLocal) refresh() }
    if (c.isLocal) { TgInfoCell("本地媒体模式没有服务器配置"); return }
    TgHeaderCell("运行状态")
    val st = status
    if (st != null) {
        TgDetailCell("服务版本", st["version"]?.jsonPrimitive?.content.orEmpty())
        TgDetailCell("运行时间", "${(st["uptimeSec"]?.jsonPrimitive?.longOrNull ?: 0) / 60} 分钟")
        TgDetailCell("媒体 / 群组", "${st["media"] ?: 0} / ${st["dialogs"] ?: 0}")
        val jobs = st["jobs"]?.jsonObject
        TgDetailCell("存储优化", "排队 ${jobs?.get("queued") ?: 0} · 待替换 ${jobs?.get("done") ?: 0} · 失败 ${jobs?.get("failed") ?: 0}")
        (st["index"] as? JsonArray)?.forEach { entry ->
            val p = entry.jsonObject
            TgTextCell(p["label"]?.jsonPrimitive?.content ?: "媒体盘", subtitle = "${p["state"]?.jsonPrimitive?.content.orEmpty()} · ${p["files"] ?: 0} 个文件" + (p["message"]?.jsonPrimitive?.contentOrNull?.let { " · $it" } ?: ""))
        }
        if (st["canManage"]?.jsonPrimitive?.booleanOrNull != true) TgInfoCell("当前账号可查看状态。修改配置需管理员账号，初始管理员为服务器的首个账号。")
    }
    TgTextCell(if (busy) "正在读取…" else "刷新状态与配置", titleColor = tg.accent, onClick = if (busy || changes.isNotEmpty()) null else { { scope.launch { refresh() } } })
    if (message.isNotEmpty()) TgInfoCell(message)
    val cfg = config
    if (cfg != null) {
        TgInfoCell("配置保存到服务器磁盘，重启服务后生效。未保存 ${changes.size} 项。")
        cfg.fields.groupBy { it.group }.forEach { (group, fields) ->
            TgHeaderCell(group)
            fields.forEach { field ->
                val value = changes[field.key] ?: field.value
                if (field.kind == "bool") {
                    TgCheckCell(field.title, null, value.jsonPrimitive.booleanOrNull == true) { enabled ->
                        if (!busy) {
                            if (field.key == "optimize.purgeOriginal" && enabled) confirm = "purge"
                            else if (JsonPrimitive(enabled) == field.value) changes.remove(field.key) else changes[field.key] = JsonPrimitive(enabled)
                        }
                    }
                } else {
                    val label = when (field.kind) {
                        "roots" -> "${(value as? JsonArray)?.size ?: 0} 个目录"
                        "lines" -> "${(value as? JsonArray)?.size ?: 0} 项"
                        "choice" -> serverOptionLabel(value.jsonPrimitive.content)
                        else -> value.jsonPrimitive.contentOrNull.orEmpty().ifEmpty { "未限制" }
                    }
                    TgTextCell(field.title, value = label, onClick = if (busy) null else { { editing = field.copy(value = value) } })
                }
            }
        }
        TgHeaderCell("保存与服务操作")
        TgTextCell("保存配置（${changes.size} 项）", titleColor = tg.accent, onClick = if (busy || changes.isEmpty()) null else { {
            scope.launch {
                if (loadedNamespace != c.session.ns()) { message = "服务器已切换，请重新打开此页面"; return@launch }
                busy = true
                try { c.api.saveServerConfiguration(cfg.revision, changes.toMap()); changes.clear(); message = "配置已保存，点击重启服务后生效"; refresh() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = friendlyError(e) }
                finally { busy = false }
            }
        } })
        if (changes.isNotEmpty()) TgTextCell("放弃未保存的修改", onClick = if (busy) null else { { changes.clear() } })
        TgTextCell("重启服务器", subtitle = "播放和连接会暂时中断。修改地址或 HTTPS 后，可能需要更新已保存的服务器地址。", titleColor = tg.accent,
            onClick = if (busy || changes.isNotEmpty() || st?.get("restartAvailable")?.jsonPrimitive?.booleanOrNull != true) null else { { confirm = "restart" } })
        TgTextCell("重新扫描媒体库", titleColor = tg.accent, onClick = if (busy) null else { { scope.launch {
            busy = true
            try { c.api.rescanServer(); message = "已请求重新扫描" } catch (e: CancellationException) { throw e } catch (e: Exception) { message = friendlyError(e) } finally { busy = false }
        } } })
        TgHeaderCell("配置位置")
        TgTextCell("实际配置文件", subtitle = cfg.configPath)
        TgTextCell("数据目录", subtitle = cfg.dataDir)
    }
    editing?.let { field -> ServerFieldDialog(field, { editing = null }) { value ->
        val original = cfg?.fields?.firstOrNull { it.key == field.key }?.value
        if (value == original) changes.remove(field.key) else changes[field.key] = value
        editing = null
    } }
    confirm?.let { action -> AlertDialog(
        onDismissRequest = { confirm = null },
        title = { Text(if (action == "purge") "永久删除原文件？" else "重启服务器？") },
        text = { Text(if (action == "purge") "启用后，压缩替换成功的原文件会被永久删除，不进入可恢复的保留流程。" else "保存的设置将在重启后生效，连接将暂时断开。") },
        confirmButton = { TextButton(onClick = {
            confirm = null
            if (action == "purge") changes["optimize.purgeOriginal"] = JsonPrimitive(true)
            else scope.launch {
                if (loadedNamespace != c.session.ns()) return@launch
                busy = true
                try { c.api.restartServer(); message = "重启请求已提交，稍后点刷新重新连接" }
                catch (e: CancellationException) { throw e } catch (e: Exception) { message = friendlyError(e) }
                finally { busy = false }
            }
        }) { Text("确定") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
    ) }
}

private fun serverOptionLabel(value: String) = when (value) {
    "auto" -> "自动"; "ffmpeg" -> "内置 FFmpeg"; "qsv" -> "Intel QSV"; "none", "off" -> "关闭"; "exif" -> "使用内嵌缩略图"
    "debug" -> "调试"; "info" -> "信息"; "warn" -> "警告"; "error" -> "错误"; else -> value
}

@Composable
private fun ServerFieldDialog(field: ServerConfigField, onDismiss: () -> Unit, onSave: (JsonElement) -> Unit) {
    var text by remember(field.key) { mutableStateOf(when (field.kind) {
        "roots" -> (field.value as? JsonArray)?.joinToString("\n") { e -> val o = e.jsonObject; o["path"]!!.jsonPrimitive.content + " | " + (o["label"]?.jsonPrimitive?.contentOrNull ?: "") }.orEmpty()
        "lines" -> (field.value as? JsonArray)?.joinToString("\n") { it.jsonPrimitive.content }.orEmpty()
        else -> field.value.jsonPrimitive.contentOrNull.orEmpty()
    }) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(field.title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (field.kind == "choice") field.options.forEach { option ->
                TextButton(onClick = { onSave(JsonPrimitive(option)) }) { Text((if (text == option) "✓ " else "") + serverOptionLabel(option)) }
            } else {
                if (field.kind == "roots") Text("每行一个目录，可在 | 后填写显示名称。例如：F:\\视频 | 视频盘")
                if (field.kind == "lines") Text("每行填写一个目录名")
                OutlinedTextField(text, { text = it; error = "" }, modifier = Modifier.fillMaxWidth(), singleLine = field.kind !in setOf("roots", "lines"), isError = error.isNotEmpty())
                if (field.kind == "number") Text("范围：${field.min.toLong()} ～ ${field.max.toLong()}")
                if (error.isNotEmpty()) Text(error, color = LocalTg.current.danger)
            }
        }
    }, confirmButton = { if (field.kind != "choice") TextButton(onClick = {
        val value: JsonElement = when (field.kind) {
            "number" -> { val n = text.toLongOrNull(); if (n == null || n < field.min || n > field.max) { error = "请输入范围内的整数"; return@TextButton }; JsonPrimitive(n) }
            "roots" -> JsonArray(text.lines().filter { it.isNotBlank() }.map { line -> val parts = line.split('|', limit = 2); buildJsonObject { put("path", parts[0].trim()); put("label", parts.getOrNull(1)?.trim().orEmpty()) } })
            "lines" -> JsonArray(text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) })
            else -> JsonPrimitive(text.trim())
        }
        onSave(value)
    }) { Text("确定") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
