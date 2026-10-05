package com.localtg.render

import android.opengl.GLES20
import android.opengl.GLES30
import com.localtg.AppLog

/**
 * mpv "user shader"(//!HOOK / //!BIND / //!SAVE / //!WIDTH / //!HEIGHT / //!WHEN)格式的最小运行时,
 * 用来直接跑开源的 Anime4K(MIT)与 AMD FSR 1.0(MIT,agyild 的 mpv 移植)着色器。
 *
 * 支持的子集(够用即可,不是完整的 libplacebo):
 *  - 钩子 LUMA / MAIN / PREKERNEL;LUMA 阶段处理亮度平面,之后按"亮度替换"并回 RGB(BT.709 三个通道的反变换行都含 1,
 *    所以 RGB' = RGB + (Y' − Y) 精确成立,Anime4K 的 Clamp 也用同一个技巧);
 *  - BIND 的纹理在 GLSL 里提供 NAME_tex(pos) / NAME_texOff(off) / NAME_pos / NAME_pt / NAME_size / NAME_raw;
 *  - SAVE 保存中间结果(RGBA16F);WIDTH / HEIGHT / WHEN 的逆波兰表达式,可引用 NAME.w / NAME.h(含 OUTPUT、NATIVE);
 *  - 不支持计算着色器、LUT 纹理、textureGather(着色器里有 #ifdef 回退)。
 */
class MpvPass(
    val desc: String,
    val hooks: List<String>,
    val binds: List<String>,
    val save: String?,
    val width: List<String>?,
    val height: List<String>?,
    val whenExpr: List<String>?,
    val body: String,
) {
    var program = 0
    val stage: Int get() = when (hooks.firstOrNull()) { "LUMA" -> 0; "MAIN" -> 1; else -> 2 }
    /** 本 pass 的 HOOKED 指向哪张纹理(PREKERNEL 钩在 MAIN 之后,指向 MAIN)。 */
    val hookedName: String get() = when (val h = hooks.firstOrNull()) { null, "PREKERNEL" -> "MAIN"; else -> h }
}

object MpvShaderParser {
    fun parse(text: String): List<MpvPass> {
        val out = ArrayList<MpvPass>()
        var desc = ""; var hooks = ArrayList<String>(); var binds = ArrayList<String>()
        var save: String? = null; var width: List<String>? = null; var height: List<String>? = null; var whenE: List<String>? = null
        val body = StringBuilder()
        var started = false
        fun flush() {
            if (started && hooks.isNotEmpty() && body.isNotBlank()) {
                out.add(MpvPass(desc, hooks, binds, save, width, height, whenE, body.toString()))
            }
            desc = ""; hooks = ArrayList(); binds = ArrayList(); save = null; width = null; height = null; whenE = null
            body.setLength(0); started = false
        }
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.startsWith("//!")) {
                if (body.isNotBlank()) flush() // 上一段代码结束,新的 pass 开始
                started = true
                val sp = line.indexOf(' ')
                val key = if (sp < 0) line.substring(3) else line.substring(3, sp)
                val v = if (sp < 0) "" else line.substring(sp + 1).trim()
                when (key) {
                    "DESC" -> desc = v
                    "HOOK" -> hooks.add(v)
                    "BIND" -> binds.add(v)
                    "SAVE" -> save = v
                    "WIDTH" -> width = v.split(' ').filter { it.isNotEmpty() }
                    "HEIGHT" -> height = v.split(' ').filter { it.isNotEmpty() }
                    "WHEN" -> whenE = v.split(' ').filter { it.isNotEmpty() }
                    // COMPONENTS / OFFSET / TEXTURE 等:忽略(统一用 RGBA16F)
                }
            } else if (started) {
                body.append(raw).append('\n')
            }
        }
        flush()
        return out
    }
}

