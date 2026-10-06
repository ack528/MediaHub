package com.localtg

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import coil3.request.crossfade
import com.localtg.data.Api
import com.localtg.data.Item
import com.localtg.data.SessionStore
import com.localtg.data.buildHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/** 手写的依赖容器(个人自用,不引入 Hilt)。 */
class AppContainer(app: Application) {
    /** 应用级协程域:界面销毁后仍要完成的保存(例如退出群组时保存浏览位置)用它。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val session = SessionStore(app)
    val viewState = com.localtg.data.ViewStateStore(app) { session.ns() }
    val settings = com.localtg.data.SettingsStore(app, scope)
    val playback = com.localtg.data.PlaybackStore(app) { session.ns() }
    val local = com.localtg.data.LocalMedia(app)
    val http: OkHttpClient = buildHttpClient(session, local, settings.value.connectTimeoutSec, settings.value.readTimeoutSec) { s -> scope.launch { session.clearTokenIf(s.baseUrl) } }
    val api = Api(http, session).also { it.serverRenderHeic = settings.value.heicMode == "server" }
    init {
        // 设置改变后立即生效的项
        scope.launch { settings.state.collect { api.serverRenderHeic = it.heicMode == "server" } }
        // 旧版本登录的是 http:// 地址;服务器升级为 HTTPS 后原地址连不上,提示重新连接(确认证书指纹)
        scope.launch {
            session.loaded.first { it }
            val s = session.session.value ?: return@launch
            if (!s.baseUrl.startsWith("http://")) return@launch
            if (runCatching { api.serverInfo(s.baseUrl) }.isSuccess) return@launch
            val hu = runCatching { s.baseUrl.toHttpUrl() }.getOrNull() ?: return@launch
            if (runCatching { com.localtg.data.Tls.probe(hu.host, hu.port) }.isSuccess) {
                AppLog.i("app", "服务器已升级为 HTTPS,旧的 http 登录失效,需要重新连接")
                notice.value = "服务器已升级为加密传输(HTTPS),请重新连接并核对证书指纹"
                session.clearToken()
            }
        }
    }

    /** 聊天页点开查看器时,把当前已加载的条目快照交给查看器。 */
    /** 本地媒体模式(读取手机自己的照片和视频)。 */
    val isLocal: Boolean get() = session.local.value

    /** 登录页顶部的提示(例如服务器升级为加密传输后,旧的 http 登录需要重新连接)。 */
    val notice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    @Volatile var viewerFeed: com.localtg.data.ViewerFeed = com.localtg.data.ViewerFeed(emptyList())

    /** 画中画(小窗)状态与播放信息,供 MainActivity 在按 Home 键时判断是否自动进入小窗。 */
    val inPip = kotlinx.coroutines.flow.MutableStateFlow(false)
    @Volatile var videoPlaying: Boolean = false
    @Volatile var videoAspect: Float = 0f

    /** 对话列表缓存,聊天页用来取标题。 */
    /** 已经提示过"服务端版本太旧,不支持同步"(每次启动只提示一次) */
    @Volatile var syncWarned: Boolean = false

    /** 点开群时文件夹列表上的显示顺序(含盘符标签过滤 / 排序 / 隐藏小文件夹),聊天页据此左右滑动切换上一个 / 下一个群 */
    @Volatile var chatSiblings: List<String> = emptyList()

    @Volatile var dialogCache: Map<String, com.localtg.data.Dialog> = emptyMap()
}

class App : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        com.localtg.render.Assets.app = applicationContext
        AppLog.init(this, container.settings.value.logLevel)
        AppLog.installCrashHandler()
        AppLog.i("app", "启动\n" + AppLog.deviceInfo().trimEnd() + "\n设置: " + container.settings.value)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val s = container.settings.value
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, s.imageMemPercent / 100.0).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("img").toOkioPath())
                    .maxSizeBytes(s.imageDiskCacheMb.toLong() * 1024 * 1024)
                    .build()
            }
            .eventListenerFactory { _ ->
                object : coil3.EventListener() {
                    override fun onError(request: coil3.request.ImageRequest, result: coil3.request.ErrorResult) {
                        AppLog.w("image", "加载失败 ${request.data}: ${result.throwable.javaClass.simpleName}: ${result.throwable.message}")
                    }
                }
            }
            .allowHardware(s.hardwareBitmaps)
            .crossfade(s.crossfade)
            .build()
    }
}
