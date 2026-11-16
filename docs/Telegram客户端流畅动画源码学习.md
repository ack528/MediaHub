# Telegram 客户端是怎么做到操作流畅、动画丝滑的 —— 源码学习笔记

> 读的是 Telegram **Android 官方源码**(GitHub `DrKLO/Telegram`,master 分支,2026-11-16 拉取,仓库最后一次推送 2026-11-12)。
> 文中的"文件:行号"指向这份源码里我实际读过的位置,行号随版本会漂移,按函数名搜更可靠。
> 没读 tdesktop(C++/Qt)和 iOS 版;文中不对它们做任何断言。
> 标注 **【推断】** 的是我根据源码做的解释或对本项目的建议,不是源码里写明的事实。
> 许可证:Telegram Android 是 GPL v2+。这份文档只讲思路和数字,**不要整段拷贝它的代码进本项目**。

---

## 0. 先说结论:它"丝滑"不是靠某一个黑科技

读完的整体印象是:Telegram 没有什么神奇框架,靠的是**一大批朴素、一致、反复执行的工程纪律**。归纳成十条:

| # | 纪律 | 一句话 |
|---|------|--------|
| 1 | 主线程只画不算 | 解码、网络、文件、排版全部丢到专用线程队列,主线程只拿结果 |
| 2 | 内存缓存命中必须同步 | 命中就在调用栈里直接设好图,没有"先空白再出现"的异步一帧 |
| 3 | 缩略图和大图分开排队 | 缩略图有独立线程,永远不会排在大图解码后面 |
| 4 | 动画由"目标值+时间"驱动,不是由"事件"驱动 | `AnimatedFloat`:任何时刻改目标,都从当前值接着走,天然可打断 |
| 5 | 时长由手势速度/距离算出来 | 松手后的收尾动画时长是 `距离/速度` 的函数,不是写死的 |
| 6 | 动画期间"让路" | 转场动画开始时锁住通知中心,别的界面更新被延后,动画结束再一次性放行 |
| 7 | 转场用"共享元素"而不是"淡入淡出" | 缩略图飞到全屏,所有属性(缩放/平移/裁剪/圆角)同一个进度线性插值 |
| 8 | 列表动画只在"该动"的时候开 | 首次加载、整表刷新时 `setItemAnimator(null)`,不白白做动画 |
| 9 | 远距离滚动不真滚 | 直接跳到目标,再用旧视图/新视图做平移动画,假装滚过去了 |
| 10 | 按设备档次降级 | CPU 核数/内存/主频分高中低三档,低端机自动关掉重效果 |

下面逐条展开,最后给一张"对 MediaHub 可以怎么借鉴"的对照表(第 12 节)。

---

## 1. 线程模型:主线程只负责"画"

### 1.1 自己的队列类,不是线程池乱开

`messenger/DispatchQueue.java` 非常小(约 150 行):**一条线程 + 一个 Looper + 一个 Handler**。要点:

- 构造时可以指定线程优先级(`Process.setThreadPriority`)。
- `postRunnable(r, delay)`、`postToFrontRunnable`(插队)、`cancelRunnable`(从 Handler 队列里摘掉)、`cleanupQueue`。
- 内部用 `CountDownLatch syncLatch` 等线程里的 Handler 建好才放行,避免"线程还没起来就 post"的竞态。
- 记录 `lastTaskTime`,给线程池做"闲置回收"用。

`messenger/Utilities.java:40-47` 一次性声明了**按职责划分的全局队列**,每个都是独立线程,互不阻塞:

```
stageQueue        globalQueue       cacheClearQueue   searchQueue
phoneBookQueue    themeQueue        externalNetworkQueue   videoPlayerQueue(按需创建)
```

好处很具体:搜索再慢不会拖主题切换,清缓存再慢不会拖图片加载。

### 1.2 线程池也会"收缩"

`DispatchQueuePoolBackground.java`:一组 `DispatchQueue`,忙/闲分开记录;`cleanupRunnable` 每 30 秒扫一遍,**闲置超过 30 秒的线程直接 `recycle()` 退出**。常驻线程少,内存和调度开销小。

### 1.3 带优先级的单线程队列

`DispatchQueuePriority.java`:`ThreadPoolExecutor(1,1)` + `PriorityBlockingQueue`,比较器按 `priority` 倒序。额外有 `pause()/resume()`(用 `CountDownLatch` 卡在 `beforeExecute`),可以**整条队列暂停**。图片磁盘读取队列(`cacheOutQueue`)就是它。

### 1.4 主线程回调的惯用法

`AndroidUtilities.runOnUIThread(r, delay)` 随处可见,配套 `cancelRunOnUIThread`。**所有后台结果都通过它"落"回主线程**,而不是在后台直接动 View。

### 1.5 位图回收也要绕一圈

`AndroidUtilities.recycleBitmaps`(`AndroidUtilities.java:894`):

1. SDK ≤ 23 直接不回收(注释说 Skia 会崩,交给 GC)。
2. 否则先把位图包成 `WeakReference`,**先 post 到主线程,再 post 到 `globalQueue`** 去 `recycle()`。

绕这一圈的目的是:主线程可能正好还有一帧在引用这张图,先让这一帧画完,再在后台回收,避免"使用已回收的位图"崩溃,也不在主线程做释放。

> **【推断】对本项目**:Compose/WebView 里位图生命周期由 Coil/WebView 管,不需要手工 recycle;但"后台算、主线程落地"的纪律同样适用(我们 5.2.5 把 `AppJson` 解析挪出主线程就是这个道理)。

---

## 2. 图片管线:缩略图为什么永远"秒出"

这是整个应用手感的地基。涉及 `ImageLoader.java`(22 万字节)和 `ImageReceiver.java`(14.7 万字节)。

### 2.1 角色分工

- **`ImageReceiver`**:绑在每一个"需要显示图的位置"上的小对象,**不是 View**,而是一个可以 `draw(canvas)` 的绘制代理。聊天气泡、头像、列表项里的图,都是它。
- **`ImageLoader`**:全局单例,负责找缓存、排队、解码、网络。
- 二者通过 `loadImageForImageReceiver(receiver)` 对接。