/** 逆波兰表达式求值。变量形如 NAME.w / NAME.h。 */
object Rpn {
    fun eval(tokens: List<String>, size: (String) -> Pair<Int, Int>?): Float {
        val st = ArrayDeque<Float>()
        fun pop() = st.removeLast()
        for (t in tokens) {
            when (t) {
                "+" -> { val b = pop(); val a = pop(); st.addLast(a + b) }
                "-" -> { val b = pop(); val a = pop(); st.addLast(a - b) }
                "*" -> { val b = pop(); val a = pop(); st.addLast(a * b) }
                "/" -> { val b = pop(); val a = pop(); st.addLast(a / b) }
                "%" -> { val b = pop(); val a = pop(); st.addLast(a % b) }
                ">" -> { val b = pop(); val a = pop(); st.addLast(if (a > b) 1f else 0f) }
                "<" -> { val b = pop(); val a = pop(); st.addLast(if (a < b) 1f else 0f) }
                "=" -> { val b = pop(); val a = pop(); st.addLast(if (a == b) 1f else 0f) }
                "!" -> { val a = pop(); st.addLast(if (a == 0f) 1f else 0f) }
                else -> {
                    val dot = t.lastIndexOf('.')
                    val num = t.toFloatOrNull()
                    if (num != null) st.addLast(num)
                    else if (dot > 0) {
                        val s = size(t.substring(0, dot)) ?: throw IllegalStateException("表达式引用了未知纹理 ${t.substring(0, dot)}")
                        st.addLast((if (t.substring(dot + 1) == "w") s.first else s.second).toFloat())
                    } else throw IllegalStateException("无法解析表达式记号 $t")
                }
            }
        }
        return st.lastOrNull() ?: 0f
    }
}

/**
 * 一串 mpv 着色器 pass 的执行器。输入是原始帧(RGBA 纹理),输出是处理后的 MAIN 纹理。
 * 一帧处理完后,中间纹理放回纹理池,最终结果纹理的所有权交给调用方。
 */
class ShaderChain(val name: String, private val passes: List<MpvPass>, private val pool: Gl.Pool, private val lumaProgram: Int, private val mergeProgram: Int) {
    private val ordered = passes.withIndex().sortedWith(compareBy({ it.value.stage }, { it.index })).map { it.value }
    val needsLuma = ordered.any { it.stage == 0 }
    private var broken = false

    /** 返回 null 表示没有任何 pass 生效(调用方直接用原图)。返回的纹理归调用方,用完要放回池。 */
    fun run(src: Gl.Tex, outW: Int, outH: Int): Gl.Tex? {
        if (broken) return null
        val tex = HashMap<String, Gl.Tex>()
        val owned = HashSet<Gl.Tex>()
        tex["MAIN"] = src; tex["NATIVE"] = src
        var executed = 0
        var lumaDirty = false
        try {
            if (needsLuma) {
                val y = pool.acquire(src.w, src.h)
                owned.add(y); tex["LUMA"] = y
                drawSimple(lumaProgram, src, y)
            }
            fun size(n: String): Pair<Int, Int>? = when (n) {
                "OUTPUT" -> outW to outH
                else -> tex[n]?.let { it.w to it.h }
            }
            var stageDone = 0
            for (p in ordered) {
                // 亮度阶段结束 → 把处理后的亮度并回 RGB,再进入 MAIN 阶段
                if (p.stage > 0 && lumaDirty && stageDone == 0) {
                    mergeLuma(tex, owned)
                    lumaDirty = false
                }
                stageDone = maxOf(stageDone, p.stage)
                val hooked = tex[p.hookedName] ?: continue
                if (p.whenExpr != null && Rpn.eval(p.whenExpr) { n -> if (n == "HOOKED") hooked.w to hooked.h else size(n) } <= 0f) continue
                val ow = p.width?.let { Rpn.eval(it) { n -> if (n == "HOOKED") hooked.w to hooked.h else size(n) }.toInt() } ?: hooked.w
                val oh = p.height?.let { Rpn.eval(it) { n -> if (n == "HOOKED") hooked.w to hooked.h else size(n) }.toInt() } ?: hooked.h
                if (ow < 1 || oh < 1 || ow > 8192 || oh > 8192) continue
                val binds = p.binds.map { b ->
                    b to (if (b == "HOOKED") hooked else tex[b] ?: throw IllegalStateException("pass「${p.desc}」绑定了不存在的纹理 $b"))
                }
                if (p.program == 0) p.program = Gl.program(buildFragment(p))
                val out = pool.acquire(ow, oh)
                Gl.target(out)
                Gl.use(p.program)
                binds.forEachIndexed { i, (n, t) ->
                    Gl.bindTex(i, t.id)
                    GLES20.glUniform1i(Gl.loc(p.program, "${n}_raw"), i)
                    GLES20.glUniform2f(Gl.loc(p.program, "${n}_pt"), 1f / t.w, 1f / t.h)
                    GLES20.glUniform2f(Gl.loc(p.program, "${n}_size"), t.w.toFloat(), t.h.toFloat())
                }
                Gl.draw()
                executed++
                val saveName = p.save ?: p.hookedName
                val old = tex.put(saveName, out)
                owned.add(out)
                if (old != null && old !== out && tex.values.none { it === old } && owned.remove(old)) pool.release(old)
                if (saveName == "LUMA") lumaDirty = true
            }
            if (lumaDirty) mergeLuma(tex, owned)
        } catch (e: Exception) {
            AppLog.w("enhance", "着色器链「$name」执行失败,已停用:${e.message}")
            broken = true
            owned.forEach { pool.release(it) }
            return null
        }
        val result = tex["MAIN"]
        owned.filter { it !== result }.forEach { pool.release(it) }
        return if (executed == 0 || result === src) null else result
    }

