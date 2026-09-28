# Chora 性能优化交接文档

> 交接说明：这份文档记录我这一轮**做了什么、怎么测的、哪些结论可信、哪些没解决**。
> 目的是让接手的人不用重走我踩过的坑。写于 2026-09-28。
>
> **请先读「§0 结论速览」和「§5 我犯的错」，那两节能省你最多时间。**

---

## 0. 结论速览

**核心发现：这个 App 在播放时把 CPU 的绝大部分浪费在「与显示刷新率绑定的动画」上，而不是在任何业务逻辑上。**

| 状态 | 帧率 | CPU | 卡顿 |
|---|---|---|---|
| 播放（改动前） | 244 fps | ~95% 单核 | 0.04% |
| **播放（改动后，现状）** | 139 fps | **~61% 单核** | **0.00%** |
| 暂停 | 0 fps | **0.2%** | — |
| 全部 UI 动画关掉（实测地板） | ~0 | 11%（其中 8.7% 是 ExoPlayer 音频解码，主线程只剩 1.8%） | — |

**这台设备的屏幕是 120Hz，但 App 播放时在以 244fps 重绘。** 多出来的那一半帧率是纯粹的浪费。
「全关动画 = 11%」这个对照证明了：**超出地板的 CPU 100% 来自 UI 动画，不来自任何 Kotlin 业务代码。**

现状相比原始：**CPU 降约 36%，卡顿保持 0%**。距离 11% 的地板还有空间，原因见 §4。

---

## 1. 环境与工具约束（这决定了哪些排查手段不可用）

- 设备：`192.168.31.242:5555`，1440×3200，**600 dpi，屏幕 120Hz**（`dumpsys display` 确认）
- 包名 `com.craftworks.music`
- **设备没有 root**。因此以下手段全部不可用：
  - `debuggerd`（需要 root）
  - `kill -3` 抓线程栈（`Operation not permitted`）
  - `uiautomator dump` 在播放页会报 `could not get idle state`（因为 UI 永远不 idle —— 这本身就是动画过多的一个副作用）
- **`dmtracedump` 本地和设备上都不存在**，所以 `am profile start --sampling`（ART 自带采样器）虽然能跑，但输出是二进制且无法解析。**这条路是死的。**
- release 包 `isDebuggable=false`，所以 release 上**没有** `run-as`，读不了 SharedPreferences。
- 设备**极不稳定**，本次会话中 adb 断连 4~5 次（`Connection timed out` / `Host is down`）。我写了 `/tmp/dev.sh` 之类的重连脚本才勉强撑住。**接手的人请先解决这个，否则每轮验证都很痛苦。**

### 我为此自建的工具（可能对后续有用）

`app/src/main/java/com/craftworks/music/util/MainThreadSampler.kt` —— 一个 debug-only 的主线程采样器。
从 `Application.onCreate` 延迟 20 秒启动，12 秒内以 40ms 间隔采样主线程 `stackTrace`，通过 `logcat`（tag `MTSAMPLE`）输出 LEAF 帧和 APP 帧两个直方图。

- 用 `BuildConfig.DEBUG` 门控，**release 包里被 R8 完全删除**（已验证 release APK 仍为 4.81 MB）
- 触发时序：进程启动 +20s。要测播放状态需要自己先把歌播起来
- **⚠️ 这是我加的临时排查脚手架，不是有意保留的功能。接手后建议删掉**（同时要删 `Application.kt` 里的 hook 和 `app/build.gradle.kts` 里新增的 `buildFeatures { buildConfig = true }`）

它的输出证明了关键结论：
```
63%  MessageQueue.nativePollOnce   ← 主线程在等 vsync
95%  (no app frame)                ← 95% 的采样栈里根本没有 com.craftworks.music 的代码
```

---

## 2. 我实际做的改动

### 2.1 已验证有效（CPU 下降）