### 2.2 内存缓存:先查三层,命中就同步返回

`ImageLoader.java:3350-3480`(`loadImageForImageReceiver`)的流程:

1. 依次取 **media key → image key → thumb key**(从大到小,优先用最清晰的)。
2. 每个 key 在 `memCache` → `smallImagesMemCache` → `wallpaperMemCache`(以及动画贴纸专用的 lottie 缓存)里找,命中就 `moveToFront`(LRU 提前)。
3. 命中:`cancelLoadingForImageReceiver(...)` 取消还在排队的任务,然后 **`imageReceiver.setImageBitmapByKey(drawable, key, type, /*memCache=*/true, guid)` 同步设置**,直接 `return`。

`memCache=true` 这个参数很关键,后面第 2.5 节会讲:它决定**不做渐变**。

也就是说:列表快速滑回来,已经解码过的缩略图**在同一个调用栈里就变成当前图**,下一次 `onDraw` 就是成图,不存在"空白 → 淡入"。

### 2.3 内存缓存有多大?出乎意料地小

`ImageLoader.java:2083-2150`(构造函数):

```
memoryClass >= 192  → maxSize = 30,否则 15        (单位 MB)
cacheSize = min(maxSize, memoryClass / 7) MB
memCache            = cacheSize × 80%
smallImagesMemCache = cacheSize × 20%
wallpaperMemCache   = cacheSize / 4
lottieMemCache      = 512×512×2×4×5 字节(约 10MB)
```

**普通图片缓存最多 30MB**。能这么小还流畅,原因是:

- 列表里放的是**按显示尺寸解码的小图**(见 2.4),不是原图;
- 缩略图本身又有"内嵌微缩图(stripped)"兜底。

### 2.4 解码:按目标尺寸降采样,低端机用 RGB_565

`ImageLoader.java:1285-1340`、`1459-1461`、`1828`:

- 先 `inJustDecodeBounds=true` 读出原图宽高;
- 按目标过滤尺寸(`w_filter`/`h_filter`)算 `scaleFactor`;`< 1.2` 视为 1;
- 取 **2 的幂** 作为 `inSampleSize`(`do { sample *= 2 } while (sample*2 < scaleFactor)`);
- 色彩格式:`force8888 ? ARGB_8888 : RGB_565`。`canForce8888` 只有 `memoryClass >= 192` 才为真;头像等圆形图、`deviceIsHigh()` 设备才用 8888。

RGB_565 一个像素 2 字节,是 ARGB_8888 的一半;同样缓存大小能放两倍的图,解码也更快。

### 2.5 缩略图有独立线程

`ImageLoader.java:129-132`:

```
cacheOutQueue       DispatchQueuePriority   (大图磁盘读取+解码,带优先级)
cacheThumbOutQueue  DispatchQueue2          (缩略图专用线程)
imageLoadQueue      DispatchQueue2          (调度/去重/发起网络)
thumbGeneratingQueue                         (生成缩略图,Thread.MIN_PRIORITY)
```

`ImageLoader.java:3270-3275`:`thumb != 0` 时任务投到 `cacheThumbOutQueue`,否则投到 `cacheOutQueue` 并带 `priority`。**缩略图永远不会排在一张 4000×3000 原图解码后面。** 这一条对"滑动列表时缩略图是否跟手"影响极大。

发起加载本身也分优先级:`imageLoadQueue.postRunnable(op, 低优先级?0:1)`,同时把这个 Runnable 记到 `imageReceiver.addLoadingImageRunnable`,**ImageReceiver 换图/脱离屏幕时可以把还没执行的任务从队列里摘掉**(`cancelLoadingForImageReceiver`,`ImageLoader.java:1963-1977` 附近)。滑过去的格子不会再白白解码。

### 2.6 去重:同一张图只加载一次

`imageLoadingByKeys` / `imageLoadingByUrl` 两张表;`img.addImageReceiver(...)` 允许**多个 ImageReceiver 共用同一个加载任务**。聊天里同一个头像出现一百次,只解码一次。

### 2.7 渐变(crossfade)只在"异步到达"时才做

`ImageReceiver.java:2876`:

```
if (allowCrossFade && isVisible && (!memCache && !forcePreview || forceCrossfade) && crossfadeDuration != 0) { …渐变… }
```

- `memCache == true`(缓存同步命中)→ **不渐变**,直接显示;
- 真的是异步到达(磁盘/网络)→ 150ms 渐变(`DEFAULT_CROSSFADE_DURATION = 150`,第 226 行);
- `isVisible == false`(不在屏幕上)→ 不渐变,省掉无意义的重绘。

渐变进度推进(`checkAlphaAnimation`,`ImageReceiver.java:1835`):

- 主线程绘制:`currentAlpha += 16f / crossfadeDuration`(**按"一帧 16ms"累加,不读时钟**);
- 后台线程绘制(`BackgroundThreadDrawHolder`):`dt = now - lastUpdateAlphaTime`,首帧给 16ms,刷新率 > 60Hz 时把 `dt` 封顶 30ms,避免卡一下后渐变突然跳完;
- 没到 1 就 `invalidate()` 继续要下一帧,到 1 释放 `crossfadeImage`。

> 注意:按帧累加意味着"掉帧时渐变会变慢但不会跳",这是它有意的选择。

### 2.8 脱离屏幕时"备份参数"而不是持有位图

`ImageReceiver.onDetachedFromWindow`(`:1103`):把当前的 location/filter/thumb/size 等参数存进 `setImageBackup`,并**取消通知观察者**;`onAttachedToWindow`(`:1190`)里 `setBackupImage()` 再按备份参数重新 `loadImage`(此时多半内存缓存命中,同步出图)。

所以列表项被回收后不占位图内存,滚回来又能立刻恢复。

### 2.9 动图在重动画期间暂停

