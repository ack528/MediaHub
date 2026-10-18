package com.localtg.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.localtg.AppContainer
import com.localtg.AppLog
import com.localtg.ChatActivity
import com.localtg.RandomActivity
import com.localtg.SearchActivity
import com.localtg.SettingsActivity
import com.localtg.ui.tg.LocalSettings

/**
 * 主界面(MainActivity)只负责"登录页"和"文件夹列表"。
 *
 * 页面之间的前进 / 返回动画不再自己画 —— 和 Clash Meta for Android、Shizuku 一样,**每个页面是一个 Activity**
 * (聊天 → [ChatActivity]、设置及各分类页 → [SettingsActivity]、搜索 → [SearchActivity]),
 * 前进用 startActivity、返回用 finish() / 系统返回手势,动画完全交给系统:
 * 系统的 Activity 转场(由系统进程里的 WindowManager 动画,不受应用主线程卡顿影响)和系统的跨 Activity 预测性返回(缩小 + 圆角 + 跟手)。
 * 只有"缩略图 ↔ 全屏查看器"仍在 Activity 内部用共享元素(见 [ViewerNavHost]),系统转场做不到。
 */
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
        val l = androidx.navigation.NavController.OnDestinationChangedListener { _, d, _ -> AppLog.i("nav", "进入 ${d.route}") }
        nav.addOnDestinationChangedListener(l)
        onDispose { nav.removeOnDestinationChangedListener(l) }
    }
    CrashNotice()
    val style = LocalSettings.current.motion
    NavHost(nav, startDestination = if (session != null) "dialogs" else "onboarding") {
        composable(
            "onboarding",
            enterTransition = { fadeThroughIn(style) }, exitTransition = { fadeThroughOut(style) },
            popEnterTransition = { fadeThroughIn(style) }, popExitTransition = { fadeThroughOut(style) },
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                OnboardingScreen(c) { nav.navigate("dialogs") { popUpTo("onboarding") { inclusive = true }; launchSingleTop = true } }
            }
        }
        composable(
            "dialogs",
            enterTransition = { fadeThroughIn(style) }, exitTransition = { fadeThroughOut(style) },
            popEnterTransition = { fadeThroughIn(style) }, popExitTransition = { fadeThroughOut(style) },
        ) {
            // 从文件夹(消息页)返回时:消息页正常向右滑出,下面的主界面从 0.92 放大回 1(B 站 / 小红书返回时背景的景深);
            // 缩小时四周的空隙用临近色(顶部是顶栏色,下面是列表底色)
            val backdrop = remember { BackdropSpec() }
            val returnScale = remember { androidx.compose.animation.core.Animatable(1f) }
            val left = remember { booleanArrayOf(false) } // 刚打开了文件夹 / 随机页
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            DisposableEffect(lifecycle, style) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                    if (style == Motion.OFF) return@LifecycleEventObserver
                    when (e) {
                        androidx.lifecycle.Lifecycle.Event.ON_STOP -> if (left[0]) scope.launch { returnScale.snapTo(0.92f) } // 看不见的时候先缩好
                        androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if (left[0]) {
                            left[0] = false
                            scope.launch { returnScale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 1.2f, stiffness = 420f, visibilityThreshold = 0.001f)) }
                        }
                        else -> {}
                    }
                }
                lifecycle.addObserver(obs)
                onDispose { lifecycle.removeObserver(obs) }
            }
            val pageBg = MaterialTheme.colorScheme.background
            Box(
                Modifier.fillMaxSize()
                    .drawBehind { drawBackdrop(backdrop, returnScale.value, pageBg) }
                    .graphicsLayer { val s = returnScale.value; scaleX = s; scaleY = s }
                    .background(pageBg),
            ) {
              CompositionLocalProvider(LocalBackdrop provides backdrop) {
                val view = androidx.compose.ui.platform.LocalView.current
                DialogsScreen(
                    c,
                    onOpen = { d, r -> left[0] = true; ChatActivity.start(ctx, d.id, d.title, expandFrom(view, r, style)) }, // 从点的那一行展开
                    onSettings = { SettingsActivity.start(ctx, null) },
                    onSearch = { SearchActivity.start(ctx) },
                    onRandom = { label, roots, r -> left[0] = true; RandomActivity.start(ctx, label, roots, expandFrom(view, r, style)) },
                    onSection = { SettingsActivity.start(ctx, it) },
                )
              }
            }
        }
    }

    // 令牌失效(401)、退出登录、"添加服务器" → 回到登录页(其它 Activity 自己会 finish);
    // 在登录页点已保存的服务器切换成功 → 进入列表
    LaunchedEffect(session) {
        val route = nav.currentDestination?.route
        if (session == null && route != "onboarding") {
            nav.navigate("onboarding") { popUpTo(0) }
        } else if (session != null && route == "onboarding") {
            nav.navigate("dialogs") { popUpTo("onboarding") { inclusive = true }; launchSingleTop = true }
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
