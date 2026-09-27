---
name: chora-music-dev
description: Chora 全栈全景架构中枢、Media3/ExoPlayer 播放内核、Navidrome/Subsonic 音频流协议、Jukebox 多输出设备与跨设备播放接管 (Playback Handoff)、十大核心开发铁律与避坑速查指南
---

# Chora (Navidrome / Local Music Player) 全栈开发与架构专家指南

本技能为 **Chora**（面向 Android 与 Android TV 的高品质本地与 Navidrome/Subsonic 流媒体播放器）核心全栈中枢指南。旨在让任何接手 AI 或开发者在极低 Token 消耗下掌握整个项目的架构拓扑、模块职责划分、数据流向以及严守核心开发铁律与避坑指南。

---

## 1. 系统模块与代码组织全景

| 模块名称 | 物理路径 | 技术栈 | 核心职责 |
| :--- | :--- | :--- | :--- |
| **主入口与调度** | `app/src/main/java/.../MainActivity.kt` | Jetpack Compose, Material 3, Navigation | 顶层单 Activity 架构、全局路由导航、系统双层 BackHandler 拦截、媒体控制器动态绑定 |
| **播放内核服务** | `app/src/main/java/.../player/MusicService.kt` | AndroidX Media3 1.5+, ExoPlayer | 前台媒体库服务 (`ChoraMediaLibraryService`)、MediaSession、跨端口/CF 302 重定向嗅探、Scrobble 听歌打卡、历史续播持久化、Jukebox 远程播放拦截转发 |
| **Navidrome 核心** | `app/src/main/java/.../managers/NavidromeManager.kt`<br>`app/src/main/java/.../data/datasource/navidrome/` | OkHttp3, Ktor Client, Subsonic REST API, Native REST API | 多服务器管理、动态 URL 穿透解析（局域网/公网自动选路）、Token/Salt 签名计算、Bearer Token 登录握手与刷新、原生 Jukebox / 歌词翻译扩展 |
| **Jukebox 输出管理** | `app/src/main/java/.../managers/JukeboxManager.kt`<br>`app/src/main/java/.../data/model/JukeboxModel.kt` | Kotlin Flow, StateFlow, Coroutines, Ktor | 局域网/远程多输出设备调度（Browser / MPD / DLNA / 小爱音箱）、音量防抖互锁、无缝流转、状态轮询 |
| **歌词与翻译中枢** | `app/src/main/java/.../data/repository/LyricsRepository.kt`<br>`app/src/main/java/.../data/model/LyricsTranslationModel.kt` | LRCLIB, NetEase, Navidrome Native API | 多源歌词获取、逐行时间戳解析、服务端 AI 翻译缓存预探测、双语对照数据结构映射 |
| **跨设备播放接管** | `app/src/main/java/.../managers/PlaybackHandoffManager.kt`<br>`app/src/main/java/.../data/model/PlaybackHandoffModel.kt` | Ktor, HttpURLConnection SSE, kotlinx.serialization | 会话可见性上报（被看见）、`GET /api/playback/sessions` 轮询（看见别人）、`takeover()` 六维状态继承（进度/播放意图/音量/输出设备/循环/双语）、SSE 被接管通知、封面现签与缓存 |
| **本地状态管理** | `app/src/main/java/.../managers/settings/` | Jetpack Preferences DataStore, Kotlin Flow | 播放续播状态 (`LocalDataSettingsManager`)、外观与动效配置 (`AppearanceSettingsManager`)、缓存与网络配置 |
| **全屏与底栏播放 UI** | `app/src/main/java/.../ui/playing/` | Jetpack Compose Expressive, Coil, Canvas | 响应式底栏 Mini Player、圆形进度环自适应、大屏全屏播放器、双语对照歌词逐行滚动 (`NowPlayingLyrics.kt`)、输出设备底栏浮层 (`JukeboxDeviceBottomSheet.kt`) |
| **TV 交互适配** | `app/src/main/java/.../ui/playing/tv/`<br>`app/src/main/java/.../ui/screens/tv/` | Compose for TV, D-Pad Navigation | 遥控器十字焦点导航、大屏进度滑块、TV 专属专辑与歌单展示 |
| **产物构建与发布** | 项目根目录 `build.gradle.kts` | Gradle 8.9+, AGP 8.7+, Kotlin 2.0+ | 双端自适应打包，产物自动归档根目录 `music.apk` 并支持无线 ADB 极速热推 |