`ImageReceiver.java:3159-3190`:收到 `stopAllHeavyOperations` 通知时,把重型 Lottie/视频贴纸 `stop()`;收到 `startAllHeavyOperations` 再 `start()`。这是第 8 节"动画期间让路"在图片层的体现。

> **【推断】对本项目**:`WebMedia` 的磁盘缓存 + `warmThumb` 同步放内存缓存,已经对应了 2.2/2.5 的核心;下一步可借鉴的是 2.5(缩略图独立队列/优先级)和 2.7(命中缓存不渐变)。

---

## 3. 动画原语:几个极小但到处复用的工具

### 3.1 `CubicBezierInterpolator`:一套命名好的曲线

`ui/Components/CubicBezierInterpolator.java:12-26`:

```
DEFAULT          (0.25, 0.10, 0.25, 1)     CSS 的 ease
EASE_OUT         (0,    0,    0.58, 1)
EASE_OUT_QUINT   (0.23, 1,    0.32, 1)     ← 全应用最常用的"收尾"曲线
EASE_IN          (0.42, 0,    1,    1)
EASE_BOTH        (0.42, 0,    0.58, 1)
EASE_OUT_BACK    (0.34, 1.56, 0.64, 1)     带回弹
Emphasized / EmphasizedDecelerate(0.05,0.7,0.1,1) / EmphasizedAccelerate(0.3,0,0.8,0.15)   Material 3 风格
StandardDecelerate (0,0,0,1)
```

`Easings.java` 另外给了 Sine/Quad 系列。**所有动画都从这套有限的曲线里挑**,不是每个界面自己调一条,所以整个应用的"运动语言"一致。

`ChatListItemAnimator.DEFAULT_INTERPOLATOR`(聊天列表和键盘共用)是一条手调的曲线:`(0.1992, 0.0106, 0.2792, 0.9103)`,起步快、后段缓,时长 250ms。

### 3.2 `AnimatedFloat`:Telegram 最核心的一个类(只有 200 行)

`ui/Components/AnimatedFloat.java`。用法约定写在注释里:**`set(target)` 必须在 `onDraw` 里调**。机制:

```text
set(target):
    首次调用 或 duration<=0 或 force → 直接 value = target(不动画)
    否则如果 |target - 当前target| > 0.0001:
        startValue = 当前 value     ← 关键:从"此刻的值"出发
        targetValue = target
        transitionStart = elapsedRealtime()

getValue():
    t = clamp((now - start - delay) / duration, 0, 1)
    value = lerp(startValue, targetValue, interpolator(t))
    t < 1 就 parent.invalidate()      ← 动画由"绘制→invalidate"自己驱动
```

这套设计带来的性质:

1. **随时可打断,且不会跳变**:动画进行到一半改目标,`startValue` 取当前值,曲线从当前位置重新出发(速度会重置,但位置连续)。
2. **不需要 ValueAnimator,不需要生命周期管理**:没有"忘了 cancel"、没有监听器泄漏;View 销毁就自然停了。
3. **状态机变成一行**:`float alpha = animatedAlpha.set(visible ? 1 : 0)`,开关随便切。
4. **不动的时候零开销**:`transition=false` 时 `getValue()` 直接返回,不再 `invalidate`。
5. **用 `SystemClock.elapsedRealtime()`**:时间驱动而不是帧驱动,掉帧也按真实时间走完。

`ActionBarLayout.java:1190` 就有 `new AnimatedFloat(this, 280, EASE_OUT_QUINT)` 这种用法。

> **【推断】对本项目**:Compose 里的 `Animatable.animateTo` / `animate*AsState` 本质就是同一件事(从当前值重定向目标),我们 5.2.4 做"动画打断"时已经用这个思路。Web 端(React)里可以用 CSS transition(同样从当前计算值重定向)获得同样效果,**避免用"状态+setTimeout"自己拼**。

### 3.3 `ButtonBounce`:按下缩小、松手回弹

`ui/Components/ButtonBounce.java`:

- 按下:`ValueAnimator(pressedT → 1)`,**60ms**,`CubicBezierInterpolator.DEFAULT`;
- 松开:`ValueAnimator(pressedT → 0)`,**350ms**,`OvershootInterpolator(overshoot=5.0)`(默认),可带 `releaseDelay`;
- 新动画开始前 `cancel()` 旧动画,起点取当前 `pressedT`(同样"从此刻出发");
- `getScale(diff) = ((1-diff) + diff*(1-pressedT)) * bounceScale`,`bounce()` 另有 .9→1 的 320ms 弹一下。

**按下极快(60ms)、松开偏慢且回弹**——按下要"立刻响应",松开要"有弹性",这个不对称是手感的来源。

### 3.4 弹簧几乎不用

在我读的文件里,`SpringAnimation/SpringForce` 只出现在 `ChatActivity`(气泡右滑回复的小图标显隐,`STIFFNESS_MEDIUM` + `DAMPING_RATIO_NO_BOUNCY`,第 4634 行附近)和 `PhotoViewer` 的 import。**Telegram 的主要运动不是弹簧物理,而是"贝塞尔曲线 + 由速度算出来的时长"**。想要"像 Telegram"的手感,不必上弹簧库。

---

## 4. 列表:RecyclerView 的几处关键改造

### 4.1 自带改过的 `DefaultItemAnimator`

仓库里直接放了 `androidx/recyclerview/widget/DefaultItemAnimator.java`(改过的副本),`ChatListItemAnimator extends DefaultItemAnimator`(`ui/recyclerview/ChatListItemAnimator.java`,8 万字节):

- `DEFAULT_DURATION = 250`,`translationInterpolator = DEFAULT_INTERPOLATOR`(第 74 行);
- `alwaysCreateMoveAnimationIfPossible = true`:**位置变了就一定做移动动画**,哪怕系统觉得没必要;
- `setSupportsChangeAnimations(false)`:内容变化不做"旧视图淡出新视图淡入"那种默认动画,避免闪一下;
- `runPendingAnimations`:把"移除/移动/添加/变化"四类待执行动画**分阶段**编排(先删、再移、再加),并区分两种入场:
  - 新消息出现在底部 → `runMessageEnterTransition`(从下方平移进来,按新增项总高度算位移,`addedItemsHeight`);
  - 其它 → `runAlphaEnterTransition`(淡入);
