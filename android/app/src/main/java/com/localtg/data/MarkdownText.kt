package com.localtg.data

/**
 * 把简单的 Markdown(更新说明 / GitHub Release 正文)整理成适合直接显示的纯文本:
 * 去掉标题的 #、引用的 >、加粗 / 代码标记、[文字](链接) 只留文字;列表项统一成 "• ",缩进的子条目缩进显示。
 * 以前这些符号会原样显示在更新对话框里(一堆星号、井号)。
 */
fun cleanMarkdown(s: String): String = s.lines().mapNotNull { raw ->
    var l = raw.trimEnd()
    if (l.isBlank()) return@mapNotNull null
    val indent = l.length - l.trimStart().length
    l = l.trimStart()
    l = l.replace(Regex("^#{1,6}\\s*"), "").replace(Regex("^>\\s?"), "")
    val bullet = Regex("^[-*]\\s+").containsMatchIn(l)
    if (bullet) l = l.replace(Regex("^[-*]\\s+"), "")
    l = inlineClean(l)
    when {
        bullet && indent >= 2 -> "    ◦ $l"
        bullet -> "• $l"
        else -> l
    }
}.joinToString("\n")

/** 行内标记:**加粗**、__加粗__、`代码`、[文字](链接)。 */
fun inlineClean(s: String): String = s
    .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    .replace(Regex("__(.+?)__"), "$1")
    .replace(Regex("`([^`]*)`"), "$1")