---

## 2. Navidrome 原生扩展 API 契约协议字典 (极度重要·避坑必读)

Navidrome 官方 Subsonic 接口满足基础音频拉取，而**多输出设备（Jukebox）**与**歌词翻译（Lyrics Translation）**由服务端 Go 原生路由 (`/api/*`) 提供支持。**注意：Go 端两个模块在 JSON 字段命名约定上不一致，客户端必须严格按照注解序列化！**

### 2.1 接口序列化契约速查表

| 接口路径 | HTTP 方法 | 请求体字段要求 (JSON) | 响应关键字段 | 避坑铁律 |
| :--- | :--- | :--- | :--- | :--- |
| `/auth/login` | `POST` | `{"username":"...","password":"..."}` | `{"token":"Bearer ...","id":"...","name":"..."}` | 客户端必须在内存缓存 Token；遇到 401 自动使用 Mutex 锁执行重登刷新 |
| `/api/jukebox/devices` | `GET` | 无 (需带 Header `Authorization: Bearer <token>`) | `{"devices":[{"id":"...","name":"...","type":"..."}],"selected":"..."}` | 返回可用设备列表及当前选中的设备 ID |
| `/api/jukebox/select` | `POST` | **`{"device_id":"..."}`** <br>*(必须为 snake_case)* | `{"selected":"..."}` | **⚠️ 严禁写成 `deviceId`！** 若字段缺失，Go 会解码成空串 `""` 导致服务端回退为 `browser` 并返回 200，随后后续播放失败且 UI 仍停留在本机！ |
| `/api/jukebox/play` | `POST` | **`{"song_id":"...","position":0,"stream_url":null}`** <br>*(必须为 snake_case)* | `{"status":"playing"}` | **⚠️ 严禁写成 `songId`！** 否则 Go 服务端报 `HTTP 400: either song_id or stream_url is required` |
| `/api/jukebox/control` | `POST` | `{"action":"...","value":0}` | `{"status":"ok"}` | `action` 可选 `pause`, `resume`, `stop`, `seek`, `volume`；`value` 必须为整数 |
| `/api/jukebox/status` | `GET` | 无 (需带 Header `Authorization: Bearer <token>`) | `{"status":"...","currentTime":0,"duration":0,"volume":0,"deviceId":"...","deviceType":"..."}` | **注意**：此处服务端返回的是 **camelCase** (`currentTime`, `deviceId`, `deviceType`)！ |
| `/api/lyrics/translate/{songId}` | `GET` | Query 参数: `?lang=zh-CN` | `{"songId":"...","targetLang":"...","lines":[...],"bilingualLrc":"..."}` | 探测缓存；若无缓存服务端返回 404 Not Found (正常现象，切勿抛异常) |
| `/api/lyrics/translate` | `POST` | **`{"songId":"...","targetLang":"zh-CN","force":false}`** <br>*(注意此处是 camelCase)* | 同上 | 请求服务端执行 AI 翻译；长按悬浮按钮传 `force: true` 强制重新翻译 |
| `/api/playback/sessions` | `GET` | 无 (Header `X-ND-Authorization` + `X-ND-Client-Unique-Id`) | `{"count":N,"sessions":[{"sessionId","songId","positionMs","coverArtId","outputDevice","playMode","volume","isCurrentSession"}]}` | 响应 **camelCase**；`volume`/`playMode`/`bilingual` 是 `omitempty` ⇒ DTO 用 `Int?`，**缺失 ≠ 0**（服务端把 `volume=0` 当"未上报"） |
| `/api/playback/sessions/{id}/takeover` | `POST` | `{"action":"pause","sourceSessionId":"...","newPlayerName":"...","targetOutput":"..."}` (camelCase) | `{"status":"...","takenOverSessionId":"...","session":{...}}` | **对不存在的会话也返回 200**（`session` 缺失）⇒ 不能用状态码判断成败，异常只 log；必须在**本地已起播之后**才发 |
| `/api/events?jwt=<JWT>` | `GET` (SSE) | token **只能**走 Query `jwt`（走 header 会 401） | `event: playbackHandoff` + `data: {"targetSessionId":"...","newPlayerName":"..."}` | 服务端 `broadcastToAll`，**必须**按 `targetSessionId == myClientId` 过滤；Ktor `response.body()` 会缓冲到底 ⇒ SSE 只能 `HttpURLConnection` + `BufferedReader` 逐行读，`readTimeout = 0` |
| `/rest/reportPlayback` | `GET` (Subsonic) | 扩展 query `outputDevice` / `volume` / `playMode` / `bilingualActive` | — | 只能经 `MusicService.reportPlaybackState()` 单一出口；播放中 **12s** 心跳一次（TTL = 剩余曲长 + 5s，间隔过长会提前掉出列表） |
| `/rest/getCoverArt.view` | `GET` (Subsonic) | `id` 可传 `coverArtId` / `albumId` / `songId` 任一 + `u/t/s/v/c/size` | 二进制图片 | 会话封面 URL 每次换 `salt` ⇒ Coil 缓存 key 必须用稳定 id，**严禁**用 URL 当 key |