- 同时起一个 `ValueAnimator(0→1)`,时长 = 移除时长 + 移动时长,每帧回调 `activity.onListItemAnimatorTick()`,**让列表之外的叠加层(浮动日期、模糊背景、输入框)跟着同步重绘**。

`DialogsItemAnimator`(会话列表)则简单得多:删除/变化各 **180ms**,`DecelerateInterpolator`。

### 4.2 动画不是一直开的

`ChatActivity.java:39333-39338`:

```
notifyDataSetChanged(animated):
    if (animated && fragmentOpened) → setItemAnimator(chatListItemAnimator)
    else                            → setItemAnimator(null)
```

另外 `:30638` 等多处在整表刷新/定位时 `setItemAnimator(null)`。
**页面还没打开完(`fragmentOpened` 为假)、首次填充数据时不做列表动画**——否则进页面时一堆 item 一起飞入,既慢又丑。

### 4.3 远距离滚动:跳过去 + 假动画(`RecyclerAnimationScrollHelper`)

点"回到最新消息"或跳到很远的位置时,真的滚过去要经过成百上千个 item,必然卡。Telegram 的做法(`RecyclerAnimationScrollHelper.java`,约 380 行):

1. 记下当前所有可见子 View(`oldViews`),并用**稳定 id**(`adapter.getItemId`)建表;
2. 禁用滚动(`setScrollEnabled(false)`),让每个气泡 `setAnimationRunning(true, …)`(冻结自身布局变化);
3. **直接 `scrollToPositionWithOffset` 跳到目标** + `notifyDataSetChanged`,等下一次 layout;
4. 在 `OnLayoutChangeListener` 里取到"新可见的 View"(`incomingViews`);用稳定 id 对比,找出两边都有的 View 以算偏移(`scrollDiff`);
5. 把还没有 parent 的旧 View **重新 `addView` 回去并 `layoutManager.ignoreView`**(让布局管理器别管它们),
6. 一个 `ValueAnimator(0→1)`:旧 View `translationY = ∓scrollLength × value`,新 View `translationY = scrollLength × (1-value)`(只对屏幕内的旧 View 设置,屏幕外的跳过);
7. 动画结束:`translationY` 清零、`stopIgnoringView`、`removeView`、恢复滚动、恢复滚动条。

**时长**(`:316-345`):

- 聊天:有相同 View(跳得近)→ `600ms`;否则 `((scrollLength / 列表高度) + 1) × 200`,下限 300、上限 1300,曲线 `EASE_OUT_QUINT`;
- 会话列表:有相同 View → `150ms` + `EASE_OUT`;否则类似上面的公式。

还有保险:`fastScrollAnimationRunning` 为真时直接 `return`;列表在跑 item 动画时不开始;用户关了"视图动画"设置或没有子 View 时退化成普通 `scrollToPosition`。

> **【推断】对本项目**:`DateScrubber`/日期跳转(跳到很远的日期)可以借鉴"先定位到目标窗口,再用平移过渡"而不是真滚;目前我们是窗口化渲染(`Chunk`),本来就是"跳到目标窗口"的结构,只缺过渡动画。

### 4.4 滚动中"少干活",停下来再补

`ChatActivity.java:6823-6870`(滚动监听):

- `SCROLL_STATE_IDLE`:`updateVisibleRows()`(把可见行补成完整状态)、恢复 `OVER_SCROLL_ALWAYS`、发 `startSpoilers`;低端机(`PERFORMANCE_CLASS_LOW`)还在此刻置 `scrolling = true` 并补发 `startAllHeavyOperations`;
- `DRAGGING/SETTLING`:隐藏浮动日期、标记 `scrollingChatListView`;低端机在滚动开始时置 `scrolling = false`(这两个标志后续怎么用我没追下去,推测用于"滚动期间不跑重操作");
- 气泡的"剧透遮罩"(spoiler 粒子动画)在 `getScrollState() != IDLE` 时 `setSpoilersSuppressed(true)`(第 35933 行等多处)——**滚动时不跑装饰性动画**。

### 4.5 气泡是"单个自绘 View",不是 View 树

`ui/Cells/ChatMessageCell.java` 有 **134 万字节、1000 多处绘制/布局相关调用**。它的 `photoImage`、`avatarImageReceiver` 都是 `ImageReceiver`(不是 `ImageView`),`onAttachedToWindow/onDetachedFromWindow` 里转发给它们(`:990-999`、`:5826`)。整个气泡在一个 `onDraw` 里用 Canvas 画完。

- 好处:每个 item 只有 1 个 View,`measure/layout` 成本极低,**View 层级是平的**;
- 动画期间的保护:`setAnimationRunning(true)` 后,触摸被忽略(`:4358` `|| animationRunning`)、布局重算被跳过(`:17574` `!wasLayout && !animationRunning`),避免动画中途重排抖动。

> **【推断】对本项目**:这就是为什么原生 Telegram 的列表比 WebView 列表"跟手"的结构性原因之一。Web 端对应的缓解手段:减少每个 item 的 DOM 节点数、用 `transform/opacity` 做动画(只触发合成)、`content-visibility`/窗口化(我们已有 `Chunk`)、避免滚动期间的布局抖动。

---

## 5. 页面导航:`ActionBarLayout` 的滑动返回

Telegram 自己实现了 Fragment 栈和转场(`ui/ActionBar/ActionBarLayout.java`,16.8 万字节),不依赖 Android 的 Fragment 动画。

### 5.1 双容器 + 视差

有两个容器:`containerView`(当前页)和 `containerViewBack`(下面那页)。手指拖动时:

```
containerView.setTranslationX(dx)       // 当前页跟手
setInnerTranslationX(dx)                // 下面那页做视差位移(幅度更小)
```

### 5.2 手势判定的具体数字

`ActionBarLayout.java:1549`:

```
开始跟踪条件: dx >= 0.4cm(AndroidUtilities.getPixelsInCM(0.4f))  且  |dx| / 3 > dy
              (向右拖动 0.4 厘米以上,而且横向位移是纵向的 3 倍以上 —— 过滤掉斜着滑和竖着滚)
```

- 用 **厘米**(物理尺寸)而不是 dp/px 来定阈值,不同屏幕密度手感一致;
- `ACTION_UP` 时用 `VelocityTracker.computeCurrentVelocity(1000)`;
- **快速甩动也能触发**:没开始跟踪但 `velX >= 3500 && velX > |velY|` 时直接 `prepareForMoving()` 进入跟踪;
- **完成 or 取消**(`:1598`):

```
backAnimation(= 取消返回) = (x < 宽度/3) && (velX < 3500 || |velX| < |velY|)
```

即:拖过 1/3 宽度 → 完成返回;没过 1/3 但甩得够快(≥3500px/s 且主要是横向)→ 也完成。

### 5.3 松手后的收尾动画:时长由距离算

`animateBackEndAnimation`(`:1710-1745`):

```
完成返回:  duration = max( 200 / 宽度 × 剩余距离 , 50 )  ms     (非预测式返回)
取消返回:  duration = max( 320 / 宽度 × 已拖距离 , 120 ) ms
同时对 containerView.TRANSLATION_X 和 innerTranslationX 做 ObjectAnimator
预测式返回(Android 13+):EASE_OUT_QUINT,下限 380 / 320 ms
```

**剩得越少动得越短**——不会出现"明明只差一点点却还要走 200ms"的拖沓。

### 5.4 拖动期间开硬件层

`:1514`:`containerView.setLayerType(LAYER_TYPE_HARDWARE, null)`;结束时(`:1464`、`:1531`、`:1606`、`:1618`)`LAYER_TYPE_NONE`。**拖动/转场期间把整个页面栅格化成一张纹理,GPU 只做平移**,不再逐帧重绘子 View;动画一结束立刻释放(硬件层占显存)。

### 5.5 `ViewPagerFixed`(标签页横滑)的速度驱动时长

`ui/Components/ViewPagerFixed.java:730-820`:

```
惯性曲线:  t' = t-1;  y = t'^5 + 1                (五次方 ease-out,起步极快、收尾极缓)
fling 触发: |velX| >= 3000 且主要横向
方向判定:  |velX| > 1500 时看速度方向,否则看位移是否过 1/3 宽度
时长:      duration = 4 × round(1000 × |distance / velX|),夹在 [150, 600] ms
           (没有速度时 duration = (pageDelta + 1) × 100)
点标签切换: 540ms,EASE_OUT_QUINT
```

`distance` 里还掺了 `distanceInfluenceForSnapDuration`(类似 Android 老 ViewPager 的"吸附时长"公式)。整体思路:**用手指松开时的速度来决定动画时长,让动画看起来像手势的延续,而不是另起一段动画**。

> **【推断】对本项目**:这正是我们 5.2.1 里"左右滑动群组"要做到的目标。`Folders.tsx` 的 `commit()` 现在是固定时长的两阶段动画;改成"按松手速度和剩余距离算时长、夹在 150~600ms"、曲线用五次方 ease-out(`cubic-bezier(0.23,1,0.32,1)` 即 EASE_OUT_QUINT 可近似),应该更贴手。

---

## 6. 共享元素转场:PhotoViewer 打开/关闭

Telegram 的图片查看器(`ui/PhotoViewer.java`,**121 万字节**)是单独一个 `WindowManager` 窗口(`wm.addView(windowView, …)`,`type = LAST_APPLICATION_WINDOW`,第 17442 行附近),叠在整个应用上面。

### 6.1 数据:`PlaceProviderObject`

打开前,调用方通过 `placeProvider.getPlaceForPhoto(...)` 返回一个对象(`PhotoViewer.java:2730`),里面带:
源 `ImageReceiver`、源 View 在窗口里的位置 `viewX/viewY`、缩放 `scale`、缩略图位图 `thumb`、圆角 `radius`、`clipTopAddition/clipBottomAddition`、父容器 `parentView`、是否 `fadeIn`、`keepImageReceiverVisible` 等。

### 6.2 `ClippingImageView` + `animationValues[2][13]`

用一个专用的图片视图 `animatingImageView` 做"飞行的图片"。动画值是一个 `float[2][13]` 数组:**第 0 行起点,第 1 行终点**,13 列依次是:

```
0 scaleX   1 scaleY   2 translateX   3 translateY
4 clipHorizontal   5 clipTop   6 clipBottom
7~10 四个圆角半径
11 imageY(clipVertical)   12 imageX(clipHorizontal)
```

`ClippingImageView.setAnimationProgress(p)`(`ClippingImageView.java:104`)对**每一列做线性插值**:

```
scale、translate、clip、radius、image offset = start + (end - start) × p
fade 时 alpha = in ? p : 1 - p
```

也就是说,**只有一个进度 p(0→1),所有属性共用它**。缩放、位移、裁剪边、圆角按同一节奏变化,所以看起来是一个整体的"形变",而不是几个动画拼在一起。

关键细节:

- `pivot = (0,0)`,先算好 `scale`/`translate` 再设置(`:17660` 起),让变换矩阵简单;
- **裁剪值来自源列表的可见区域**(`:17712-17725`):源格子如果被列表边缘(顶栏/输入框)盖住一部分,飞行中的图片**同样只显示没被盖住的那一部分**,然后随进度把裁剪收缩到 0。这就是"从被半遮挡的缩略图展开"也不穿帮的原因;
- 终点 `scale = min(窗口宽/图宽, 窗口高/图高)`,居中。

### 6.3 开始时机:`OnPreDrawListener`

不是 `onClick` 里立刻开动画,而是 `windowView.getViewTreeObserver().addOnPreDrawListener(…)`(`:17657`):**等新窗口完成首次 measure/layout、拿到真实尺寸后**,在 `onPreDraw` 里算出 `animationValues`、`setAnimationProgress(0)`、把背景 alpha 设 0、容器 alpha 设 0,然后才起动画。保证第 0 帧就与源缩略图**像素对齐**。