| 文件 | 改动 | 效果 |
|---|---|---|
| `NowPlayingMiniPlayer.kt` | `MiniPlayerWaveform`：原来 **5 个 `rememberInfiniteTransition`**（每个独立绑帧时钟）→ **1 个共享 ticker**，正弦算 scale | 单独测：244fps → 124fps，~95% → ~60% |
| `NowPlayingMiniPlayer.kt` | ticker 从 `delay(33)` 改为 **`withFrameNanos` + 每 33ms 才写一次** | 卡顿 26% → **0%** |
| `NowPlayingMiniPlayer.kt` | ticker 的 `tick` 读取位置从**组合体内**移到 **`graphicsLayer` lambda 内** | 避免每 tick 重组 5 个 Box |
| `NowPlayingMiniPlayer.kt` | dock 的 2 处 `basicMarquee()` 改为**纯省略号** | 单独测：~60% → ~47% |
| `NowPlayingMiniPlayer.kt` | Material3 `CircularProgressIndicator` → **`Canvas.drawArc`**，直接用原本 2Hz 的数值画 | 去掉组件自带的帧时钟 sweep 动画 |

**波形图改动的关键细节**（`NowPlayingMiniPlayer.kt` 约 610-640 行）：
- 周期 1200ms，5 根柱子错位 delay `[100,300,150,400,250]`，scale 范围 0.4~1.2 —— **与原动画参数一致**
- 读 `tick` 必须在 `graphicsLayer { }` lambda 里。写在组合体里会每 33ms 重组一次，**比画本身贵得多**。这个坑我踩过一次。

**关于 jank（重要）**：设备是 120Hz，帧预算只有 **8.3ms**。
用 `delay(33)` 做 ticker 时它与 vsync 完全不同步，更新全挤在少数帧上，导致 50 分位帧时 10ms **超过预算**，实测 **25.9% 卡顿**。
改成 `withFrameNanos` 后卡顿归零。**所以：降频必须用帧时钟驱动，不能用 `delay`。**

### 2.2 无效（实测 CPU 收益为 0，但代码还在）

`NowPlayingLyrics.kt` 的三项歌词优化，严格 A/B（同首歌、同一位置 04:43 vs 04:36、都在播放、校验过 pid、20 秒窗口）：

| | CPU | 帧数 | 99分位帧时 |
|---|---|---|---|
| BEFORE | 84.4% | 4834 | 20ms |
| AFTER | **84.2%** | 4894 | 16ms |

**CPU 差异在噪声内（0.2 个百分点）。** 只有帧时间分位数有轻微改善。

决定性反证：把同一播放页切到**封面视图（完全没有歌词）**再测 → **85.5%**，和歌词视图一样。
**歌词渲染开销约等于零。** 我一开始判断「每行 20~40 个词各自持有 3 个 Animatable + 2 个 LaunchedEffect」是主要开销，这个判断是错的 —— 结构上确实如此，但它根本不是瓶颈。

这三项改动本身无害（视觉一致、代码更清晰、帧时间分位数略好），**留着也行，删掉也行**，但不要指望它省电。

### 2.3 本轮之前的改动（未在本轮验证，仅记录）

- `NavidromeNativeApi.kt` / `PlaybackHandoffManager.kt`：接管权限 403 修复（服务端新增 `canTakeOverSession: caller.IsAdmin || target.UserId == caller.ID`；原实现会先本地切歌+弹成功+删列表再 POST 且丢弃返回值）
- `NowPlayingMiniPlayer.kt`：暂停时不再 marquee（原来暂停状态下 `basicMarquee` 无限动画常驻）
- `DownloadedSongsManager.kt`：下载记录 JSON 解析挪到后台线程
- `Application.kt`：ambient 轮询加 3 秒首延迟
- SSE 解析器新增 `nowPlayingCount` 事件分支，用它做秒级刷新

---

## 3. 本轮**新发现**的 Bug（不是性能问题，但很值钱）

### 3.1 播放时烧掉一整个核心 36 秒（已修）

