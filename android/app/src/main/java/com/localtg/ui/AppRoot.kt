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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                DialogsScreen(
                    c,
                    onOpen = { ChatActivity.start(ctx, it.id) },
                    onSettings = { SettingsActivity.start(ctx, null) },
                    onSearch = { SearchActivity.start(ctx) },
                    onSection = { SettingsActivity.start(ctx, it) },
                )
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

/**
 * 聊天 / 搜索页里的"查看器":根页面 → 查看器,缩略图 ↔ 全屏用共享元素(容器转换),
 * 返回手势 = 整页缩小淡出(见 [navMotion] 的预测性规格)。根页面本身是 Activity 的内容,不做进入动画(Activity 转场由系统负责)。
 */
@Composable
fun ViewerNavHost(c: AppContainer, root: @Composable (openViewer: (Int) -> Unit) -> Unit) {
    val nav = rememberNavController()
    DisposableEffect(nav) {
        val l = androidx.navigation.NavController.OnDestinationChangedListener { _, d, args ->
            AppLog.i("nav", "进入 ${d.route}" + (args?.getString("index")?.let { " index=$it" } ?: ""))
        }
        nav.addOnDestinationChangedListener(l)
        onDispose { nav.removeOnDestinationChangedListener(l) }
    }
    val style = LocalSettings.current.motion
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val m = remember(style, density) { navMotion(style, density) }
    @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransition provides this) {
            NavHost(
                nav, startDestination = "root",
                enterTransition = m.enter, exitTransition = m.exit, popEnterTransition = m.popEnter, popExitTransition = m.popExit,
                predictivePopEnterTransition = m.predEnter, predictivePopExitTransition = m.predExit,
            ) {
                composable(
                    "root",
                    // 进入查看器时根页面原地不动(由共享元素负责过渡),返回时同样
                    exitTransition = { if (targetState.destination.route?.startsWith("viewer") == true) ExitTransition.KeepUntilTransitionsFinished else m.exit(this) },
                    popEnterTransition = { if (initialState.destination.route?.startsWith("viewer") == true) EnterTransition.None else m.popEnter(this) },
                ) {
                    NavScreen(this) { root { index -> nav.navigate("viewer/$index") } }
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
internal fun NavScreen(scope: AnimatedVisibilityScope, bg: Color? = null, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNavScope provides scope) {
        val style = LocalSettings.current.motion
        // 系统风格:离开的页面逐渐变成圆角(系统预测性返回的做法),手势预览时进入的页面带一层随进度变亮的遮罩;
        // 整屏滑动风格:左边缘投影。
        val corner by scope.transition.animateDp(transitionSpec = { tween(300, easing = LinearEasing) }, label = "corner") {
            if (it == EnterExitState.PostExit && style == Motion.SLIDE) 28.dp else 0.dp
        }
        val scrim by scope.transition.animateFloat(transitionSpec = { tween(450, easing = LinearEasing) }, label = "scrim") {
            if (it == EnterExitState.PreEnter && style == Motion.SLIDE && scope.transition.isSeeking) 0.22f else 0f
        }
        Box(
            Modifier.fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (style == Motion.PARALLAX) {
                        val w = 14.dp.toPx()
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Transparent, Color(0x2E000000)), startX = -w, endX = 0f),
                            topLeft = Offset(-w, 0f), size = Size(w, size.height),
                        )
                    }
                }
                .graphicsLayer { if (corner > 0.dp) { shape = RoundedCornerShape(corner); clip = true } }
                .background(bg ?: MaterialTheme.colorScheme.background)
                .drawWithContent { drawContent(); if (scrim > 0f) drawRect(Color.Black.copy(alpha = scrim)) },
        ) { content() }
    }
}