### 6.4 动画本体

```
AnimatorSet.playTogether(
    ofFloat(animatingImageView, CLIPPING_IMAGE_VIEW_PROGRESS, 0→1),
    ofInt(backgroundDrawable, COLOR_DRAWABLE_ALPHA, 0→255),
    ofFloat(containerView, ALPHA, 0→1),
    ofFloat(navigationBar, ALPHA, 0→1) )
setDuration(200)        ← 打开 200ms,没有显式 setInterpolator
```

- 打开 **200ms**,没有额外设置插值器(用的是 `ObjectAnimator` 默认的先加速后减速);
- 关闭(`:18500-18560`):我读到的这一支是 `containerView` 缩到 0.9、背景和容器 alpha → 0、导航栏 alpha → 0,**200ms**;这一支应当是没有可飞回的源位置时的路径,**有源位置时的"飞回"分支我没有逐行读**,不下结论;
- 动画期间 `containerView.setLayerType(LAYER_TYPE_HARDWARE, null)`(`:17745`),结束后 `LAYER_TYPE_NONE`(`:17595`)。

### 6.5 隐藏源缩略图的时机

`:17787-17792`:

```
backgroundDrawable.drawRunnable = () -> {
    disableShowCheck = false;
    if (!object.keepImageReceiverVisible) object.imageReceiver.setVisible(false, true);
};
```

**不是点击时就把源缩略图藏起来,而是等"飞行图片的背景第一次真正被画出来"的那一帧再藏**。这样源图和飞行图片之间不会出现"源图已消失、飞行图还没画出"的空白帧(闪一下)。结束时 `showAfterAnimation.imageReceiver.setVisible(true, true)` 恢复。

### 6.6 结束后才加载大图

`animationEndRunnable`(`:17731` 起)里才 `setImages()`(换成高清图)、`setCropBitmap()`、隐藏 `animatingImageView`。**飞行期间用的是已经在内存里的缩略图**(`animatingImageViews[i].setImageBitmap(object.thumb)`),不在动画中途去解码大图。

关闭时更进一步:`chatActivity.doOnIdle(animationEndRunnable)`(`:18560` 附近)——**收尾清理推迟到空闲时再做**,保证关闭动画最后一帧不被清理工作挤掉。

> **【推断】对本项目**:本项目的 `hero.ts`+`Hero.kt` 已经是同一套思路(缩略图垫底、源格子隐藏/显示)。可对照的几点:
> 1. 源格子隐藏的时机:是否在"飞行层首帧真正绘制后"才隐藏(Telegram 用 `drawRunnable`);
> 2. 飞行层是否按**源列表可见区域**裁剪(被顶栏遮住的缩略图展开时不应"穿过"顶栏);
> 3. 动画结束后才开始加载高清图/起播,并把清理工作放到空闲回调。

---

## 7. "让路":动画期间暂停别的更新(`NotificationCenter`)

这是 Telegram 里**最不起眼、但对流畅度贡献最大的机制之一**。

### 7.1 机制

`messenger/NotificationCenter.java`:应用内的事件总线。

- `AnimationNotificationsLocker.lock()` → `NotificationCenter.setAnimationInProgress(index, allowedIds)`(`NotificationCenter.java:463-486`):登记一次"有动画在跑",并(第一次时)广播 **`stopAllHeavyOperations`**(`512` 这个"层级"标志);
- 在动画进行期间,`postNotificationName`(`:598-625`)会判断:**这个通知是否在所有进行中动画的"允许列表"里**;不在 → **放进 `delayedPosts` 队列,先不派发**;
- 永远放行的少数几种:`startAllHeavyOperations`、`stopAllHeavyOperations`、`didReplacedPhotoInMemCache`、`closeChats`、`invalidateMotionBackground`、`needCheckSystemBarColors`、`messageReceivedByServer2`;
- `unlock()` → `onAnimationFinish(index)`(`:524-545`):计数归零时广播 `startAllHeavyOperations`,并 `runDelayedNotifications()` **把积压的通知一次性放出去**;
- 还有"过期保险"(`EXPIRE_NOTIFICATIONS_TIME` + `checkForExpiredNotifications`),防止某处忘了 unlock 导致 UI 永远不更新。

### 7.2 谁在用

- **页面转场**:`ChatActivity.onTransitionAnimationStart`(`:28095-28135`)——进入聊天时,只允许 `dialogsNeedReload / closeChats / botKeyboardDidLoad / needDeleteDialog`(首次还允许 `messagesDidLoad`)这几种通知,**其它所有 UI 更新(新消息、已读回执、头像更新…)全部延后到转场结束**;
- **PhotoViewer 打开**:`transitionNotificationLocker.lock()`(在 `runOnUIThread` 里紧挨着 `animatorSet.start()`),动画结束再 `unlock()`(`:17617-17630`);
- **键盘动画**:`AdjustPanLayoutHelper` 里有 `AnimationNotificationsLocker`(`AdjustPanLayoutHelper.java:68`);
- `ViewPagerFixed` 里也有一个。

### 7.3 效果

动画期间主线程**只做动画本身**,不会因为一条新消息到来而触发一次列表 `notifyDataSetChanged`、一次布局、一次图片加载。动画结束后再批量处理。

### 7.4 图片层的配合

`ImageReceiver` 订阅了 `stopAllHeavyOperations/startAllHeavyOperations`(`:1204-1206`),据此暂停/恢复动图和 Lottie(2.9 节)。

> **【推断】对本项目**:这是一条**可以直接落地、收益确定**的建议。我们的 `hero` 转场进行时(`Opening`/`Closing`),网页侧还可能在:收到 `mediaDeleted` 后改列表、`history` 请求返回后 `setItems`(5.2.6 刚加的缓存先显示/后更新)、`useShortVideoPreload` 触发的 `video.preload`、缩略图解码。可以做一个极简的"转场期间锁"——`hero` 进入 `Opening/Closing` 时设置 `animating=true`,期间把 `setItems/setTotal` 之类的**非用户触发的状态更新攒起来**,`viewerClosed/Open 完成` 后再统一应用。