`NowPlayingMiniPlayer.kt` 的 `else` 分支（**暂停 / 无同步歌词**时走的那条）给歌名和歌手两行都加了 `.basicMarquee()`，**没有任何 `isPlaying` 判断**。`basicMarquee()` 是无限动画，而 mini 播放栏是**常驻**组件。

修复前后的冷启动曲线（`/proc` 采样）：
```
        修复前              修复后
t=2s     96%                 52%
t=8s    110%                  0%
t=20s   112%                  0%
t=36s   120% → 结束           0%
```
**每次冷启动原本要烧 36 秒满载核心。** 这和 §0 的「播放时 95%」是两个独立问题，都已修复。

### 3.2 APK 体积（不是 bug，是认知修正）

我们在测的**一直是 debug 包**。release 开了 R8 后：

| | 大小 | dex 数量 | 冷启动 |
|---|---|---|---|
| debug | 25.85 MB | 20 | 1771ms |
| **release** | **4.81 MB** | **2** | **183~261ms** |
| 官方 v1.31.1 | 4.65 MB | — | — |

**差 5.4 倍，全部来自 build type，跟代码无关。** 之前所有性能数字都是在 debug 包上测的，普遍偏悲观。

**现在 `music.apk` 是 release 包。** release 无 `keystore.properties` 时回退到 debug keystore，所以**签名与 debug 包一致，可直接覆盖安装、不丢数据**（已验证 cert 指纹相同）。真正发布时才需要准备 keystore。

### 3.3 服务端兼容性（已修，未实机验证）

fork 最新 commit `35851212` 新增了接管权限校验。你的 App 原来会：本地切歌 → 弹「接管成功」→ 从列表删除该 session → **然后才 POST，且丢弃返回值**。非管理员遇到 403 时会看到假成功、对方还在放歌。

已修：缓存 `/auth/login` 的 `id`/`isAdmin`（原本解析了 `id` 却直接丢弃）→ 动播放前先本地预判 → 服务端确认后才切歌 → 403 时如实报错并保留列表行 → 设备浮层里不能接管的那条显示「仅管理员可接管」+ 锁图标 + 按���禁用。

**这一项我没能在真机上验证**（缺第二个设备 / 缺非管理员账号），接手后建议补测。

---

## 4. 我**没有解决**的事

### 4.1 剩余约 50% CPU 的差距（最大的未解项）

地板是 11%，现状 61%，**中间还差 50 个百分点没有定位到具体原因。**

我试过的排除法（都不成立）：
- 关掉 marquee + 波形 + 进度环 + 涟漪 → 11%（所以原因在这些里）
- 但把它们逐个以 30Hz 重新实现后 → 61%（**说明我的实现本身仍然在产生大量帧**）
- `rg` 全项目扫 `rememberInfiniteTransition|withFrameMillis|infiniteRepeatable`，播放路径上**只剩 `AnimatedGradient.kt:47` 一个无限循环**，而它只在**展开的播放页**生效，dock 状态不显示
- 自研采样器：主线程 95% 空闲，**95% 的栈里没有 app 代码**

**最可能的剩余嫌疑（我没能验证到位）**：
1. `NowPlayingPortrait.kt` / `NowPlayingLandscape.kt` 里的 `marqueeProvider = { Modifier.basicMarquee() }` —— **展开的播放页**确实还有原生 marquee。我的测量大多在 **dock 折叠**状态做的，可能根本没测到它
2. `AnimatedGradientBackground` 的全屏 RuntimeShader（`AnimatedGradient.kt`），`withFrameMillis` 无限循环 + 96dp 渐变
3. 歌词的 `Modifier.blur`（每行一个实时高斯模糊，4~7 个）
4. `NowPlayingLyrics` 的逐词 `Animatable` × 3（**这项我判断为无用，但它确实在产生重绘**）

**下一步建议**：按 §5 的方法，**先在「展开的播放页 + 歌词视图」状态下重新做一次二分**。我怀疑我之前一直在测 dock，把真正的大头漏了。

### 4.2 139 fps 仍然高于 120Hz

