package com.localtg.ui.glass

import android.app.ActivityManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.localtg.ui.tg.LocalSettings

/**
 * 液态玻璃(见 docs/17):浮在内容上面的部件采样它下面的内容,做 模糊 + 饱和度 +(小部件)透镜折射、高光、阴影,再盖一层淡膜。
 * 渲染用 Kyant 的 backdrop 库(Apache-2.0)。按设备能力分三级:
 *  - Liquid:Android 13+,全部效果;
 *  - Frosted:Android 12,或设置里选了「只要毛玻璃」:模糊 + 饱和度,没有透镜;
 *  - Translucent:Android 8–11、低内存设备,或设置里关了:不采样,只画半透明的膜。
 */
enum class GlassLevel { Liquid, Frosted, Translucent }

/** 玻璃部件的种类:通栏的顶栏 / 底栏只模糊(大面积的透镜和高光会成一道道光带);小部件(胶囊、按钮)才有透镜、高光、阴影。 */
enum class GlassKind { Bar, Pill }

/** 当前页面内容的图层:玻璃部件从这里采样。null = 没有可采样的内容,只画膜。 */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
fun glassLevel(): GlassLevel {
    val mode = LocalSettings.current.glass
    val ctx = LocalContext.current
    val lowRam = remember { ctx.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true }
    return when {
        mode == "off" || lowRam || Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> GlassLevel.Translucent
        mode == "frosted" || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> GlassLevel.Frosted
        else -> GlassLevel.Liquid
    }
}

/**
 * 把这个部件画成玻璃。tint = 膜的颜色(含透明度,例如顶栏用页面底色的 75%);没法采样时用 [fallbackAlpha] 的不透明度画同一种颜色,
 * 保证字看得清。shape 必须是圆角类的形状(透镜效果只支持这种)。
 */
@Composable
fun Modifier.glass(shape: CornerBasedShape, kind: GlassKind, tint: Color, fallbackAlpha: Float = 0.94f): Modifier {
    val backdrop = LocalGlassBackdrop.current
    val level = glassLevel()
    if (backdrop == null || level == GlassLevel.Translucent) {
        return this.clip(shape).background(tint.copy(alpha = maxOf(tint.alpha, fallbackAlpha)), shape)
    }
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur((if (kind == GlassKind.Bar) 20f else 6f).dp.toPx())
            // 透镜折射:小部件边缘明显弯折;通栏顶栏只在底边轻轻弯一下(液态玻璃的边缘感)
            if (level == GlassLevel.Liquid) { if (kind == GlassKind.Pill) lens(12f.dp.toPx(), 16f.dp.toPx()) else lens(8f.dp.toPx(), 10f.dp.toPx()) }
        },
        highlight = if (kind == GlassKind.Pill) ({ Highlight(width = 0.8f.dp, alpha = 0.55f) }) else null,
        shadow = if (kind == GlassKind.Pill) ({ Shadow(radius = 18f.dp, color = Color.Black.copy(alpha = 0.10f)) }) else null,
        onDrawSurface = { drawRect(tint) },
    )
}

/**
 * 能被玻璃采样的页面:[content] 画进一个图层(页面底色也画在里面),[overlay] 里的玻璃部件(顶栏、悬浮按钮…)从这个图层采样。
 * 内容要自己留出被顶栏盖住的那段高度(通透:内容一直铺到顶,滚动时从玻璃顶栏下面透出来)。
 */
@Composable
fun GlassHost(
    modifier: Modifier = Modifier, background: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit, overlay: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(background), content = content)
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) { overlay() }
    }
}

/**
 * 查看器 / 播放器里浮在画面上的玻璃(深色):不采样 —— 视频画在 SurfaceView 上,图层录不到,采样出来是一块黑把画面挡住;
 * 改成半透明的深色膜 + 上亮下暗的一圈细描边(玻璃的边缘高光)+ 柔和阴影,照片和视频上看起来一致,也不额外耗电。
 */
fun Modifier.mediaGlass(shape: androidx.compose.ui.graphics.Shape, alpha: Float = 0.34f): Modifier =
    this.shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
        .clip(shape)
        .background(Color(0xFF16181C).copy(alpha = alpha), shape)
        .border(0.8.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.08f))), shape)

/** 页面底部被悬浮玻璃底栏盖住的高度(含导航栏);没有底栏的页面是 0。列表据此在底部留白,最后一项能滚到底栏上面。 */
val LocalBottomInset = androidx.compose.runtime.compositionLocalOf { 0.dp }
