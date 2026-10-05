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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

    LaunchedEffect(Unit) { address = c.session.lastAddress() }

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
                        runCatching { c.api.serverInfo(url) }
                            .onSuccess { info = it; normalized = url }
                            .onFailure { error = friendlyError(it) }
                        busy = false
                    }
                },
            ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("连接") }
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

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "本应用只读访问你的媒体,不会修改服务器上的文件。密码不会保存在手机上。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