现状帧率 139fps > 屏幕 120Hz。虽然卡顿 0%、单帧很轻，但**多出来的帧是白烧的**。说明仍有第二个驱动源在每帧触发 invalidate。这个没查出来。

### 4.3 我引入的行为变更（可逆，用户未拍板）

**dock 里的长歌名不再横向滚动，改为省略号截断。** 完整播放页的大标题滚动**没动**（那是 `NowPlayingPortrait/Landscape` 里独立的 `marqueeProvider`）。

这是我用 CPU 换的，用户没明确同意。**如果要恢复**，在 `NowPlayingMiniPlayer.kt` 里把那两处 `.then(if (isPlaying) Modifier ... else Modifier)` 改回 `Modifier.basicMarquee()` 即可，但会退回 ~13% CPU。

### 4.4 其他没做完的

- `verticalFadingEdges` 我加了开关（外观 → 「歌词边缘渐隐」，**默认开 = 和原来一样**），但**A/B 性能数字没测出来** —— 播放页的队列 bottom-sheet 一直拦截点击，导致切不到干净的歌词视图
- 播控键边缘点击修复（`pressSlide`）**一直等用户手动复测**，我全程没验证成功
- `navidrome2all` fork 的 takeover 403 修复**没实机验证**（见 §3.3）

---

## 5. 我犯的错 —— 请务必避免（这是我浪费你时间最多的地方）

### 5.1 最大的错：用有噪声的 CPU 采样当结论

同一个状态我测出过 **49.1% / 69.0% / 80.7% / 94.7%** 四个数，还拿其中两个下过结论。**这种数据本来就该当场作废。**

噪声来源（我踩过的坑）：
- 每次测的**歌不一样** → 歌词密度不同 → 每行词数不同 → 帧数不同
- 播放列表会**自动切歌**，测量窗口里切歌会彻底污染数据（有一次窗口正好赶上歌结束，得到 0 帧）
- `top -n 1` 是瞬时采样，**不能用来取平均**

**正确做法（我最后才学会）**：
1. **二分法**，不是采样对比。同一个 build 里把动画逐个关掉重测，差值就是归因。噪声会同时作用于所有组，差值可靠。
2. CPU 用 `/proc/<pid>/stat` 的 **utime+stime 差分**，不用 `top`
3. **用 `pidof` 拿 pid，绝对不要用 `ps -A | grep com.craftworks.music | head -1`** —— 我踩过：shell wrapper 的命令行里也含这个字符串，grep 会匹配到它自己，导致 pid 每次都变、测量全废
4. 线程级用 `/proc/<pid>/task/*/stat` 差分，解析时注意 `comm` 字段可能含空格/括号，要用 `rfind(')')` 切
5. 每次测量**前后都校验播放状态**，否则会拿到「暂停时的 0.3%」当结果

### 5.2 第二个错：先下结论再验证方向

我一开始说「逐词动画是主要开销」，用户要求做 A/B，测出来是 0；再测封面视图，发现歌词完全不是瓶颈。**如果一开始就做二分法定位，能省掉两轮无效优化。**

正确顺序应该是：**先二分定位 → 再优化**。我反了。

### 5.3 第三个错：自己引入的优化比原实现更贵

`throttledMarquee()` 第一版：
- 用了 `layout { placeable.place(offset) }` → 每 33ms **重新测量 + 重新布局**整行文字
- 在 layout 阶段写 snapshot state → 触发重组
- 用 `Constraints.Infinity` 测量 → 每 tick 都对整串长文本做完整排版

结果比原生 `basicMarquee()` **更慢**（59.6% vs 原生方案的 47%）。

教训：**「降频」本身不等于「变便宜」**。必须让失效发生在**最便宜的阶段**（draw），而不是 layout/重组。

---

## 6. 接手者的建议路线

