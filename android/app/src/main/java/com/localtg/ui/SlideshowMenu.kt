package com.localtg.ui

import androidx.compose.runtime.*
import com.localtg.AppContainer
import com.localtg.data.*

internal fun slideOrderLabel(order: String?) = when (order) { "reverse" -> "倒序"; "shuffle" -> "随机"; else -> "顺序" }

/** Uses precisely the same rows, chips, spacing and host as all other player options. */
@Composable
internal fun SlideshowMenu(c: AppContainer, ui: ViewerUi, source: SlideSource?) {
    val state = ui.slides
    val settings by c.settings.state.collectAsState()
    val types = source?.scope?.mediaTypes.orEmpty().split(',')
    val filter = when { "video" !in types -> "仅图片"; "photo" !in types && "gif" !in types -> "仅视频"; else -> "图片与视频" }
    MenuNote("从当前项开始 · $filter")
    MenuOptions(listOf("顺序播放" to (state.active && state.options?.order == "forward"), "倒序播放" to (state.active && state.options?.order == "reverse"), "随机播放" to (state.active && state.options?.order == "shuffle"))) { index ->
        if (!state.preparing) ui.startSlides(SlideshowOptions(settings.slideshowSeconds, listOf("forward", "reverse", "shuffle")[index]))
    }
    if (state.preparing) MenuNote("正在准备播放队列…")
    MenuDivider()
    MenuLabel("图片停留时间")
    val seconds = listOf(3, 5, 10, 15, 30, 60)
    seconds.chunked(3).forEach { row ->
        MenuChips(row.map { "${it}秒" }, row.indexOf(state.options?.imageSeconds ?: settings.slideshowSeconds)) { i ->
            c.settings.update { copy(slideshowSeconds = row[i]) }; state.interval(row[i])
        }
    }
    if (state.active) {
        MenuDivider()
        if (state.message.isNotEmpty()) MenuNote(state.message)
        if (!state.failed) MenuRow(if (state.playing) "暂停播放" else if (state.finished) "重新播放本轮" else "继续播放", arrow = false) { ui.toggleSlides(); ui.dismissMenus++ }
        MenuRow("下一项", arrow = false) { ui.nextSlide(); ui.dismissMenus++ }
        MenuRow("停止幻灯片", arrow = false) { ui.stopSlides(); ui.dismissMenus++ }
    }
}

internal fun slideshowSummary(ui: ViewerUi): String = when {
    ui.slides.preparing -> "准备中"
    !ui.slides.active -> "关闭"
    ui.slides.failed -> "加载失败"
    ui.slides.finished -> "已结束"
    !ui.slides.playing -> "已暂停"
    else -> slideOrderLabel(ui.slides.options?.order)
}