---

## 3. 八大核心开发铁律 (AI 必读必守)

### 铁律 1: 播放进度与时长计算多级 Fallback (严禁单点依赖 `player.duration`)
- **冷启动状态陷阱**：应用冷启动时播放器处于未准备状态 (`STATE_IDLE`)，ExoPlayer 的 `player.duration` 会返回 `C.TIME_UNSET`（即负数 `-9223372036854775807L`）。
- **杜绝 `coerceAtLeast(1L)` 满圈假象**：严禁直接写 `val dur = player.duration.coerceAtLeast(1L)`！若历史进度为 111,709ms，`111709 / 1 = 111709` 经 `coerceIn(0f, 1f)` 会变成 `1.0f`，导致前端进度环显示 100%“已播完”假象。
- **强制多级回退规范**：
  ```kotlin
  val metaDurationMs = remember(metadata) {
      val ms = metadata?.durationMs ?: 0L
      if (ms > 0L) ms
      else (metadata?.extras?.getLong("duration")?.takeIf { it > 0 }?.times(1000L)) ?: 0L
  }
  val effectiveDuration = when {
      duration > 1000L -> duration
      metaDurationMs > 1000L -> metaDurationMs
      else -> 0L
  }
  val progress = if (effectiveDuration > 0L) {
      (currentPosition.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
  } else {
      0f
  }
  ```
- **冷启动位置预取**：若 `currentPosition == 0L`，必须在第一帧异步从 `LocalDataSettingsManager.playbackResumptionPlaylistWithStartPosition` 读取 `startPositionMs` 预填，实现 0 闪烁还原历史真实进度。

### 铁律 2: 懒加载 (Lazy Prepare) 与 Resumption 还原时序
- **启动零音频流开销**：应用打开或冷启动恢复播放列表时，仅调用 `player.setMediaItems(items)` 并 `player.seekTo(index, pos)`，必须保持 `playWhenReady = false`，**切勿提前调用 `player.prepare()`**。
- **懒准备触发机制**：仅在用户显式点击播放（Mini Player 封面或 Play 按钮）时触发 `player.play()`，由 ExoPlayer 动态拉取网络流，避免用户打开 App 瞬间消耗蜂窝流量或遭遇弱网超时阻塞。

