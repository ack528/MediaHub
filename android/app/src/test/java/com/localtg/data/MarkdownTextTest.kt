package com.localtg.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTextTest {
    @Test
    fun stripsMarkers() {
        val md = "## 手机端 LocalBrowse 3.2.0\n- **修复:点转码**。分段 `hls/seg0.ts` 401\n  - 子条目 [链接](https://x.y)\n\n> 说明:不含 DLL\n完整见 [CHANGELOG.md](https://example.com)。"
        val want = "手机端 LocalBrowse 3.2.0\n• 修复:点转码。分段 hls/seg0.ts 401\n    ◦ 子条目 链接\n说明:不含 DLL\n完整见 CHANGELOG.md。"
        assertEquals(want, cleanMarkdown(md))
    }

    @Test
    fun plainTextUnchanged() {
        assertEquals("• 修复一个问题", cleanMarkdown("- 修复一个问题"))
        assertEquals("没有标记的一行", cleanMarkdown("没有标记的一行"))
    }
}
