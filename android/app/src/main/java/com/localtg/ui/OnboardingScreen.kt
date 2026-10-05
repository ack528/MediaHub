package com.localtg.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import com.localtg.ui.tg.LocalTg
import com.localtg.ui.tg.TgBar
import com.localtg.ui.tg.TgIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.localtg.data.LocalMedia
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.localtg.AppContainer
import com.localtg.data.ServerInfo
import com.localtg.data.Tls
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.localtg.data.Session
import com.localtg.data.friendlyError
import com.localtg.data.normalizeAddress
import kotlinx.coroutines.launch

/** 首次启动:先输入服务器地址并验证,再输入账号密码登录。 */
@Composable
fun OnboardingScreen(c: AppContainer, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<ServerInfo?>(null) }
    var normalized by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingFp by remember { mutableStateOf<String?>(null) } // 等待用户确认的证书指纹
    var connectedFp by remember { mutableStateOf<String?>(null) } // 本次连接核对过的证书指纹(登录成功后记到这台服务器上)
    val saved by c.session.servers.collectAsState()
    val notice by c.notice.collectAsState()

    LaunchedEffect(Unit) { address = c.session.lastAddress() }

    // 本地媒体模式:读取手机自己的照片和视频(按文件夹分群),不需要服务器
    val ctx = LocalContext.current
    fun enterLocal() { scope.launch { c.session.enterLocal(); onDone() } }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (LocalMedia.hasPermission(ctx)) enterLocal() else error = "需要允许读取照片和视频,才能浏览手机上的本地媒体(可以在系统设置里授予)"
    }

    /** 验证服务器并进入账号密码步骤。 */
    suspend fun connect(url: String) {
        runCatching { c.api.serverInfo(url) }
            .onSuccess { info = it; normalized = url; c.notice.value = null }
            .onFailure { error = friendlyError(it) }
    }

    val tg = LocalTg.current
    Column(Modifier.fillMaxSize().imePadding()) {
    // 蓝色头部:和文件夹列表 / 设置的顶栏同一个 TgBar
    TgBar {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.localtg.R.drawable.ic_logo), null,
                Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
            )
            Column(Modifier.padding(start = 16.dp)) {
                Text("本地浏览", color = tg.barText, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text("连接到你自己的媒体服务器", color = tg.barSub, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {

        if (info == null && saved.isNotEmpty()) {
            SectionTitle("已保存的服务器")
            saved.forEach { e ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            if (e.loggedIn) scope.launch { c.session.switchTo(e.id) } // 已登录:直接进入(界面会自动跳转)
                            else { address = e.address.ifEmpty { e.baseUrl }; error = null; scope.launch { c.session.switchTo(e.id) } }
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, color = tg.name, fontSize = 16.sp)
                        Text(
                            e.host + if (e.isLocal) "" else if (e.loggedIn) "  ·  已登录" + (if (e.user.isNotEmpty()) "(${e.user})" else "") else "  ·  需要重新登录",
                            color = if (!e.isLocal && !e.loggedIn) tg.warn else tg.message, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(TgIcons.ChevronRight, null, tint = tg.message, modifier = Modifier.size(22.dp))
                }
                HorizontalDivider(color = tg.divider)
            }
            Spacer(Modifier.height(8.dp))
            SectionTitle("添加服务器")
        }

        if (info == null) {
            TgField(address, { address = it; error = null }, "服务器地址", "192.168.1.20:8480", keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(16.dp))
            TgButton(
                "连接", enabled = address.isNotBlank() && !busy, busy = busy,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        val url = normalizeAddress(address)
                        if (url.startsWith("https://")) {
                            // 加密传输:先读取服务器证书指纹;与已信任的一致就直接连,否则让用户核对后再信任
                            val hu = url.toHttpUrl()
                            val fp = runCatching { Tls.probe(hu.host, hu.port) }.getOrElse { error = friendlyError(it); busy = false; return@launch }
                            if (!c.session.isTrusted(fp)) { pendingFp = fp; busy = false; return@launch }
                            connectedFp = fp
                        }
                        connect(url)
                        busy = false
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            TgButton(
                "读取本地媒体(不连接服务器)", enabled = !busy, outlined = true,
                onClick = { if (LocalMedia.hasPermission(ctx)) enterLocal() else permLauncher.launch(LocalMedia.permissions()) },
            )
        } else {
            SectionTitle("已连接")
            Text("${info!!.name}  v${info!!.version}", color = tg.name, fontSize = 16.sp)
            Text(normalized, color = tg.message, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(16.dp))
            TgField(user, { user = it; error = null }, "账号")
            Spacer(Modifier.height(10.dp))
            TgField(pass, { pass = it; error = null }, "密码", password = true, keyboardType = KeyboardType.Password)
            Spacer(Modifier.height(16.dp))
            TgButton(
                "登录", enabled = user.isNotBlank() && pass.isNotEmpty() && !busy, busy = busy,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        runCatching { c.api.login(normalized, user.trim(), pass, "${Build.MANUFACTURER} ${Build.MODEL}") }
                            .onSuccess {
                                c.session.save(Session(normalized, it.token), address, user.trim(), connectedFp)
                                pass = "" // 密码不保留
                                onDone()
                            }
                            .onFailure { error = friendlyError(it) }
                        busy = false
                    }
                },
            )
            TextButton(onClick = { info = null; error = null }, modifier = Modifier.fillMaxWidth()) { Text("更换服务器") }
        }

        notice?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = tg.accent, fontSize = 14.sp)
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = tg.danger, fontSize = 14.sp)
        }
        pendingFp?.let { fp ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingFp = null },
                title = { Text("确认服务器证书") },
                text = {
                    Column {
                        Text("这是第一次连接这台服务器(或它的证书变了)。请在电脑的 MediaHub 管理程序「网络」页核对下面的指纹,一致才点“信任并连接”。")
                        Spacer(Modifier.height(10.dp))
                        Text("SHA-256 指纹", color = tg.message, fontSize = 12.sp)
                        Text(fp.chunked(24).joinToString("\n"), color = tg.name, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val url = normalizeAddress(address)
                        pendingFp = null; busy = true; error = null
                        scope.launch { c.session.trust(fp); connectedFp = fp; connect(url); busy = false }
                    }) { Text("信任并连接") }
                },
                dismissButton = { TextButton(onClick = { pendingFp = null }) { Text("取消") } },
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("本应用只读访问你的媒体,不会修改服务器上的文件。密码不会保存在手机上。", color = tg.message, fontSize = 12.sp)
    }
    }
}