### 铁律 3: Cloudflare 302 跨端口与 CDN 重定向必须预嗅探
- **OkHttp/ExoPlayer 限制**：部分反向代理（如 Cloudflare Tunnel、Nginx 跨端口转发、自签名 HTTPS 重定向）在返回 `302 Found` 时可能发生协议降级（HTTPS -> HTTP）或端口变化，ExoPlayer 底层 `DefaultHttpDataSource` 在安全沙箱限制下默认不会自动跨协议跟随重定向。
- **嗅探解法**：在 `MusicService.kt` 的 `followHttpRedirects(initialUri)` 中，使用标准 `HttpURLConnection`（配置禁用自动跟随并支持 5 级手动跟随循环），提取最终真实流地址再交付 ExoPlayer 播放。

### 铁律 4: Compose 双层 BackHandler 职责隔离规范
- **痛点**：全屏播放器在展开状态下（无论是封面还是歌词），按系统返回键必须平滑缩小为 Mini Player，绝不能直接触发退出或跳出混乱历史栈；而在首页收起状态下，按返回键应提示“再按一次退出应用”。
- **隔离解法**：严禁在一个 `BackHandler(enabled=true)` 中混杂所有逻辑，必须利用 Compose 的 `enabled` 动态注册机制分离为双层：
  ```kotlin
  val isPlayerExpanded by remember {
      derivedStateOf {
          scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded ||
          scaffoldState.bottomSheetState.targetValue == SheetValue.Expanded
      }
  }

  // 1. 全屏播放界面展开时优先拦截（队列 -> 详情 -> 折叠底栏）
  BackHandler(enabled = isPlayerExpanded) {
      if (nowPlayingViewModel.playQueueOpen.value) {
          nowPlayingViewModel.setPlayQueueOpen(false)
          return@BackHandler
      }
      coroutineScope.launch { scaffoldState.bottomSheetState.partialExpand() }
  }

  // 2. 底栏收起状态下的全局页面回退与首页防误触退出
  BackHandler(enabled = !isPlayerExpanded) {
      if (navController.currentBackStackEntry?.destination?.route == Screen.Home.route) {
          // 2秒内双击退出应用
      } else {
          navController.popBackStack()
      }
  }
  ```

### 铁律 5: 音频 MIME 自动嗅探与全格式直通
- 在 `Song.toMediaItem()` 构建时，必须保留流媒体原始元数据：
  - `mediaMetadata.extras.putString("format", format)`
  - `mediaMetadata.extras.putLong("duration", duration)`
  - `mediaMetadata.extras.putString("navidromeID", navidromeID)`
- 当 Navidrome 返回 FLAC, MP3, AAC, OGG, Opus, WAV 等多格式无损音频时，确保 ExoPlayer 能够基于扩展名或 MIME 动态匹配解码器，杜绝因 MIME 未指定导致的格式不支持报错。

### 铁律 6: Jukebox 多输出端调度四大铁律 (MPD / DLNA / 小米音箱)
- **铁律 6.1 (0 音量丢弃与 3 秒锁定防抢手)**：设备刚唤醒时常回报 `volume == 0`，客户端接收到 0 时必须丢弃，切忌覆盖本地音量；用户拖动音量需 200ms 防抖，且松手后 3 秒内屏蔽远端回写。
- **铁律 6.2 (本地静音与时钟驱动维持 MediaSession)**：切换远程时，本地 ExoPlayer 音量置 0 (`player.volume = 0f`)，绝不 stop，保持 MediaSession 和前台通知栏不丢，锁屏控制依然可用。
- **铁律 6.3 (依据 deviceType 动态降级)**：当 `deviceType == "xiaomi"` 时，音箱原生协议不支持 seek 且无进度回报，UI 进度条禁止拖拽并给予友好提示。
- **铁律 6.4 (无缝流转与无缝切回)**：切换设备时带上本地进度秒数 `position`；切回本机时调用 `POST /api/jukebox/select` (`"browser"`)，本地播放器恢复音量 `player.volume = 1f` 出声。

