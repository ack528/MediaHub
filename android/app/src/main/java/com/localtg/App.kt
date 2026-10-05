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
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/** 手写的依赖容器(个人自用,不引入 Hilt)。 */
class AppContainer(app: Application) {
    /** 应用级协程域:界面销毁后仍要完成的保存(例如退出群组时保存浏览位置)用它。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val session = SessionStore(app)
    val viewState = com.localtg.data.ViewStateStore(app)
    val settings = com.localtg.data.SettingsStore(app, scope)
    val playback = com.localtg.data.PlaybackStore(app)
    val http: OkHttpClient = buildHttpClient(session, settings.value.connectTimeoutSec, settings.value.readTimeoutSec) { scope.launch { session.clearToken() } }
    val api = Api(http, session)

    /** 聊天页点开查看器时,把当前已加载的条目快照交给查看器。 */
    @Volatile var viewerFeed: com.localtg.data.ViewerFeed = com.localtg.data.ViewerFeed(emptyList())

    /** 画中画(小窗)状态与播放信息,供 MainActivity 在按 Home 键时判断是否自动进入小窗。 */
    val inPip = kotlinx.coroutines.flow.MutableStateFlow(false)
    @Volatile var videoPlaying: Boolean = false
    @Volatile var videoAspect: Float = 0f

    /** 对话列表缓存,聊天页用来取标题。 */
    @Volatile var dialogCache: Map<String, com.localtg.data.Dialog> = emptyMap()
}

class App : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