/** 小节标题:和设置页的 Header 同样式(15sp、强调色、中等粗细)。 */
@Composable
private fun SectionTitle(text: String) {
    Text(text, color = LocalTg.current.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
}

/** 输入框:圆角 10dp、强调色焦点、颜色取自主题 —— 不再是 Material 默认的样式。 */
@Composable
private fun TgField(
    value: String, onChange: (String) -> Unit, label: String, placeholderText: String = "",
    password: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text,
) {
    val tg = LocalTg.current
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) },
        placeholder = { if (placeholderText.isNotEmpty()) Text(placeholderText) },
        shape = RoundedCornerShape(10.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = tg.accent, focusedLabelColor = tg.accent, cursorColor = tg.accent,
            unfocusedBorderColor = tg.divider, unfocusedLabelColor = tg.message,
            focusedTextColor = tg.name, unfocusedTextColor = tg.name,
        ),
    )
}

/** 主按钮 / 次按钮:高 48dp、圆角 10dp、强调色。 */
@Composable
private fun TgButton(text: String, enabled: Boolean, busy: Boolean = false, outlined: Boolean = false, onClick: () -> Unit) {
    val tg = LocalTg.current
    val shape = RoundedCornerShape(10.dp)
    val mod = Modifier.fillMaxWidth().height(48.dp)
    if (outlined) {
        OutlinedButton(
            onClick = onClick, modifier = mod, enabled = enabled, shape = shape,
            border = BorderStroke(1.dp, if (enabled) tg.accent else tg.divider),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = tg.accent),
        ) { Text(text, fontSize = 16.sp) }
    } else {
        Button(
            onClick = onClick, modifier = mod, enabled = enabled, shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = tg.accent, contentColor = Color.White),
        ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) else Text(text, fontSize = 16.sp) }
    }
}
