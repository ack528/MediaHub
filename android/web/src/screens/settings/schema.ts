// 由 Compose 版设置页(SettingsScreen.kt)提取的设置项清单:键名与 AppSettings 字段一一对应。
import type { SettingRow } from "./types";

export const SETTING_PAGES: Record<string, SettingRow[]> = {
  "appearance": [
    {
      "type": "header",
      "title": "主题"
    },
    {
      "type": "choice",
      "key": "theme",
      "title": "颜色主题",
      "options": [
        [
          "dark",
          "深色"
        ],
        [
          "light",
          "浅色(默认)"
        ],
        [
          "system",
          "跟随系统"
        ]
      ]
    },
    {
      "type": "header",
      "title": "文字与头像"
    },
    {
      "type": "choice",
      "key": "fontScale",
      "title": "界面文字大小",
      "options": [
        [
          0.85,
          "85%"
        ],
        [
          1,
          "100%(默认)"
        ],
        [
          1.15,
          "115%"
        ],
        [
          1.3,
          "130%"
        ],
        [
          1.5,
          "150%"
        ]
      ],
      "desc": "在系统字号的基础上再缩放,只影响本应用"
    },
    {
      "type": "choice",
      "key": "avatarShape",
      "title": "头像形状",
      "options": [
        [
          "round",
          "圆角方形(默认)"
        ],
        [
          "circle",
          "圆形"
        ],
        [
          "square",
          "方形"
        ]
      ]
    },
    {
      "type": "header",
      "title": "屏幕"
    },
    {
      "type": "switch",
      "key": "forceHighRefresh",
      "title": "强制屏幕保持 120 Hz 及以上",
      "desc": "应用在前台时,让系统把屏幕固定在 120 Hz 及以上(同分辨率下刷新率 ≥ 120 的最高档,例如 120 / 144 Hz);屏幕没有这样的档位就不起作用。比自动刷新率更耗电;系统省电模式、过热降频、在系统设置里手动锁了刷新率时,系统仍可能覆盖"
    },
    {
      "type": "header",
      "title": "交互"
    },
    {
      "type": "choice",
      "key": "hapticLevel",
      "title": "震动反馈",
      "options": [
        [
          0,
          "关闭"
        ],
        [
          1,
          "轻"
        ],
        [
          2,
          "标准(默认)"
        ]
      ],
      "desc": "翻页、切换标签、开关、长按、保存完成、已经是第一个 / 最后一个等操作时轻微震动;不受系统「触感反馈」总开关影响,由这里决定"
    },
    {
      "type": "header",
      "title": "液态玻璃"
    },
    {
      "type": "choice",
      "key": "glass",
      "title": "底部导航的玻璃效果",
      "options": [
        [
          "on",
          "开(默认)"
        ],
        [
          "frosted",
          "只要毛玻璃"
        ],
        [
          "off",
          "关"
        ]
      ],
      "desc": "首页底部导航采样下面的内容,做模糊、饱和度和边缘折射(按住拖动时镜片还有色散)。卡顿时可以改成「只要毛玻璃」或关掉。拖动导航时如果检测到明显掉帧,本次运行会自动降一档(开 → 只要毛玻璃 → 关),重启应用后按这里的设置重新尝试"
    },
    {
      "type": "header",
      "title": "动画"
    },
    {
      "type": "choice",
      "key": "motion",
      "title": "界面动画",
      "options": [
        [
          "slide",
          "系统风格(默认)"
        ],
        [
          "parallax",
          "整屏滑动 + 视差(Telegram 式)"
        ],
        [
          "axis",
          "Material 共享轴"
        ],
        [
          "fade",
          "仅淡入淡出"
        ],
        [
          "off",
          "关闭动画"
        ]
      ],
      "desc": "页面之间(文件夹列表 → 文件夹 → 设置)的进入 / 返回动画,改了下一次打开页面就生效。系统风格 = 用系统自己的转场,返回手势时有系统的预测性返回预览;其它风格是应用自己的转场。打开图片 / 视频时都是从点的位置展开到全屏(选「关闭动画」时直接打开)"
    },
    {
      "type": "switch",
      "key": "fineMotion",
      "title": "细节动效",
      "desc": "点按缩略图 / 按钮时轻轻缩小再弹回、盘符标签的下划线跟着手指滑动、登录页入场。只影响这些小动画,页面之间的转场看上面的「界面动画」;界面动画选关闭时这里也不生效"
    },
    {
      "type": "header",
      "title": "聊天流"
    },
    {
      "type": "choice",
      "key": "chatBackground",
      "title": "聊天背景",
      "options": [
        [
          "gradient",
          "渐变(Telegram 默认)"
        ],
        [
          "plain",
          "纯色"
        ]
      ]
    },
    {
      "type": "switch",
      "key": "showDatePills",
      "title": "显示日期胶囊",
      "desc": "在聊天流里按天分隔,显示“今天 / 昨天 / 日期”(只在按时间排序时显示,按名称 / 大小 / 类型排序时没有意义,自动隐藏)"
    },
    {
      "type": "header",
      "title": "隐私"
    },
    {
      "type": "switch",
      "key": "screenSecure",
      "title": "禁止截屏和录屏",
      "desc": "开启后截图、录屏都是黑的,最近任务列表里也不显示画面预览(切换后立即生效)"
    }
  ],
  "folders": [
    {
      "type": "header",
      "title": "排序与过滤"
    },
    {
      "type": "choice",
      "key": "dialogSort",
      "title": "文件夹排序",
      "options": [
        [
          "recent",
          "最近更新(默认)"
        ],
        [
          "name",
          "名称 A → Z"
        ],
        [
          "count",
          "媒体数量(多 → 少)"
        ],
        [
          "path",
          "路径"
        ]
      ],
      "desc": "只影响首页的文件夹列表;盘符标签页内同样生效"
    },
    {
      "type": "choice",
      "key": "dialogMinCount",
      "title": "隐藏小文件夹",
      "options": [
        [
          0,
          "不隐藏"
        ],
        [
          2,
          "少于 2 个媒体"
        ],
        [
          5,
          "少于 5 个媒体"
        ],
        [
          10,
          "少于 10 个媒体"
        ],
        [
          30,
          "少于 30 个媒体"
        ]
      ],
      "desc": "隐藏只有零星几张图的文件夹;搜索不受影响"
    },
    {
      "type": "header",
      "title": "每一行"
    },
    {
      "type": "choice",
      "key": "dialogSubtitle",
      "title": "第二行内容",
      "options": [
        [
          "last",
          "最近浏览的文件名"
        ],
        [
          "path",
          "所在路径"
        ],
        [
          "none",
          "不显示"
        ]
      ]
    },
    {
      "type": "switch",
      "key": "avatarCover",
      "title": "头像使用文件夹封面",
      "desc": "关闭后头像只显示首字母,更省流量"
    }
  ],
  "browse": [
    {
      "type": "header",
      "title": "打开文件夹"
    },
    {
      "type": "switch",
      "key": "swipeGroups",
      "title": "左右滑动切换盘符",
      "desc": "在主界面(文件夹列表)向左 / 向右滑动,切换到下一个 / 上一个盘符(第一页是「全部」),和点顶部的盘符标签一样。在群里不会横向切换"
    },
    {
      "type": "switch",
      "key": "rememberPosition",
      "title": "记住浏览位置",
      "desc": "再次打开同一个文件夹时回到上次退出的位置,并记住排序、网格 / 聊天、过滤"
    },
    {
      "type": "header",
      "title": "默认排序"
    },
    {
      "type": "choice",
      "key": "defaultSort",
      "title": "排序依据",
      "options": [
        [
          "auto",
          "自动(服务器按名称,本地按时间)"
        ],
        [
          "name",
          "文件名称"
        ],
        [
          "taken",
          "文件时间"
        ],
        [
          "size",
          "文件大小"
        ],
        [
          "type",
          "文件类型"
        ]
      ],
      "desc": "第一次打开的文件夹用这个排序;在文件夹右上角菜单里改过的,以后仍用改过的"
    },
    {
      "type": "choice",
      "key": "defaultSortDir",
      "title": "排序方向",
      "options": [
        [
          "auto",
          "自动(名称 / 类型升序,其余新的 / 大的在前)"
        ],
        [
          "asc",
          "升序"
        ],
        [
          "desc",
          "降序"
        ]
      ]
    },
    {
      "type": "header",
      "title": "网格"
    },
    {
      "type": "choice",
      "key": "defaultColumns",
      "title": "默认列数",
      "options": [
        [
          2,
          "2 列"
        ],
        [
          3,
          "3 列"
        ],
        [
          4,
          "4 列"
        ],
        [
          5,
          "5 列"
        ],
        [
          6,
          "6 列"
        ]
      ],
      "desc": "已经设置过的文件夹仍用它自己的列数"
    },
    {
      "type": "choice",
      "key": "gridSpacing",
      "title": "格子间距",
      "options": [
        [
          0,
          "无"
        ],
        [
          1,
          "1 dp(默认)"
        ],
        [
          2,
          "2 dp"
        ],
        [
          4,
          "4 dp"
        ],
        [
          8,
          "8 dp"
        ]
      ]
    },
    {
      "type": "choice",
      "key": "gridThumbWidth",
      "title": "缩略图清晰度",
      "options": [
        [
          320,
          "标准(省流量)"
        ],
        [
          480,
          "高(默认)"
        ],
        [
          720,
          "很高"
        ],
        [
          960,
          "最高"
        ]
      ],
      "desc": "列数少、屏幕大时调高更清晰"
    },
    {
      "type": "switch",
      "key": "showVideoName",
      "title": "显示文件名",
      "desc": "缩略图左下角用小字显示文件名(图片和视频,不含扩展名),单行、过长省略"
    },
    {
      "type": "switch",
      "key": "showDuration",
      "title": "显示视频时长",
      "desc": "视频缩略图上显示时长;关闭后只显示一个播放标记"
    },
    {
      "type": "header",
      "title": "聊天流"
    },
    {
      "type": "choice",
      "key": "chatSpacing",
      "title": "气泡间距",
      "options": [
        [
          1,
          "紧凑 1 dp"
        ],
        [
          3,
          "标准 3 dp(默认)"
        ],
        [
          6,
          "宽松 6 dp"
        ],
        [
          12,
          "很宽 12 dp"
        ]
      ]
    },
    {
      "type": "choice",
      "key": "bubbleWidth",
      "title": "气泡宽度",
      "options": [
        [
          60,
          "60%"
        ],
        [
          72,
          "72%(默认)"
        ],
        [
          85,
          "85%"
        ],
        [
          100,
          "撑满"
        ]
      ]
    },
    {
      "type": "choice",
      "key": "chatImageWidth",
      "title": "图片清晰度",
      "options": [
        [
          480,
          "标准(省流量)"
        ],
        [
          720,
          "高"
        ],
        [
          960,
          "很高(默认)"
        ],
        [
          1280,
          "最高"
        ]
      ]
    },
    {
      "type": "header",
      "title": "加载"
    },
    {
      "type": "choice",
      "key": "preloadCount",
      "title": "预加载附近缩略图",
      "options": [
        [
          0,
          "关闭"
        ],
        [
          24,
          "24 张"
        ],
        [
          48,
          "48 张(默认)"
        ],
        [
          96,
          "96 张"
        ],
        [
          192,
          "192 张"
        ]
      ],
      "desc": "滑动或跳到某处、停下来之后,把那附近屏幕外的缩略图提前下载进本机缓存(滑动方向多一些),再往那边滑就不用等网络。会多用一些流量和缓存空间;只进磁盘缓存,不占内存"
    },
    {
      "type": "switch",
      "key": "pauseThumbsWhenFast",
      "title": "快速滑动时暂停加载缩略图",
      "desc": "滑得很快时先不请求图片,停下后再加载,省流量也更流畅"
    }
  ],
  "viewer": [
    {
      "type": "header",
      "title": "加载"
    },
    {
      "type": "choice",
      "key": "viewerPreload",
      "title": "预加载页数",
      "options": [
        [
          0,
          "不预加载"
        ],
        [
          1,
          "左右各 1 页(默认)"
        ],
        [
          2,
          "左右各 2 页"
        ],
        [
          3,
          "左右各 3 页"
        ]
      ],
      "desc": "翻页更快,但更费流量和内存;连续看大图时可以调大"
    },
    {
      "type": "choice",
      "key": "viewerImageWidth",
      "title": "全屏图片清晰度",
      "options": [
        [
          1440,
          "1440 像素宽(省流量)"
        ],
        [
          2160,
          "2160 像素宽"
        ],
        [
          2880,
          "2880 像素宽(默认)"
        ],
        [
          4096,
          "4096 像素宽(最清晰)"
        ]
      ],
      "desc": "放大看细节时越大越清晰;服务端会按这个宽度生成预览图"
    },
    {
      "type": "header",
      "title": "翻页"
    },
    {
      "type": "switch",
      "key": "rightSwipePrev",
      "title": "向右滑 = 上一个",
      "desc": "在聊天流里打开的图片 / 视频:向右滑回到上面那一条(更早的),向左滑到下面那一条;关闭则相反。上一个 / 下一个按钮和自动播放下一个也按这个方向"
    },
    {
      "type": "header",
      "title": "缩放"
    },
    {
      "type": "choice",
      "key": "maxZoom",
      "title": "放大上限",
      "options": [
        [
          2,
          "2 倍(默认)"
        ],
        [
          3,
          "3 倍"
        ],
        [
          4,
          "4 倍"
        ],
        [
          6,
          "6 倍"
        ],
        [
          8,
          "8 倍"
        ]
      ],
      "desc": "双指或双击放大时最多放大到多少"
    },
    {
      "type": "header",
      "title": "幻灯片全屏提示"
    }
  ],
  "image": [
    {
      "type": "header",
      "title": "缓存"
    },
    {
      "type": "choice",
      "key": "imageDiskCacheMb",
      "title": "磁盘缓存上限",
      "options": [
        [
          128,
          "128 MB"
        ],
        [
          256,
          "256 MB"
        ],
        [
          512,
          "512 MB"
        ],
        [
          1024,
          "1 GB"
        ],
        [
          2048,
          "2 GB"
        ],
        [
          4096,
          "4 GB"
        ]
      ],
      "desc": "已看过的缩略图和图片保存在手机里,再次浏览不用重新下载"
    },
    {
      "type": "choice",
      "key": "imageMemPercent",
      "title": "内存缓存比例",
      "options": [
        [
          10,
          "10%"
        ],
        [
          15,
          "15%"
        ],
        [
          20,
          "20%"
        ],
        [
          25,
          "25%"
        ],
        [
          30,
          "30%"
        ],
        [
          40,
          "40%"
        ]
      ],
      "desc": "占应用可用内存的比例;大相册快速回滚时越大越流畅"
    },
    {
      "type": "header",
      "title": "格式兼容"
    },
    {
      "type": "choice",
      "key": "heicMode",
      "title": "HEIC / HEIF / AVIF",
      "options": [
        [
          "server",
          "服务端转换(推荐)"
        ],
        [
          "native",
          "手机直接解码"
        ]
      ],
      "desc": "服务端转成 JPEG 后显示,方向和色彩都正确;RAW、JPEG XL、TIFF 等格式总是由服务端转换"
    },
    {
      "type": "header",
      "title": "显示"
    },
    {
      "type": "switch",
      "key": "hardwareBitmaps",
      "title": "硬件位图",
      "desc": "更省内存、绘制更快;个别机型出现花屏时关闭"
    },
    {
      "type": "switch",
      "key": "crossfade",
      "title": "淡入动画",
      "desc": "图片加载完成时渐显"
    }
  ],
  "video": [
    {
      "type": "header",
      "title": "播放"
    },
    {
      "type": "switch",
      "key": "rememberPlayback",
      "title": "记住播放设置",
      "desc": "在播放器里选过的转码画质 / 编码会记住,切到下一个视频直接用(例如选了 1.5M 转码,后面的视频默认也是 1.5M),并同步到服务器:多台手机共用,服务端还会提前把下一个视频的开头转好,切换几乎秒开。关闭后每个视频都从原画开始"
    },
    {
      "type": "switch",
      "key": "autoplay",
      "title": "自动播放",
      "desc": "翻到视频页时自动开始播放"
    },
    {
      "type": "switch",
      "key": "loop",
      "title": "循环播放",
      "desc": "播放结束后从头再播"
    },
    {
      "type": "switch",
      "key": "muted",
      "title": "默认静音"
    },
    {
      "type": "choice",
      "key": "speed",
      "title": "默认倍速",
      "options": [
        [
          0.5,
          "0.5x"
        ],
        [
          0.75,
          "0.75x"
        ],
        [
          1,
          "1x"
        ],
        [
          1.25,
          "1.25x"
        ],
        [
          1.5,
          "1.5x"
        ],
        [
          2,
          "2x"
        ]
      ]
    },
    {
      "type": "switch",
      "key": "resume",
      "title": "记住播放进度",
      "desc": "下次打开同一个视频从上次的位置继续;看到结尾附近则从头开始"
    },
    {
      "type": "choice",
      "key": "resumeRewindSec",
      "title": "续播时回退",
      "options": [
        [
          0,
          "不回退"
        ],
        [
          3,
          "3 秒"
        ],
        [
          5,
          "5 秒"
        ],
        [
          10,
          "10 秒"
        ]
      ],
      "desc": "从上次位置继续时先往回几秒,方便接上剧情"
    },
    {
      "type": "switch",
      "key": "autoLandscape",
      "title": "横向视频自动横屏",
      "desc": "播放宽大于高的视频时自动转横屏,翻到别的页恢复"
    },
    {
      "type": "header",
      "title": "手势与小窗"
    },
    {
      "type": "switch",
      "key": "gestures",
      "title": "手势控制",
      "desc": "双击左 / 右侧快退 / 快进,双击中间暂停;长按倍速;左半屏上下滑调亮度,右半屏调音量"
    },
    {
      "type": "switch",
      "key": "swipeSeek",
      "title": "水平滑动快进快退",
      "desc": "视频里左右滑动调整进度(和 VLC 一样);关闭后左右滑动切换上 / 下一项"
    },
    {
      "type": "choice",
      "key": "barSwipeDp",
      "title": "功能条滑动切换距离",
      "options": [
        [
          15,
          "15dp(极短)"
        ],
        [
          25,
          "25dp(短)"
        ],
        [
          40,
          "40dp(默认)"
        ],
        [
          60,
          "60dp"
        ],
        [
          80,
          "80dp(长)"
        ]
      ],
      "desc": "视频控制条显示时,在进度条下面那一排左右滑动切换上 / 下一个媒体所需的距离,越小越灵敏"
    },
    {
      "type": "choice",
      "key": "swipeSpanSec",
      "title": "横扫快进范围",
      "options": [
        [
          30,
          "30 秒"
        ],
        [
          60,
          "60 秒"
        ],
        [
          90,
          "90 秒(默认)"
        ],
        [
          180,
          "3 分钟"
        ],
        [
          300,
          "5 分钟"
        ]
      ],
      "desc": "手指从屏幕一侧横扫到另一侧对应的快进 / 快退时长,长视频可以调大"
    },
    {
      "type": "choice",
      "key": "gestureSense",
      "title": "亮度 / 音量滑动灵敏度",
      "options": [
        [
          0.8,
          "低"
        ],
        [
          1.3,
          "标准(默认)"
        ],
        [
          2,
          "高"
        ],
        [
          3,
          "很高"
        ]
      ],
      "desc": "竖向滑动时调整的快慢"
    },
    {
      "type": "choice",
      "key": "longPressSpeed",
      "title": "长按倍速",
      "options": [
        [
          1.5,
          "1.5x"
        ],
        [
          2,
          "2x"
        ],
        [
          3,
          "3x"
        ],
        [
          4,
          "4x"
        ]
      ],
      "desc": "按住画面时临时加速,松手恢复"
    },
    {
      "type": "switch",
      "key": "autoNext",
      "title": "播放完自动下一个",
      "desc": "视频播放结束后自动翻到下一项"
    },
    {
      "type": "switch",
      "key": "autoPip",
      "title": "离开应用时自动小窗",
      "desc": "正在播放时按 Home 键,自动进入画中画小窗"
    },
    {
      "type": "header",
      "title": "快进快退"
    },
    {
      "type": "choice",
      "key": "seekBackSec",
      "title": "快退",
      "options": [
        [
          5,
          "5 秒"
        ],
        [
          10,
          "10 秒"
        ],
        [
          15,
          "15 秒"
        ],
        [
          30,
          "30 秒"
        ]
      ]
    },
    {
      "type": "choice",
      "key": "seekForwardSec",
      "title": "快进",
      "options": [
        [
          5,
          "5 秒"
        ],
        [
          10,
          "10 秒"
        ],
        [
          15,
          "15 秒"
        ],
        [
          30,
          "30 秒"
        ]
      ]
    },
    {
      "type": "header",
      "title": "画面"
    },
    {
      "type": "choice",
      "key": "resizeMode",
      "title": "画面缩放",
      "options": [
        [
          "fit",
          "适应屏幕(留黑边)"
        ],
        [
          "zoom",
          "裁剪填满"
        ],
        [
          "fill",
          "拉伸填满"
        ]
      ]
    },
    {
      "type": "choice",
      "key": "controllerTimeoutSec",
      "title": "控制条自动隐藏",
      "options": [
        [
          2,
          "2 秒"
        ],
        [
          3,
          "3 秒"
        ],
        [
          4,
          "4 秒"
        ],
        [
          5,
          "5 秒"
        ],
        [
          8,
          "8 秒"
        ],
        [
          10,
          "10 秒"
        ]
      ]
    },
    {
      "type": "switch",
      "key": "keepScreenOn",
      "title": "播放时保持屏幕常亮"
    },
    {
      "type": "header",
      "title": "音频"
    },
    {
      "type": "choice",
      "key": "volumeBoostMb",
      "title": "音量增益",
      "options": [
        [
          0,
          "关闭"
        ],
        [
          300,
          "+3 dB"
        ],
        [
          600,
          "+6 dB"
        ],
        [
          1000,
          "+10 dB"
        ],
        [
          1500,
          "+15 dB"
        ]
      ],
      "desc": "视频声音太小时在系统音量之上再放大;过大可能失真。下一次播放生效"
    },
    {
      "type": "switch",
      "key": "pauseOnUnplug",
      "title": "拔掉耳机时暂停",
      "desc": "耳机拔出或蓝牙断开时自动暂停,避免外放"
    },
    {
      "type": "switch",
      "key": "volumeNormalize",
      "title": "自动音量均衡",
      "desc": "不同视频的音量自动调到差不多:录得小声的放大、太吵的压低(最多 ±12dB,带防爆音)。在应用内部给声音乘一个系数,不改系统音量;每个视频的系数会记住,下次打开直接生效"
    },
    {
      "type": "header",
      "title": "字幕样式"
    },
    {
      "type": "choice",
      "key": "subtitleScale",
      "title": "字幕大小",
      "options": [
        [
          0.75,
          "小"
        ],
        [
          1,
          "标准(默认)"
        ],
        [
          1.25,
          "大"
        ],
        [
          1.5,
          "很大"
        ],
        [
          2,
          "特大"
        ]
      ]
    },
    {
      "type": "choice",
      "key": "subtitleStyle",
      "title": "字幕样式",
      "options": [
        [
          "system",
          "跟随系统字幕设置(默认)"
        ],
        [
          "outline",
          "白字黑描边"
        ],
        [
          "shadow",
          "白字阴影"
        ],
        [
          "box",
          "白字黑底"
        ],
        [
          "yellow",
          "黄字黑描边"
        ]
      ]
    },
    {
      "type": "choice",
      "key": "subtitleBottom",
      "title": "字幕位置",
      "options": [
        [
          4,
          "靠近底边"
        ],
        [
          8,
          "默认"
        ],
        [
          14,
          "稍高"
        ],
        [
          22,
          "高"
        ]
      ],
      "desc": "字幕离画面底部的距离;字幕是否显示、语言在「编解码器 → 音轨与字幕」里选"
    },
    {
      "type": "header",
      "title": "调试"
    },
    {
      "type": "switch",
      "key": "showStats",
      "title": "显示技术信息",
      "desc": "在画面角落显示解码器、格式、码率、丢帧数,排查卡顿时有用"
    }
  ],
  "codec": [
    {
      "type": "header",
      "title": "解码器选择"
    },
    {
      "type": "choice",
      "key": "decoderMode",
      "title": "解码方式",
      "options": [
        [
          "auto",
          "自动(系统默认顺序)"
        ],
        [
          "hw_first",
          "硬件解码优先"
        ],
        [
          "sw_first",
          "软件解码优先"
        ],
        [
          "hw_only",
          "仅硬件解码"
        ],
        [
          "sw_only",
          "仅软件解码"
        ]
      ],
      "desc": "“仅硬件 / 仅软件”不会回退到另一种,遇到不支持的格式会直接报错"
    },
    {
      "type": "switch",
      "key": "autoSoftwareFallback",
      "title": "解码失败自动改软解",
      "desc": "硬件解码器报错时,自动换成软件解码从当前位置重试"
    },
    {
      "type": "switch",
      "key": "useCompat",
      "title": "兼容版本(只转音频)",
      "desc": "视频本身手机能解、只是音轨(DTS / TrueHD 等)或容器(AVI 等)不支持时,让服务端做一份兼容版本:视频流原样复制(画质无损、不占转码路数),只把音频转成 AAC。第一次要等服务端做完,期间先用转码播放,之后直接秒开。需要服务端 3.2.0"
    },
    {
      "type": "choice",
      "key": "videoCacheMb",
      "title": "视频缓存",
      "options": [
        [
          0,
          "关闭"
        ],
        [
          256,
          "256 MB"
        ],
        [
          512,
          "512 MB"
        ],
        [
          1024,
          "1 GB"
        ],
        [
          2048,
          "2 GB"
        ],
        [
          4096,
          "4 GB"
        ]
      ],
      "desc": "直接播放的视频缓存在手机上的大小上限:往回拖、重看不用重新下载,下一个视频也会提前缓存开头(只在 Wi-Fi / 内网直连时)"
    },
    {
      "type": "switch",
      "key": "autoTranscode",
      "title": "解不了时自动转码",
      "desc": "手机无法播放(封装 / 编码不支持,软解也失败)时,自动改用服务端转码播放(服务端内置 ffmpeg,不需要额外安装)"
    },
    {
      "type": "switch",
      "key": "decoderFallback",
      "title": "允许备用解码器",
      "desc": "首选解码器初始化失败时依次尝试同格式的其他解码器"
    },
    {
      "type": "choice",
      "key": "asyncQueueing",
      "title": "MediaCodec 异步队列",
      "options": [
        [
          "auto",
          "自动"
        ],
        [
          "on",
          "强制开启"
        ],
        [
          "off",
          "强制关闭"
        ]
      ],
      "desc": "部分机型开启后丢帧更少;个别机型会花屏,可强制关闭"
    },
    {
      "type": "switch",
      "key": "tunneling",
      "title": "隧道播放",
      "desc": "把解码和显示交给硬件直通,可能更省电并提升 4K / HDR 流畅度;部分机型不稳定"
    },
    {
      "type": "header",
      "title": "缓冲"
    },
    {
      "type": "choice",
      "key": "bufferMode",
      "title": "缓冲大小",
      "options": [
        [
          "small",
          "小(约 15 秒,省流量)"
        ],
        [
          "standard",
          "标准(约 50 秒)"
        ],
        [
          "large",
          "大(约 120 秒,Wi-Fi 不稳时用)"
        ]
      ]
    },
    {
      "type": "header",
      "title": "服务端转码"
    },
    {
      "type": "choice",
      "key": "maxBitrateMbps",
      "title": "转码码率",
      "options": [
        [
          0,
          "自动(按原分辨率)"
        ],
        [
          6,
          "6 Mbps"
        ],
        [
          5,
          "5 Mbps"
        ],
        [
          4,
          "4 Mbps"
        ],
        [
          3,
          "3 Mbps"
        ],
        [
          2,
          "2 Mbps"
        ],
        [
          1,
          "1 Mbps"
        ]
      ],
      "desc": "转码只调整码率,不改变分辨率(原来多少像素还是多少),只提供 6 Mbps 及以下的档位。自动 = 按视频原分辨率:480p 1.5M / 720p 3M / 1080p 及以上 6M;网络不好时调低。更细的档位(0.5M / 0.8M / 1.5M 等)在播放器右上角菜单的「画质与解码」里选"
    },
    {
      "type": "switch",
      "key": "autoQuality",
      "title": "自动画质",
      "desc": "网速不够时自动降一档转码:开播前网速估计明显低于视频码率就直接转码到扛得住的档位;播放中连续卡顿(45 秒内两次,或一次超过 8 秒)就自动降一档并从当前位置继续,画面上会提示,可在播放器菜单里改回。手动选择画质后优先保持手动档位；自动降档只针对未手动选定画质的视频，不改「记住的播放设置」。经内网穿透 / 云中继、带宽小的时候最有用。需要服务端支持转码。"
    },
    {
      "type": "choice",
      "key": "playerCodec",
      "title": "转码编码",
      "options": [
        [
          "auto",
          "自动(推荐)"
        ],
        [
          "h264",
          "H.264"
        ],
        [
          "h265",
          "H.265"
        ]
      ],
      "desc": "H.265 在相同码率下画质更好、相同画质更省流量。自动 = 服务端能输出 H.265(需要服务器有 Intel QSV 显卡硬编)且手机能硬解 H.265 就用 H.265,否则 H.264"
    },
    {
      "type": "header",
      "title": "音轨与字幕"
    },
    {
      "type": "choice",
      "key": "audioLanguage",
      "title": "首选音轨语言",
      "options": [
        [
          "",
          "跟随系统"
        ],
        [
          "zh",
          "中文"
        ],
        [
          "en",
          "English"
        ],
        [
          "ja",
          "日本語"
        ],
        [
          "ko",
          "한국어"
        ]
      ],
      "desc": "文件里有多条音轨时优先选这种语言"
    },
    {
      "type": "switch",
      "key": "showSubtitles",
      "title": "显示内嵌字幕",
      "desc": "播放有内嵌字幕轨的 MKV / MP4 时显示字幕"
    },
    {
      "type": "choice",
      "key": "subtitleLanguage",
      "title": "首选字幕语言",
      "options": [
        [
          "",
          "跟随系统"
        ],
        [
          "zh",
          "中文"
        ],
        [
          "en",
          "English"
        ],
        [
          "ja",
          "日本語"
        ],
        [
          "ko",
          "한국어"
        ]
      ]
    }
  ],
  "enhance": [
    {
      "type": "header",
      "title": "补帧(LSFG)"
    },
    {
      "type": "choice",
      "key": "enhFrc",
      "title": "补帧方式",
      "options": [
        [
          "off",
          "关闭"
        ],
        [
          "lsfg_low",
          "LSFG 低功耗(目标 60 帧,省电发热小)"
        ],
        [
          "lsfg",
          "LSFG 标准(补到屏幕最高刷新率,效果最好,最费 GPU)"
        ]
      ],
      "desc": "补帧只保留内置的 LSFG(Lossless Scaling 帧生成)。标准:把 24 / 30 帧视频补到屏幕刷新率(最高 120 Hz);低功耗:目标 60 帧(24 → 48、25 → 50、30 → 60),光流精度从 50% 起步并自动调整、固定请求 60 Hz 刷新率,GPU 占用和发热明显更小;60 帧的视频不需要补。快速运动和遮挡处可能有瑕疵",
      "requires": "lsfg"
    },
    {
      "type": "choice",
      "key": "enhFrcMultiplier",
      "title": "补帧倍率(标准模式)",
      "options": [
        [
          0,
          "自动(补到屏幕刷新率)"
        ],
        [
          2,
          "2 倍"
        ],
        [
          3,
          "3 倍"
        ],
        [
          4,
          "4 倍"
        ],
        [
          5,
          "5 倍"
        ],
        [
          6,
          "6 倍"
        ],
        [
          8,
          "8 倍"
        ]
      ],
      "desc": "固定倍率:24fps 视频 × 3 = 72fps。倍率超过 屏幕刷新率 ÷ 源帧率 时按能显示的最大倍率算;每个源帧之间只生成需要的画面,GPU 压力比“自动”小。低功耗模式不看这一项"
    },
    {
      "type": "choice",
      "key": "lsfgFlowScale",
      "title": "LSFG 光流精度(标准模式)",
      "options": [
        [
          0.25,
          "25%(最快)"
        ],
        [
          0.5,
          "50%(默认)"
        ],
        [
          0.75,
          "75%"
        ],
        [
          1,
          "100%(最准)"
        ]
      ],
      "desc": "内部光流的分辨率比例。1080p 以上建议 50% 以下;下一次开始播放生效。低功耗模式不看这一项(50% 起步,自动调整)"
    },
    {
      "type": "switch",
      "key": "lsfgPerf",
      "title": "LSFG 性能模式(标准模式)",
      "desc": "用 LSFG 3.1P(更轻量的变体)。关闭后用标准的 3.1,画质略好但更费 GPU;低功耗模式强制开启"
    },
    {
      "type": "switch",
      "key": "lsfgFp32",
      "title": "LSFG 强制 FP32 着色器",
      "desc": "默认优先用 FP16 着色器(半精度,Mali / 天玑 / 骁龙上都快不少,初始化失败会自动退回 FP32)。如果开了补帧画面花屏 / 颜色不对,打开这个"
    },
    {
      "type": "switch",
      "key": "frcTrace",
      "title": "补帧详细日志",
      "desc": "补帧时每 2 秒把统计写进日志(标签 frc):源帧间隔、vsync 抖动、上屏节奏(相位重复 / 跳过)、LSFG 生成耗时、覆盖率、热状态…,异常时立即写一行。卡顿时打开,到「设置 → 日志与诊断」分享日志给开发者分析"
    },
    {
      "type": "switch",
      "key": "enhFrcAdaptive",
      "title": "补帧跟不上时自动降级",
      "desc": "补帧耗时持续超过帧间隔(或覆盖率不足)时,依次降低光流精度,降到最低还不够就停用补帧,避免掉帧"
    },
    {
      "type": "switch",
      "key": "enhFpsOverlay",
      "title": "右上角显示帧率",
      "desc": "补帧时在画面右上角用小字显示“源帧率 → 输出帧率”;源帧率已接近目标刷新率时显示“补帧待机”"
    },
    {
      "type": "header",
      "title": "SDR 转 HDR"
    },
    {
      "type": "choice",
      "key": "enhHdr",
      "title": "SDR→HDR",
      "options": [
        [
          "off",
          "关闭"
        ],
        [
          "auto",
          "自动(屏幕支持 HDR 时开启)"
        ],
        [
          "on",
          "总是开启"
        ]
      ],
      "desc": "把普通视频的高光扩展到 HDR 亮度,输出 BT.2020 PQ。需要支持 HDR 的屏幕;阴影和中间调保持不变,大面积亮区会自动少扩展"
    },
    {
      "type": "choice",
      "key": "enhPeak",
      "title": "HDR 峰值亮度",
      "options": [
        [
          400,
          "400 nit(保守)"
        ],
        [
          600,
          "600 nit"
        ],
        [
          800,
          "800 nit"
        ],
        [
          1000,
          "1000 nit(明显)"
        ]
      ],
      "desc": "高光最亮扩展到多少。OLED 手机的峰值一般在 1000–2000 nit;过高会显得刺眼"
    }
  ],
  "network": [
    {
      "type": "header",
      "title": "连接线路"
    },
    {
      "type": "switch",
      "key": "lanFirst",
      "title": "内网直连优先",
      "desc": "和服务器在同一个局域网时自动改走服务器的内网地址(比经过内网穿透 / 云中继快得多,也不占穿透服务器的带宽);离开家里的网络、内网地址连不上时自动切回服务器地址。需要服务端 3.1.0,服务器是 HTTPS(内网地址同样要通过证书指纹校验)。"
    },
    {
      "type": "header",
      "title": "超时"
    },
    {
      "type": "choice",
      "key": "connectTimeoutSec",
      "title": "连接超时",
      "options": [
        [
          3,
          "3 秒"
        ],
        [
          5,
          "5 秒"
        ],
        [
          10,
          "10 秒"
        ],
        [
          20,
          "20 秒"
        ]
      ],
      "desc": "连不上服务器时多久放弃"
    },
    {
      "type": "choice",
      "key": "readTimeoutSec",
      "title": "读取超时",
      "options": [
        [
          15,
          "15 秒"
        ],
        [
          30,
          "30 秒"
        ],
        [
          60,
          "60 秒"
        ],
        [
          120,
          "120 秒"
        ]
      ],
      "desc": "硬盘休眠唤醒较慢时可调大"
    }
  ],
  "log": [
    {
      "type": "header",
      "title": "记录"
    },
    {
      "type": "choice",
      "key": "logLevel",
      "title": "日志级别",
      "options": [
        [
          "off",
          "关闭"
        ],
        [
          "warn",
          "仅错误与警告"
        ],
        [
          "info",
          "信息(默认)"
        ],
        [
          "debug",
          "调试(详细,含每个网络请求)"
        ]
      ],
      "desc": "遇到问题时先切到“调试”,把问题重现一次,再分享日志"
    },
    {
      "type": "header",
      "title": "发给开发者"
    },
    {
      "type": "header",
      "title": "崩溃报告"
    },
    {
      "type": "header",
      "title": "清理"
    }
  ],
  "slideshow": [
    {
      "type": "choice",
      "key": "slideshowPhotoIndicator",
      "title": "图片全屏提示",
      "options": [
        [
          "bar",
          "进度条"
        ],
        [
          "countdown",
          "暂停按钮和倒计时"
        ],
        [
          "none",
          "不显示"
        ]
      ],
      "desc": "图片隐藏信息时使用，倒计时显示在左下角暂停按钮右侧"
    },
    {
      "type": "choice",
      "key": "slideshowBarPosition",
      "title": "进度条位置",
      "options": [
        [
          "top",
          "上"
        ],
        [
          "bottom",
          "下"
        ],
        [
          "left",
          "左"
        ],
        [
          "right",
          "右"
        ]
      ],
      "desc": "图片和视频进度条共用"
    }
  ]
};
