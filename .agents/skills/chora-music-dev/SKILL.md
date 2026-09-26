---
name: chora-music-dev
description: Chora 全栈全景架构中枢、Media3/ExoPlayer 播放内核、Navidrome/Subsonic 音频流协议、八大核心开发铁律与避坑速查指南
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