### 铁律 7: 歌词翻译与双语对照交互规范 (Lyrics Translation)
- **异步探测缓存**：进入歌曲播放后，协程异步请求 `GET /api/lyrics/translate/{songId}?lang=zh-CN`。
- **点亮小圆点**：若检测到已有翻译缓存，在悬浮「译」胶囊右上角点亮主题色指示圆点。
- **交互区分**：
  - **单击**：若有翻译，切换显示双语（原文加粗大字 + 译文浅色小字）或仅原文；若无翻译，立即发起翻译请求并呈现加载动效。
  - **长按**：发起携带 `"force": true` 的 POST 请求，强制服务端 AI 重新翻译当前曲目并刷新界面。

### 铁律 8: 每次构建必须同步产物到根目录 `music.apk` 并真机回测
- 每次完成功能性或 UI 修复后，严禁只停留在代码静态检查，必须完成完整的编译流水线：
  ```bash
  ./gradlew :app:assembleDebug --daemon
  cp app/build/outputs/apk/debug/app-debug.apk music.apk
  adb -s 192.168.31.242:5555 install -r music.apk
  ```
- 安装完成后，通过无线 ADB 执行 `am force-stop` 并重新拉起，抓取截屏核对视觉呈现。

---

## 4. 全局高频开发与调试命令速查

```bash
# 1. 编译 Android Debug 产物并归档
./gradlew :app:assembleDebug --daemon
cp app/build/outputs/apk/debug/app-debug.apk music.apk

# 2. 推送安装到无线测试设备 (当前测试机 IP: 192.168.31.242:5555)
adb -s 192.168.31.242:5555 install -r music.apk

# 3. 强制停止并重新拉起应用 (模拟冷启动测试)
adb -s 192.168.31.242:5555 shell am force-stop com.craftworks.music
adb -s 192.168.31.242:5555 shell am start -n com.craftworks.music/.MainActivity

# 4. 捕获当前设备屏幕图像用于视觉核验
adb -s 192.168.31.242:5555 exec-out screencap -p > /tmp/screen.png

# 5. 模拟系统返回键 (KEYCODE_BACK)
adb -s 192.168.31.242:5555 shell input keyevent 4

# 6. UI 树 Dump (排查无障碍焦点、按钮边界与视图层级)
adb -s 192.168.31.242:5555 shell uiautomator dump /sdcard/window_dump.xml
adb -s 192.168.31.242:5555 shell cat /sdcard/window_dump.xml

# 7. 查看播放服务、续播与原生扩展接口专属日志流
adb -s 192.168.31.242:5555 logcat -s JUKEBOX:V NAVIDROME_NATIVE:V LYRICS_TRANSLATE:V RESUMPTION:D
```

---

## 5. 铁律 9: 全屏播放页手势层 = playerOffset Animatable 单一数据源 (2026-09-26 重构，严禁回退 Sheet)