---

## 8. 键盘弹出:`AdjustPanLayoutHelper`

`ui/ActionBar/AdjustPanLayoutHelper.java`(约 15KB)。系统默认的键盘弹出是"瞬间改窗口高度",聊天内容会跳一下。Telegram 的做法:

- 常量:`keyboardDuration = 250`,`keyboardInterpolator = ChatListItemAnimator.DEFAULT_INTERPOLATOR`(**和聊天列表动画同一条曲线**);
- 在 `ViewTreeObserver.OnPreDrawListener`(`:75` 起)里检测 `parent.getHeight()` 变化:
  - 变化 < 20dp 或动画已在跑 → 忽略;
  - 变化明显 → `animateHeight(previousHeight, contentHeight, isKeyboardVisible)` 并 **`return false`(取消这一帧的绘制)**,下一帧起由自己的 `ValueAnimator` 驱动视图位移,把"瞬间的高度变化"转成 250ms 的平滑位移;
- 动画期间用 `AnimationNotificationsLocker` 让路;
- 有 Android 11 的 `WindowInsetsAnimation` 分支(`USE_ANDROID11_INSET_ANIMATOR = false`,默认关闭,用的是自己的 OnPreDraw 方案)。

**关键点:`onPreDraw` 里拦截"尺寸突变",把它变成动画。** 这是一个非常通用的技巧。

---

## 9. 按设备档次降级(`SharedConfig`)

`messenger/SharedConfig.java:1634-1718`:

```
PERFORMANCE_CLASS_LOW / AVERAGE / HIGH
LOW:     cpuCount <= 2  或  memoryClass <= 100  或  (cpu<=4 且 主频<=1250MHz) 或 …(老系统+低配组合)
AVERAGE: cpuCount < 8  或  memoryClass <= 160 或 …
HIGH:    其余
```

读 `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` 取最大主频;结果存进偏好(`devicePerformanceClass`),并允许用户手动覆盖(`overrideDevicePerformanceClass`)。随后 `getDevicePerformanceClass()` 在各处用来**开关效果**(模糊、粒子、动图自动播放、滚动时的重操作策略等)。

**低端机不是"一起卡",而是"少做点"**。

> **【推断】对本项目**:本项目 WebView 部分可以读 `navigator.hardwareConcurrency`、`navigator.deviceMemory`(在 WebView 里可用)给出一个粗档位,低档时关掉缩略图渐变、减少 `will-change`、缩短动画。原生部分已有 `AppSettings` 里的动画/缓存设置,可以加一个"自动"默认值。

---

## 10. 其它值得记的小招

1. **代码建布局**:`PhotoViewer` 等处大量使用 `LayoutHelper.createFrame(...)` 直接用代码创建 LayoutParams(我只确认了这几处用法,没有统计全应用)。
2. **`BlurredRecyclerView` / `SizeNotifierFrameLayout` / `FlickerLoadingView`**(骨架屏):只看了文件大小和名字,**没读内容,不作结论**。
3. **`DispatchQueue` 是 Looper+Handler 事件驱动**(`run()` 里 `Looper.prepare()` 后 `Looper.loop()`),没有轮询。
5. **`NotificationCenter` 的观察者用弱引用组**(`createWeakObserversGroup`):`ImageReceiver` attach 时注册、detach 时 `removeAllObservers`,避免泄漏。
6. **`setImageBackup`(2.8)** 这一类"脱离屏幕就释放、回来就恢复"的模式贯穿各处。
7. **`ChatActivity` 打开时的 `fragmentOpened` 门闩**:转场未结束前,各种"需要动画"的操作都退化为无动画。

---

## 11. 数字速查表

| 场景 | 时长/阈值 | 曲线/说明 | 出处 |
|------|-----------|-----------|------|
| 按下缩小 | 60ms | DEFAULT `(0.25,0.1,0.25,1)` | ButtonBounce |
| 松开回弹 | 350ms | Overshoot(5.0) | ButtonBounce |
| `bounce()` | 320ms | Overshoot,0.9→1 | ButtonBounce |
| 图片渐变 | 150ms | 线性按帧累加 | ImageReceiver:226 |
| 聊天列表项动画 | 250ms | `(0.199,0.0106,0.279,0.910)` | ChatListItemAnimator |
| 会话列表删除/变化 | 180ms | Decelerate | DialogsItemAnimator |
| 键盘弹出 | 250ms | 同上聊天曲线 | AdjustPanLayoutHelper |
| 图片查看器打开/关闭 | 200ms | 默认(加减速) | PhotoViewer:17617 |
| 其它 PhotoViewer 切换 | 200~250ms | Decelerate(1.5) 等 | PhotoViewer |
| 滑动返回(完成) | `max(200/W×剩余距离, 50)`ms | 线性/默认 | ActionBarLayout:1710 |
| 滑动返回(取消) | `max(320/W×已拖距离, 120)`ms | 同上 | ActionBarLayout:1731 |
| 滑动返回起手 | 0.4cm 且 dx>3×dy | — | ActionBarLayout:1549 |
| 滑动返回完成阈值 | 1/3 宽度 或 vx≥3500 | — | ActionBarLayout:1598 |
| 标签页横滑 | `4×round(1000×距离/速度)`,夹 150~600ms | `t^5+1` | ViewPagerFixed:812 |
| 标签点击切换 | 540ms | EASE_OUT_QUINT | ViewPagerFixed:325 |
| 远距离滚动(聊天) | 600ms(近) / `((距离/H)+1)×200` 夹 300~1300ms | EASE_OUT_QUINT | RecyclerAnimationScrollHelper |
| 远距离滚动(会话) | 150ms(近) / 同上 | EASE_OUT | 同上 |
| 位图内存缓存 | `min(30 或 15, memClass/7)` MB;80% 常规 + 20% 小图 | LRU | ImageLoader:2088 |
| 线程闲置回收 | 30s | — | DispatchQueuePoolBackground |

---

