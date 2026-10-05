package com.localtg.ui

import android.os.Build
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

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.localtg.R.drawable.ic_logo), null,
            Modifier.size(72.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
        )
        Spacer(Modifier.height(16.dp))
        Text("本地浏览", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text("连接到你自己的媒体服务器", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))

        if (info == null) {
            OutlinedTextField(
                value = address, onValueChange = { address = it; error = null },
                label = { Text("服务器地址") },
                placeholder = { Text("192.168.1.20:8480") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = address.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        val url = normalizeAddress(address)
                        if (url.startsWith("https://")) {
                            // 加密传输:先读取服务器证书指纹;与已信任的一致就直接连,否则让用户核对后再信任
                            val hu = url.toHttpUrl()
                            val fp = runCatching { Tls.probe(hu.host, hu.port) }.getOrElse { error = friendlyError(it); busy = false; return@launch }
                            if (!fp.equals(c.session.pin.value, ignoreCase = true)) { pendingFp = fp; busy = false; return@launch }
                        }
                        connect(url)
                        busy = false
                    }
                },
            ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("连接") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
                onClick = { if (LocalMedia.hasPermission(ctx)) enterLocal() else permLauncher.launch(LocalMedia.permissions()) },
            ) { Text("读取本地媒体(不连接服务器)") }
        } else {
            Text("已连接:${info!!.name}  v${info!!.version}", style = MaterialTheme.typography.titleMedium)
            Text(normalized, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = user, onValueChange = { user = it; error = null }, label = { Text("账号") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pass, onValueChange = { pass = it; error = null }, label = { Text("密码") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = user.isNotBlank() && pass.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        runCatching { c.api.login(normalized, user.trim(), pass, "${Build.MANUFACTURER} ${Build.MODEL}") }
                            .onSuccess {
                                c.session.save(Session(normalized, it.token), address)
                                pass = "" // 密码不保留
                                onDone()
                            }
                            .onFailure { error = friendlyError(it) }
                        busy = false
                    }
                },
            ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("登录") }
            TextButton(onClick = { info = null; error = null }, modifier = Modifier.fillMaxWidth()) { Text("更换服务器") }
        }

        notice?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        pendingFp?.let { fp ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingFp = null },
                title = { Text("确认服务器证书") },
                text = {
                    Column {
                        Text("这是第一次连接这台服务器(或它的证书变了)。请在电脑的 MediaHub 管理程序「网络」页核对下面的指纹,一致才点“信任并连接”。")
                        Spacer(Modifier.height(10.dp))
                        Text("SHA-256 指纹", style = MaterialTheme.typography.labelMedium)
                        Text(fp.chunked(24).joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val url = normalizeAddress(address)
                        pendingFp = null; busy = true; error = null
                        scope.launch { c.session.setPin(fp); connect(url); busy = false }
                    }) { Text("信任并连接") }
                },
                dismissButton = { TextButton(onClick = { pendingFp = null }) { Text("取消") } },
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "本应用只读访问你的媒体,不会修改服务器上的文件。密码不会保存在手机上。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
