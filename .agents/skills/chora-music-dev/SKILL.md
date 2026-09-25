---
name: chora-music-dev
description: Chora 全栈全景架构中枢、Media3/ExoPlayer 播放内核、Navidrome/Subsonic 音频流协议、六大核心开发铁律与避坑速查指南
---

# Chora (Navidrome / Local Music Player) 全栈开发与架构专家指南

本技能为 **Chora**（面向 Android 与 Android TV 的高品质本地与 Navidrome/Subsonic 流媒体播放器）核心全栈中枢指南。旨在让任何接手 AI 或开发者在极低 Token 消耗下掌握整个项目的架构拓扑、模块职责划分、数据流向以及严守核心开发铁律。

---

## 1. 系统模块与代码组织全景

| 模块名称 | 物理路径 | 技术栈 | 核心职责 |
| :--- | :--- | :--- | :--- |
| **主入口与调度** | `app/src/main/java/.../MainActivity.kt` | Jetpack Compose, Material 3, Navigation | 顶层单 Activity 架构、全局路由导航、系统双层 BackHandler 拦截、媒体控制器动态绑定 |
| **播放内核服务** | `app/src/main/java/.../player/MusicService.kt` | AndroidX Media3 1.5+, ExoPlayer | 前台媒体库服务 (`ChoraMediaLibraryService`)、MediaSession、跨端口/CF 302 重定向嗅探、Scrobble 听歌打卡、历史续播状态持久化 |
| **Navidrome 核心** | `app/src/main/java/.../managers/NavidromeManager.kt`<br>`app/src/main/java/.../data/MediaNavidromeProvider.kt` | OkHttp3, Retrofit, Subsonic REST API | 多服务器管理、动态 URL 穿透解析（局域网/公网自动选路）、Token/Salt 签名计算、歌曲/专辑/歌单元数据拉取与流代理 |
| **本地状态管理** | `app/src/main/java/.../managers/settings/` | Jetpack Preferences DataStore, Kotlin Flow | 播放续播状态 (`LocalDataSettingsManager`)、外观与动效配置 (`AppearanceSettingsManager`)、缓存与网络配置 |
| **全屏与底栏播放 UI** | `app/src/main/java/.../ui/playing/` | Jetpack Compose Expressive, Coil, Canvas | 响应式底栏 Mini Player、圆形进度环自适应、大屏全屏播放器、歌词逐字平滑滚动 (`LyricsState`)、播放队列与专辑插图色彩提取 |
| **TV 交互适配** | `app/src/main/java/.../ui/playing/tv/`<br>`app/src/main/java/.../ui/screens/tv/` | Compose for TV, D-Pad Navigation | 遥控器十字焦点导航、大屏进度滑块、TV 专属专辑与歌单展示 |
| **产物构建与发布** | 项目根目录 `build.gradle.kts` | Gradle 8.9+, AGP 8.7+, Kotlin 2.0+ | 双端自适应打包，产物自动归档根目录 `music.apk` 并支持无线 ADB 极速热推 |

---

## 2. 六大核心开发铁律 (AI 必读必守)

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

### 铁律 6: 每次构建必须同步产物到根目录 `music.apk` 并真机回测
- 每次完成功能性或 UI 修复后，严禁只停留在代码静态检查，必须完成完整的编译流水线：
  ```bash
  ./gradlew :app:assembleDebug --daemon
  cp app/build/outputs/apk/debug/app-debug.apk music.apk
  adb -s 192.168.31.242:5555 install -r music.apk
  ```
- 安装完成后，通过无线 ADB 执行 `am force-stop` 并重新拉起，抓取截屏核对视觉呈现。

---

## 3. 全局高频开发与调试命令速查

```bash
# 1. 编译 Android Debug 产物并归档
./gradlew :app:assembleDebug --daemon
cp app/build/outputs/apk/debug/app-debug.apk music.apk

# 2. 推送安装到无线测试设备 (当前测试机 IP: 192.168.31.242:5555)
adb -s 192.168.31.242:5555 install -r music.apk

# 3. 强制停止并重新拉起应用 (模拟冷启动测试)
adb -s 192.168.31.242:5555 shell am force-stop com.craftworks.music
adb -s 192.168.31.242:5555 shell monkey -p com.craftworks.music -c android.intent.category.LAUNCHER 1

# 4. 捕获当前设备屏幕图像用于视觉核验
adb -s 192.168.31.242:5555 exec-out screencap -p > /tmp/screen.png

# 5. 模拟系统返回键 (KEYCODE_BACK)
adb -s 192.168.31.242:5555 shell input keyevent 4

# 6. 点击底栏 Mini Player 展开全屏播放器 (1440x3200 屏幕分辨率标准坐标)
adb -s 192.168.31.242:5555 shell input tap 600 2675

# 7. 查看播放服务与续播专属日志流
adb -s 192.168.31.242:5555 logcat -s RESUMPTION:D Chora:D ExoPlayer:W
```