- **BottomSheetScaffold 已整体删除**（material3 1.5.0-alpha21 SheetState 无法程序拖拽 + 甩动 cur/tgt 说谎）。非 TV 分支唯一真源：`MainActivity` 内 `playerOffset: Animatable(0f)`（0=停屏下，1=全屏），`PlayerSettleSpec = spring(NoBouncy, 900f)` 定义在 MainActivity.kt 文件尾、ChoraDock import 共享。
- **三层视图读同一 o 且必须在 graphicsLayer/offset lambda 内读（零重组）**：播放页 `translationY=(1-o)*h` + `scale 0.92→1` + `alpha 0.75→1` + `clip+RoundedCornerShape((1-o)*48dp)`（圆角卡片展开）；首页 Scaffold `scale/alpha` 后退；dock `offset=o*dockHeightPx` + `alpha=(1-2.5o)` 淡出。
- **手势清单（新增/恢复任何一条都不得破坏其余）**：dock 行竖滑跟手展开、dock 行横滑**只动中间文字**（swipeX 传入 MiniPlayer，translationX*0.45+淡出；左滑=下一曲、右滑=上一曲，阈值 18% 行宽或 ±700px/s）、播放页竖滑跟手收起（阈值 0.55）、播放页标题块横滑切歌（**进场方向与 dock 镜像**，因文字左对齐）、歌词 LazyColumn 到顶后下拉经 `NestedScrollConnection.onPostScroll` 链回 playerOffset（未到顶只滚列表="越往下越拖不动"）、双层 BackHandler（铁律 4）收起用 `animateTo(0f, PlayerSettleSpec)`。
- **弹窗宿主必须作用域隔离**：队列/输出设备开关状态在 `PlayQueueSheetHost`/`JukeboxSheetHost`（MainActivity.kt 尾部私有 Composable）内部 collect，**严禁**在 Activity 顶层 collect（会重组整个 Scaffold，弹窗延迟实测 ~2s）。
- **封面配色统一走 CoverWash（严禁 dock 再走 HSL 重映射面板）**：`ui/theme/CoverWash.kt` 的 `rememberCoverWash`(1500ms) + `Modifier.coverWash`，`FULLSCREEN` 给播放页、`COMPACT` 给 dock 卡片与宽屏底栏，两者共用 `CoverThemeManager` 同一份 palette；dock 兜底才回落 `surfaceContainer*` 渐变。`CoverColorScheme` 的 `neutralSat=(sat*0.55).coerceIn(0.14f,0.34f)` 只服务 surface/弹窗，不再是 dock 观感因素。`coverThemeFlow` 关闭时主题、ambient、dock/wash、播放页 palette 必须同时回落。
- mini player 行左右元素同尺寸：封面圆环 52dp = 输出设备 chip 52dp（图标 26dp）。
- 详细表格与原理见 `docs/ARCHITECTURE.md` §7。

## 6. 本 Compose 版本 API 避坑速查 (改手势前必读)

1. `detectVerticalDragGestures.onDragEnd` **无速度参数** → 手动 EMA：`vel=vel*0.6+(dy/dt*1000)*0.4`。
2. `Velocity.y` 是 Float，**没有 `.value`**。
3. `GraphicsLayerScope` 无 `cornerRadius/roundRadius` → `clip=true; shape=RoundedCornerShape(px.dp)`。
4. `PointerInputScope.touchSlop` 不可引用 → 固定 10dp 换算。
5. `LocalConfiguration.current` 不能写进 `remember {}` lambda（非 Composable 上下文）。
6. `setMediaItems` / `SongHelper.play` 之后**立刻** `seekTo()` 会静默失效（窗口时长 `TIMEBAR_STATE_NOT_AVAILABLE` → `TIMEBAR_STATE_NO_SEEK`，不抛异常、logcat 干净）→ 必须等 `isCurrentMediaItemSeekable` 再跳，见铁律 10。
7. `AsyncImage` 加载**带签名参数的 URL**（每次 `salt` 不同）时必须显式 `memoryCacheKey` / `diskCacheKey` 为稳定 id，否则每轮轮询重新下图。
8. 完整清单见 `docs/ARCHITECTURE.md` §8。

---

## 7. 铁律 10: 跨设备播放接管 (Playback Handoff, 2026-09-27，navidrome2all fork)

`/api/playback/*` + `/api/events` + `/rest/reportPlayback` 扩展字段实现 Spotify Connect 式接力。**六个维度必须一起继承**：进度、播放意图、音量、输出设备、循环模式、双语歌词。