## 12. 对 MediaHub 的对照与行动清单

> 本节是我的分析,不是 Telegram 源码里的内容。已做/可做按**收益/风险**排序。

### 12.1 已经和 Telegram 同思路的

| Telegram | MediaHub 现状 |
|----------|---------------|
| 缓存同步命中(2.2) | `WebMedia.warmThumb` 把缩略图同步放进 Coil 内存缓存;查看器第一帧有垫底图 |
| 磁盘缓存键稳定(2.6 去重/命中) | 5.2.5:缓存键不含内网改写地址 |
| 主线程只画不算(1) | 5.2.5:`WebBridge.call` 参数解析、`Item` 列表解码挪到 `Dispatchers.Default` |
| 动画可打断、从当前值出发(3.2) | 5.2.4:`hero.p` 是 `Animatable`,打断时 `animateTo` 重定向 |
| 点开前预热(Telegram 在 `onPreDraw` 前就备好 `thumb`) | 5.2.5:`onPointerDown` 时 `viewer.warm` |
| 窗口化渲染(只渲染屏幕附近) | `Chunk`/`useChunks` |
| 进入页面先显示缓存(Telegram 的消息先从本地库出) | 5.2.6:群组内存快照 + 磁盘缓存先显示 |

### 12.2 值得做、收益确定(建议优先)

1. **转场期间"让路"(第 7 节)**
   在 `hero.ts` 里加 `animating` 标志(`viewerOpening/viewerClosing` → true;`viewerClosed`/打开完成 → false),期间把 `setItems`、`mediaDeleted`、`video.preload`、缩略图预取等**非用户触发**的更新攒进队列,动画结束一次性放出。预期:消除"点开瞬间偶发卡顿"。
2. **源格子隐藏时机(6.5)**
   检查 `Hero.kt` 里 `viewerReveal`/隐藏源格子的时机,保证"飞行层首帧画出后再隐藏源格子"、"飞行层彻底消失前不显示源格子"。目前有 `revealNow` 与 150ms 等待路径,逻辑偏复杂,按 Telegram 的"以首帧绘制为信号"可以更干净。
3. **按松手速度算时长(5.5)**
   `Folders.tsx` 的群组横滑 `commit()`:时长 = `4×round(1000×剩余距离/速度)` 夹在 150~600ms,曲线用 `cubic-bezier(0.23,1,0.32,1)`;没有速度时 `(进度+1)×100`。
4. **命中缓存不渐变(2.7)**
   Web 端缩略图 `<img>`:若是缓存同步命中(`img.complete` 在 `onLoad` 之前就为真),不加淡入;只有真的异步加载完才 150ms 淡入。避免滚动时已缓存的图也"闪一下"。
5. **列表动画门闩(4.2)**
   页面首次填充、整表刷新、跳转定位时不触发任何 item 动画;只有"页面已打开 + 数据增量变化"才动画。

### 12.3 值得评估、需要实测

6. **缩略图独立优先队列(2.5)**:`WebMedia.proxy` 目前是 WebView 线程 → OkHttp 同步请求,缩略图和 `/srv/` 下的大图共用 OkHttp 的 `Dispatcher`。可按"路径是否缩略图"走独立的 `Dispatcher` 或限制大图并发,保证滑动时缩略图不被大图堵住。
7. **滚动停下再补(4.4)**:滚动中暂停 `useShortVideoPreload`(现在已是滚动停下 400ms 后检查,基本一致)、暂停装饰性动画。
8. **远距离跳转过渡(4.3)**:`DateScrubber` 拖到很远的日期时,先瞬间定位窗口,再做 250~600ms 的平移过渡。
9. **设备分级(9)**:`navigator.hardwareConcurrency/deviceMemory` 粗分三档,低档默认关渐变、缩短转场。
10. **`doOnIdle` 式收尾(6.6)**:关闭查看器动画最后一帧之后再做清理(`requestIdleCallback`)。

### 12.4 不建议照搬的

- **自绘列表/气泡**(4.5):属于"重写 UI 层"级别的工程,和本项目 WebView 架构冲突,不是改几行能借鉴的。
- **自建 Fragment 栈/转场**(5):Web 端用路由+CSS 已能达成,没必要再造。
- **位图手工回收**(1.5):Coil/WebView 自己管。

---

## 13. 本次阅读的边界与可信度

- **读过**(逐段):`DispatchQueue`、`DispatchQueuePriority`、`DispatchQueuePoolBackground`、`AnimatedFloat`、`CubicBezierInterpolator`、`Easings`、`ButtonBounce`、`AnimationNotificationsLocker`、`NotificationCenter`(动画锁与延迟派发部分)、`ImageLoader`(构造、缓存查找、解码采样、队列投递部分)、`ImageReceiver`(渐变、attach/detach、heavy 操作部分)、`RecyclerAnimationScrollHelper`、`ChatListItemAnimator`(开头与入场编排)、`DialogsItemAnimator`(常量)、`AdjustPanLayoutHelper`(前 120 行)、`ActionBarLayout`(滑动返回部分)、`ViewPagerFixed`(速度/时长部分)、`PhotoViewer`(打开动画与关闭动画骨架)、`ClippingImageView.setAnimationProgress`、`SharedConfig`(性能分级)、`ChatActivity`(列表动画门闩、滚动监听、转场通知过滤)。
- **只做了关键词检索、没有通读**:`ChatMessageCell`(只确认它是自绘并用 `ImageReceiver`、有 `animationRunning` 冻结)、`RecyclerListView`(只看到 FastScroll 等结构)、`DialogCell`、`BottomSheet`、`SharedMediaLayout`、`RLottieDrawable`。
- **没读**:网络层(MTProto)、消息排版(`MessageObject` 的文本布局是否在后台线程生成,我没验证)、视频播放器、`tdesktop`、iOS。
- 数字类结论(时长、阈值、缓存大小)都来自上面引用的行;"为什么这么设计"的部分如标 **【推断】** 即是我的解读。
- 行号基于 2026-11-16 拉取的 master;后续提交会使行号漂移。
