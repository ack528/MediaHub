package com.localtg.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.localtg.ui.tg.LocalSettings
import com.localtg.AppContainer
import com.localtg.AppLog

@Composable
fun AppRoot(c: AppContainer) {
    val loaded by c.session.loaded.collectAsState()
    val session by c.session.session.collectAsState()
    LaunchedEffect(Unit) { c.session.load() }

    // Android 17+:访问局域网需要运行时授权
    val ctx = LocalContext.current
    val lanPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 37 && ctx.checkSelfPermission(lanPermission) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(lanPermission)
        }
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val nav = rememberNavController()
    DisposableEffect(nav) {
        val l = androidx.navigation.NavController.OnDestinationChangedListener { _, d, args ->
            AppLog.i("nav", "进入 ${d.route}" + (args?.getString("id")?.let { " id=$it" } ?: "") + (args?.getString("index")?.let { " index=$it" } ?: "") +
                (args?.getString("section")?.let { " section=$it" } ?: ""))
        }
        nav.addOnDestinationChangedListener(l)
        onDispose { nav.removeOnDestinationChangedListener(l) }
    }
    CrashNotice()
    val style = LocalSettings.current.motion
    val m = remember(style) { navMotion(style) }
    @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransition provides this) {
            NavHost(
                nav, startDestination = if (session != null) "dialogs" else "onboarding",
                enterTransition = m.enter, exitTransition = m.exit, popEnterTransition = m.popEnter, popExitTransition = m.popExit,
                predictivePopEnterTransition = m.predEnter, predictivePopExitTransition = m.predExit,
            ) {
                composable(
                    "onboarding",
                    enterTransition = { fadeThroughIn(style) }, exitTransition = { fadeThroughOut(style) },
                    popEnterTransition = { fadeThroughIn(style) }, popExitTransition = { fadeThroughOut(style) },
                ) {
                    NavScreen(this) {
                        OnboardingScreen(c) { nav.navigate("dialogs") { popUpTo("onboarding") { inclusive = true } } }
                    }
                }
                composable(
                    "dialogs",
                    enterTransition = { if (initialState.destination.route == "onboarding") fadeThroughIn(style) else m.enter(this) },
                    exitTransition = { if (targetState.destination.route == "onboarding") fadeThroughOut(style) else m.exit(this) },
                ) {
                    NavScreen(this) {
                        DialogsScreen(
                            c, onOpen = { nav.navigate("chat/${it.id}") }, onSettings = { nav.navigate("settings") },
                            onSearch = { nav.navigate("search") }, onSection = { nav.navigate("settings/$it") },
                        )
                    }
                }
                composable("search") {
                    NavScreen(this) {
                        SearchScreen(c, onBack = { nav.popBackStack() }, onOpenViewer = { index -> nav.navigate("viewer/$index") })
                    }
                }
                composable("settings") {
                    NavScreen(this) {
                        SettingsScreen(c, section = null, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("settings/$it") })
                    }
                }
                composable("settings/{section}") { entry ->
                    NavScreen(this) {
                        SettingsScreen(
                            c, section = entry.arguments?.getString("section"),
                            onBack = { nav.popBackStack() }, onOpen = { nav.navigate("settings/$it") },
                        )
                    }
                }
                composable(
                    "chat/{id}",
                    // 进入查看器时聊天页原地不动(由共享元素负责过渡),返回时同样
                    exitTransition = { if (targetState.destination.route?.startsWith("viewer") == true) ExitTransition.KeepUntilTransitionsFinished else m.exit(this) },
                    popEnterTransition = { if (initialState.destination.route?.startsWith("viewer") == true) EnterTransition.None else m.popEnter(this) },
                ) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    NavScreen(this) {
                        ChatScreen(
                            c, dialogId = id,
                            onBack = { nav.popBackStack() },
                            onOpenViewer = { index -> nav.navigate("viewer/$index") },
                        )
                    }
                }
                composable(
                    "viewer/{index}",
                    enterTransition = { viewerIn(style) }, exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None }, popExitTransition = { viewerOut(style) },
                ) { entry ->
                    val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
                    NavScreen(this, Color.Black) {
                        ViewerScreen(c, startIndex = index, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    // 令牌失效(401)或退出登录 → 回到登录页
    LaunchedEffect(session) {
        if (session == null && nav.currentDestination?.route != "onboarding") {
            nav.navigate("onboarding") { popUpTo(0) }
        }
    }
}

/** 上次崩溃过:启动时提示,可直接分享 / 复制日志。 */
@Composable
private fun CrashNotice() {
    val ctx = LocalContext.current
    var crash by remember { mutableStateOf(AppLog.pendingCrash()) }
    val f = crash ?: return
    AlertDialog(
        onDismissRequest = { AppLog.dismissPendingCrash(); crash = null },
        title = { Text("上次异常退出了") },
        text = {
            Column {
                Text("崩溃信息已保存。把日志发给开发者就能定位问题。")
                val brief = AppLog.crashSummary(f)
                if (brief.isNotEmpty()) Text(brief, modifier = androidx.compose.ui.Modifier.padding(top = 8.dp), fontSize = 12.sp)
                TextButton(onClick = {
                    val n = AppLog.copyRecent(ctx)
                    android.widget.Toast.makeText(ctx, "已复制 $n 字,可直接粘贴", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("复制日志到剪贴板") }
            }
        },
        confirmButton = { TextButton(onClick = { runCatching { AppLog.share(ctx) }; AppLog.dismissPendingCrash(); crash = null }) { Text("分享日志") } },
        dismissButton = { TextButton(onClick = { AppLog.dismissPendingCrash(); crash = null }) { Text("忽略") } },
    )
}

/** 每个页面铺一层不透明背景,滑动过渡时才不会透出下面的页面。 */
@Composable
private fun NavScreen(scope: AnimatedVisibilityScope, bg: Color? = null, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNavScope provides scope) {
        Box(Modifier.fillMaxSize().background(bg ?: MaterialTheme.colorScheme.background)) { content() }
    }
}
