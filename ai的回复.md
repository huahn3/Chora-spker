新建的第三方接入输出设备专属文档
如同 docs/lyrics-translation-api.md 一样，已在仓库中新增了面向第三方播放器集成的标准化文档： 📄 

docs/jukebox-api.md
（已同步更新至 

AGENTS.md
 与 

docs/jukebox.md
）

文档核心亮点速览：
鉴权与协议契约：标准 Native REST JSON API，使用 Authorization: Bearer <token>（通过 POST /auth/login 取得）。
端点规范：
GET /api/jukebox/devices：获取可用设备列表（browser、mpd、dlna、xiaomi）与当前激活设备。
POST /api/jukebox/select：切换当前激活的输出设备（切到 browser 自动停止远程外放）。
POST /api/jukebox/play：向当前激活设备投播歌曲（携带 songId 与秒级进度 position）。
POST /api/jukebox/control：设备控制（pause、resume、stop、seek、volume）。
GET /api/jukebox/status：轮询设备播放进度、时长、音量（0-100）及设备类型。
客户端必守四大铁律：
0 音量容错与 3 秒防抢手：设备刚唤醒时常回报 0，客户端接收到 0 时必须忽略，切忌覆盖本地音量；用户拖动音量需 200ms 防抖，且松手后 3 秒内屏蔽远端回写。
本地时钟与前台服务静音驱动：切换远程时，本地 ExoPlayer 音量置 0，保持 MediaSession 与前台通知栏不丢，锁屏控制依然可用。
设备能力降级：当 deviceType == "xiaomi" 时，音箱原生协议无进度回报且不支持 seek，UI 进度条需禁用拖拽。
无缝流转：切换设备时带上本地进度 position，实现无缝续播。
完整 Kotlin + Retrofit 数据契约与请求模型示例。
二、 让二开 Chora 接入所有新特性，应该给看哪些东西？
在您的二开工程中（位置：/Users/liubo/Desktop/Chora-spker），如果要让接手的 AI 或您自己快速对接，只需向其提供以下两类资料：

📁 核心文档资料包
├── 1. 后端接口规范（Navidrome2all 提供）
│   ├── docs/lyrics-translation-api.md   # 歌词翻译、双语结构、缓存与重新翻译
│   ├── docs/jukebox-api.md              # 多输出设备发现、选择、投播、音量与播控
│   └── AGENTS.md                        # 服务端架构总览与避坑法则
│
└── 2. Chora 自身开发规范（客户端中枢）
    └── chora-music-dev (Skill)          # 位于 ~/.gemini/config/skills/chora-music-dev/SKILL.md
三、 Chora 客户端落地实施路线图（直接可交付给开发者/AI）
针对 Chora 当前的架构（Jetpack Compose + AndroidX Media3 1.5+ ExoPlayer + Retrofit），各特性的具体接入点如下：

1. 歌词翻译与双语逐行同步接入
API 交互：
默认加载歌词时，异步通过 GET /api/lyrics/translate/{songId}?lang=zh-CN 检查是否有缓存；
用户在界面点击「翻译」按钮时，调用 POST /api/lyrics/translate（body: {"songId": "...", "targetLang": "zh-CN"}）；
用户长按「翻译」按钮时，触发重新翻译（传入 "force": true）。
数据渲染（位于 NowPlayingPortrait.kt / TvNowPlaying.kt）：
推荐方式：直接使用返回的结构化 lines 数组（每个 item 包含毫秒级 start、end、original 原文、translation 译文）。在 Compose 的歌词列表中，每一行直接渲染为“原文大字 + 译文小字”的优雅双语排版。
文本回退：若复用现有的 LRC 解析器，可直接将 bilingualLrc（双行）或 inlineLrc（单行同时间戳 原文 / 译文）传给歌词解析器。
2. 多输出设备（Jukebox）切换与局域网外放接入
UI 呈现（位于 NowPlayingPortrait.kt 或 MiniPlayer 工具栏）：
增加一个「播放输出设备（Cast / Speaker）」图标。
点击弹出 Compose ModalBottomSheet，展示从 GET /api/jukebox/devices 读取的设备列表（本机播放、DLNA 音箱、MPD 声卡、小米音箱）。
播放内核调度（位于 player/MusicService.kt 与 ExoPlayer）：
切换到远程音箱时：
记下当前播放秒数 val currentSec = player.currentPosition / 1000；
调用 POST /api/jukebox/select 选定设备，并调用 POST /api/jukebox/play 传入 position = currentSec；
将本地 player.volume = 0f（静音），绝不调用 player.stop()，保持 MediaSession 和前台通知栏存活；
启动周期为 1.5s 的协程轮询 GET /api/jukebox/status。
切回手机本机播放时：
调用 POST /api/jukebox/select 传入 "deviceId": "browser"（服务端会自动停掉音箱）；
恢复本地 player.volume = 1f，并调用 player.play() 从原进度继续出声。
通知栏/车机切歌与播控：
在远程播放状态下，MusicService 接收到暂停/切歌/快进指令时，通过 POST /api/jukebox/control 转发给音箱。
音量联动：
界面滑动音量时，防抖 200ms 下发 POST /api/jukebox/control（action: "volume", value: 0..100）；
严格遵守 docs/jukebox-api.md 铁律 1：轮询到音箱汇报音量为 0 时直接丢弃，拖动后 3 秒内锁定本地滑块不被回显覆盖。