- **铁律 10.1（上报单一出口）**：所有 `/rest/reportPlayback` 必须走 `MusicService.reportPlaybackState(state, positionMs)`，禁止任何调用点自己拼字段；播放中 `startPlaybackHeartbeat()` 每 **12s** 报一次 `playing`（会话 TTL = 剩余曲长 + 5s）。
- **铁律 10.2（稳定身份）**：`NavidromeDataSource.getOrCreateClientUniqueId()` 在 `ChoraNetworkPrefs` 持久化 `chora-xxxxxxxx`，`Application.onCreate` 预热；所有 `/rest/*` 与 `/api/*` 带 `X-ND-Client-Unique-Id`。**sessionId 与 SSE targetSessionId 全靠它对齐**，重装/清数据后 ID 变了会看到"自己的会话在别人的列表里"。
- **铁律 10.3（接管顺序无空窗）**：先 `songRepository.getSong()` 拿流 → 远程输出先 `await` 完 `JukeboxManager.selectDeviceAwait()` → main 线程起播 + 落点 → **本地已出声之后**才 `POST .../takeover`。反过来会造成"原设备已停、本机未就绪"的静音空窗。`targetOutput` 非 `browser/local` 时曲目由 `MusicService.onMediaItemTransition` 转发，起始秒走 `MediaItem.extras["handoffStartMs"]`。
- **铁律 10.4（接管落点必须等 seekable）**：`seekWhenPrepared(player, startMs)` — 先试一次；未落点挂一次性 `Player.Listener`(`onTimelineChanged`+`onEvents`)，`isCurrentMediaItemSeekable` 为真再 `seekTo`；`currentPosition >= startMs - 1000` 或 `currentMediaItem.mediaId != expectedMediaId` 立刻 `removeListener`。裸 `seekTo` 会被静默丢弃，表现是"接管成功但从 0:00 重播"（**不报错、日志干净，极难发现**）。
- **铁律 10.5（线程铁律·崩溃级）**：ExoPlayer 只允许在创建它的线程（main）访问。跨线程读 `isPlaying`/`currentPosition`/`volume`/`repeatMode` 抛**未捕获**的 `IllegalStateException: Player is accessed on the wrong thread`，进程在播歌 12s 后（第一次心跳）直接崩。main 专用：`reportPlaybackState()`（player 回调与 `serviceMainScope` 合规，`serviceIOScope` **不合规**）；IO 协程内改用 `reportPlaybackInline()`，它内部 `withContext(Dispatchers.Main)` 取字段快照再走网络。
- **铁律 10.6（退出上报不能被自己的 cancel 砍掉）**：`onTaskRemoved`/`onDestroy` 的 `paused` 上报必须跑在 `shutdownScope`（`SupervisorJob + IO`，存活过 `onDestroy`），配 `NonCancellable` + `withTimeoutOrNull(2500)`。用 `serviceIOScope` 会被同方法末尾 `releasePlayerAndScopes()` 的 `cancel()` 掐断，其他设备就多挂一整首歌的"正在播放"。
- **铁律 10.7（SSE 只处理自己的事件）**：`/api/events?jwt=`（token 不能走 header，实测 401）只处理 `event: playbackHandoff`，且**必须** `ev.targetSessionId == myClientId` 才动作（服务端是 `broadcastToAll`）；命中即 `pause()` + Toast + 重拉列表，断线 5s 重连，`isActive` 与 socket 断开共同驱动退出。
- **铁律 10.8（别人会话的封面自己签）**：fork 只回 id。`withCoverArt()` 现签 `getCoverArt.view`，资产 id 三级兜底 `coverArtId → albumId → songId`；基础地址取 `NavidromeManager.resolveActiveServerUrl(getCurrentServer())`（与音频流同一选路，局域网可用时不绕公网）。URL 是 `@Transient`，不进 wire format；Coil 缓存 key 固定 `"handoff_cover_" + (coverArtId ?: songId)`；`onError` 回落喇叭图标（404/401/自签 TLS 都不能留白碟）。
- **铁律 10.9（omitempty ≠ 0）**：`volume`/`playMode`/`bilingual` 服务端 `omitempty`，DTO 必须可空；`effectiveVolume` 只在 `1..100` 内取值否则回落 100，避免把"没上报"读成"静音"。
- 详细表格与排查路径见 `docs/ARCHITECTURE.md` §3.6、§5.1、§6.5~§6.7。
