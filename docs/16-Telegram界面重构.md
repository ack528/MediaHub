# 16 · 界面按 Telegram Android 官方源码重构(过程记录)

> 这是 4.0.0 的第一版(通栏白条 + 灰色间隔)。最终版本改成了 Telegram 新版的圆角卡片 + 液态玻璃,以 docs/17 为准;本文的配色表仍然有效。

参考源码(本地克隆,`tools/refs/`,不进 git):

| 目录 | 项目 | 用途 |
| --- | --- | --- |
| `Telegram` | DrKLO/Telegram | 主要参考:配色(`ThemeColors.java`、`assets/darkblue.attheme`)、单元格尺寸(`ui/Cells/*.java`)、开关(`ui/Components/Switch.java`) |
| `Convx` | cosmictaserdev-creator/Convx | 辅助参考:Compose + 玻璃质感的界面写法 |
| `Square` | Lelonio/Square | 辅助参考:同上 |

```
git clone --depth 1 https://github.com/DrKLO/Telegram tools/refs/Telegram
git clone --depth 1 https://github.com/cosmictaserdev-creator/Convx tools/refs/Convx
git clone --depth 1 https://github.com/Lelonio/Square tools/refs/Square
```

范围:除播放器(VideoPlayer / VideoPlayerPanel)和查看器的动画外所有界面;动效本版本不改。

## 一、配色

以前用的是 Telegram 2013 年那套蓝色顶栏的近似色。现在白天取 `ThemeColors.java` 里的默认值(Telegram 当前的默认主题:白色顶栏),夜间取官方 `darkblue.attheme`(Dark Blue)。

| 项 | 白天 | 夜间 |
| --- | --- | --- |
| 顶栏 | `#FFFFFF`,标题 / 图标 `#1A1D21` | `#242D39`,白色 |
| 页面 | `#FFFFFF` | `#1D2733` |
| 设置页灰底(windowBackgroundGray) | `#F1F1F3` | `#151E27` |
| 分隔线 | `#D9D9D9` | `#95000000` |
| 小节标题(HeaderCell) | `#298ACF` | `#79C4FC` |
| 顶栏里选中的标签 / 下划线 | `#298ACF` | `#6ABFFF` |
| 标签未选中 | `#777C7F` | `#8B97A2` |
| 开关:关 / 开 | `#A6ADB3` / `#229AF0` | `#5D7484` / `#61A3D7` |
| 计数(静音)| `#BEC3C7` | `#3E5263` |
| 危险色 | `#CC4747` | `#EE686F` |

白色顶栏需要深色的状态栏 / 导航栏图标:`BarIconsEffect`(`tg/TgTheme.kt`)按主题设置;查看器是黑底,打开期间改成浅色图标,开始收回时换回主题的颜色。

## 二、单元格(`tg/TgCells.kt`)

取自官方源码的尺寸:

| Telegram | 本项目 | 尺寸 |
| --- | --- | --- |
| ShadowSectionCell | `TgSectionGap` | 灰色分组间隔 12dp,上下边缘各一道 3dp 的淡阴影 |
| HeaderCell | `TgHeaderCell` | 14sp 中等粗细、小节标题色,左右 21dp,上 15dp |
| TextSettingsCell | `TgTextCell` | 行高 ≥ 50dp、16sp,右侧当前值(强调色)、下面 13sp 灰色说明;分隔线从 20dp 开始 |
| TextCheckCell | `TgCheckCell` | 同上,右侧 `TgSwitch` |
| TextDetailSettingsCell | `TgDetailCell` | 13sp 灰色标题 + 16sp 值 |
| TextInfoPrivacyCell | `TgInfoCell` | 灰底、14sp 灰字,上 10dp 下 17dp |
| Switch | `TgSwitch` | 轨道 31×14dp 胶囊,滑块直径 20dp(比轨道大),滑块中心 7dp → 24dp |

## 三、各界面

| 界面 | 改动 |
| --- | --- |
| 设置(所有分类页) | 页面底灰色,每个小节是白底单元格,小节之间灰色间隔;`Header / NavRow / SwitchRow / ChoiceRow / ActionRow / InfoRow` 换成上面的单元格;窗口底色也是灰色,进入的系统转场开头不闪白 |
| 文件夹列表 | 白色顶栏、蓝色选中标签与下划线;计数改成 Telegram 的未读计数尺寸(高 23dp、全圆角) |
| 聊天 / 搜索 / 随机 | 顶栏随主题;标签选中色统一为蓝色 |
| 登录页 | 居中的大图标 + 标题 + 副标题(Telegram 欢迎页);小节标题统一 |
| 所有顶栏 | 白色顶栏底部一条细线 |

## 四、没动的

- 动画(转场、查看器、拖拽关闭、搜索面板):本版本不改。
- 播放器:不改。
- 聊天流 / 网格 / 瀑布流里的缩略图与气泡:沿用原样(聊天背景色也不变)。