    private fun drawSimple(program: Int, src: Gl.Tex, dst: Gl.Tex) {
        Gl.target(dst)
        Gl.use(program)
        Gl.bindTex(0, src.id)
        GLES20.glUniform1i(Gl.loc(program, "uTex"), 0)
        Gl.draw()
    }

    /** 亮度被处理过(尺寸可能变大):RGB' = 双线性放大的 RGB + (Y' − Y(RGB放大))。 */
    private fun mergeLuma(tex: HashMap<String, Gl.Tex>, owned: HashSet<Gl.Tex>) {
        val main = tex["MAIN"] ?: return
        val luma = tex["LUMA"] ?: return
        val out = pool.acquire(luma.w, luma.h)
        Gl.target(out)
        Gl.use(mergeProgram)
        Gl.bindTex(0, main.id); GLES20.glUniform1i(Gl.loc(mergeProgram, "uMain"), 0)
        Gl.bindTex(1, luma.id); GLES20.glUniform1i(Gl.loc(mergeProgram, "uLuma"), 1)
        Gl.draw()
        owned.add(out)
        val old = tex.put("MAIN", out)
        if (old != null && old !== out && old !== tex["NATIVE"] && tex.values.none { it === old } && owned.remove(old)) pool.release(old)
    }

    private fun buildFragment(p: MpvPass): String {
        val sb = StringBuilder()
        sb.append("#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n")
        sb.append("in vec2 vPos;\nout vec4 outColor;\n")
        val names = LinkedHashSet<String>(p.binds)
        for (n in names) {
            sb.append("uniform sampler2D ${n}_raw;\nuniform vec2 ${n}_pt;\nuniform vec2 ${n}_size;\n")
            sb.append("vec4 ${n}_tex(vec2 pos) { return textureLod(${n}_raw, pos, 0.0); }\n")
            sb.append("#define ${n}_pos vPos\n")
            sb.append("#define ${n}_texOff(off) ${n}_tex(${n}_pos + ${n}_pt * vec2(off))\n")
        }
        // mpv 里 HOOKED 就是被钩住的那张纹理,着色器也常直接用它的本名(例如 MAIN_texOff):补上别名
        val hn = p.hookedName
        if ("HOOKED" in names && hn !in names) {
            for (suffix in listOf("tex", "texOff", "pos", "pt", "size", "raw")) sb.append("#define ${hn}_$suffix HOOKED_$suffix\n")
        }
        sb.append("#line 1\n")
        sb.append(p.body)
        sb.append("\nvoid main() { outColor = hook(); }\n")
        return sb.toString()
    }
}

/** 内置着色器源码:亮度提取、亮度并回、纯拷贝。 */
object ChainShaders {
    const val LUMA = """#version 300 es
precision highp float;
uniform sampler2D uTex;
in vec2 vPos;
out vec4 outColor;
void main() {
    vec3 c = texture(uTex, vPos).rgb;
    outColor = vec4(dot(c, vec3(0.2126, 0.7152, 0.0722)), 0.0, 0.0, 1.0);
}
"""
    const val MERGE = """#version 300 es
precision highp float;
uniform sampler2D uMain;
uniform sampler2D uLuma;
in vec2 vPos;
out vec4 outColor;
void main() {
    vec3 c = texture(uMain, vPos).rgb;                // 已被放大到亮度纹理的尺寸(硬件双线性)
    float y0 = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float y1 = texture(uLuma, vPos).r;
    outColor = vec4(c + (y1 - y0), 1.0);
}
"""
}