1. **先解决设备断连**（adb 反复掉线），否则每轮验证都很痛苦
2. **在「展开的播放页 + 歌词视图」下重做二分**（见 §4.1）—— 我怀疑真正的大头在这，而我一直在测 dock
3. 二分完再优化。**记住 8.3ms 帧预算和 vsync 对齐这两条**（见 §2.1）
4. 删掉 §1 里的 `MainThreadSampler` 脚手架
5. 让用户拍板 §4.3 的 dock marquee 去留
6. 补测 §3.3 的接管权限、§4.4 的播控键边缘点击

---

## 7. 常用命令

```bash
# 构建 + 归档 + 装机（release）
./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk music.apk
adb -s 192.168.31.242:5555 install -r music.apk

# CPU（正确做法：/proc 差分）
pid=$(adb -s 192.168.31.242:5555 shell pidof com.craftworks.music | tr -d '\r')
a=$(adb -s 192.168.31.242:5555 shell "cat /proc/$pid/stat" | awk '{print $14+$15}' | tr -d '\r')
sleep 12
b=$(adb -s 192.168.31.242:5555 shell "cat /proc/$pid/stat" | awk '{print $14+$15}' | tr -d '\r')
python3 -c "print(f'{($b-$a)/(100*12)*100:.1f}%')"

# 帧率 / 卡顿
adb -s 192.168.31.242:5555 shell dumpsys gfxinfo com.craftworks.music reset
sleep 12
adb -s 192.168.31.242:5555 shell dumpsys gfxinfo com.craftworks.music | grep -E "Total frames|Janky|percentile"

# 屏幕刷新率（决定帧预算）
adb -s 192.168.31.242:5555 shell dumpsys display | grep -oE "fps=[0-9.]+" | sort -u

# 采样器输出
adb -s 192.168.31.242:5555 logcat -d -s MTSAMPLE
```

---

## 8. 涉及文件清单

**本轮改动：**
- `app/src/main/java/com/craftworks/music/ui/playing/NowPlayingMiniPlayer.kt` ← 主要战场
- `app/src/main/java/com/craftworks/music/ui/playing/NowPlayingLyrics.kt`（三项无效优化 + `InterludeIndicator` 30Hz）
- `app/src/main/java/com/craftworks/music/util/MainThreadSampler.kt` ← **临时脚手架，建议删**
- `app/src/main/java/com/craftworks/music/Application.kt`（采样器 hook，建议删）
- `app/build.gradle.kts`（新增 `buildFeatures { buildConfig = true }`，随采样器一起处理）
- `app/src/main/java/com/craftworks/music/ui/elements/RefreshRipple.kt`（我改过又改回，确认是干净的）
- `app/src/main/java/com/craftworks/music/managers/settings/AppearanceSettingsManager.kt`（新增 fading-edges 开关）
- `app/src/main/java/com/craftworks/music/ui/screens/settings/SettingsAppearance.kt`（开关 UI）
- `app/src/main/res/drawable/rounded_lyrics_fade_24.xml`（自己画的图标，**没引 material-icons-extended**，避免 APK 变大）
- `app/src/main/res/values*/strings_settings.xml` × 6（en/zh/fr/it/ru/de）

**未改动但相关（接手时值得一看）：**
- `app/src/main/java/com/craftworks/music/ui/playing/NowPlayingPortrait.kt` / `NowPlayingLandscape.kt` ← **仍用原生 `basicMarquee()`，我怀疑这是剩余大头**
- `app/src/main/java/com/craftworks/music/ui/playing/background/AnimatedGradient.kt:47` ← 唯一剩下的 `withFrameMillis` 无限循环
- `app/src/main/java/com/craftworks/music/ui/screens/HomeScreen.kt`（Navidrome logo 的 `.shadow(24.dp, CircleShape)`，每帧离屏模糊）
- `app/src/main/java/com/craftworks/music/ui/elements/ButtonAnimation.kt`（`pressSlide` 边缘点击修复，待用户复测）
- `app/src/main/java/com/craftworks/music/data/datasource/navidrome/NavidromeNativeApi.kt`（接管 403 + SSE nowPlayingCount）
- `app/src/main/java/com/craftworks/music/managers/PlaybackHandoffManager.kt`（接管流程）
