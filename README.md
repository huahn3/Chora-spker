![Logo](https://github.com/CraftWorksMC/Chora/blob/master/Github/Images/ChoraBannerTransparent.png?raw=true)

# Chora - Modern Navidrome & Local Music Player

A fast, lightweight, and modern music streaming client for Android and Android TV, built with Jetpack Compose and AndroidX Media3. Stream high-fidelity audio from Subsonic/Navidrome servers or listen to local music with seamless offline caching, synchronized lyrics, and dynamic Material You aesthetics.

<p align="center">
  <a href='https://play.google.com/store/apps/details?id=com.craftworks.music&pcampaignid=pcampaignidMKT-Other-global-all-co-prtnr-py-PartBadge-Mar2515-1'><img width=180px alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png'/></a>
  &nbsp;&nbsp;
  <a href="https://f-droid.org/packages/com.craftworks.music/"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="54"></a>
</p>

---

## ✨ Features

- 🎧 **Navidrome / Subsonic Protocol**: Full compatibility with Navidrome and Subsonic-compliant music servers (transcoding, token-based authentication, server cover art).
- ⚡ **Local-First Resumption**: Instant 0ms startup without blank state; fast-loads last played track, album art, and progress accurately from DataStore.
- 📱 **Modern Jetpack Compose UI**: Expressive Material 3 design, dynamic blur palette generation based on album art, interactive circular progress ring, and smooth spring animations.
- 👆 **Gesture-Driven Floating Dock**: Finger-following swipe-up to unfurl the fullscreen player (swipe-down or lyrics pull-down to collapse), horizontal swipe on the mini row to change track with text cross-slide transitions, and cover-art-tinted theming across every screen.
- 📺 **Android TV Support**: Optimized TV layout with D-Pad remote navigation, large album displays, and TV player interface.
- 📝 **Synchronized Lyrics**: Word-synced, line-synced, and plain lyrics powered by LRCLIB and NetEase Music.
- 🔄 **Cloudflare & Reverse Proxy Resilient**: Built-in HTTP 302 cross-protocol/cross-port sniffing to smoothly stream behind Cloudflare Tunnels and reverse proxies.
- 🌐 **Offline Downloads & Local Library**: Download tracks and albums directly to your device for offline listening.
- 🚗 **Android Auto Support**: Safe and responsive in-car playback.
- 🔁 **Playback Handoff (Multi-Device Takeover)**: Every device playing from the same server shows up in the output-device sheet with its track and position; tap **Take over** to resume exactly where the other device left off (progress, play/pause intent, volume, repeat mode, output device and bilingual-lyrics state all inherit), the previous device pauses itself via a server push event, and a Jukebox speaker is re-seeded at the handed-off second instead of restarting at 0:00.

---

## 🛠️ Architecture & Developer Guidelines

For developers and AI coding assistants joining the project, comprehensive guides are available:

- 📖 **Architecture Manual**: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- 🤖 **AI Assistant Skill**: [.agents/skills/chora-music-dev/SKILL.md](.agents/skills/chora-music-dev/SKILL.md)

### 📌 Core Development Rules

1. **Duration & Progress Fallback**:
   Cold-starting ExoPlayer keeps it in `STATE_IDLE` where `player.duration` returns `C.TIME_UNSET` (negative). Never rely purely on `player.duration.coerceAtLeast(1L)`. Always use `effectiveDuration` falling back to `mediaMetadata.durationMs`.
2. **Lazy Prepare Resumption**:
   When restoring state on startup, assign `mediaItems` and `seekTo()` but keep `playWhenReady = false` without calling `prepare()`, eliminating background network bandwidth waste.
3. **Dual BackHandler Structure**:
   Full-screen expanded player (album cover/lyrics/queue) must intercept the system back key to smoothly collapse into the Mini Player (`playerOffset.animateTo(0f, PlayerSettleSpec)`), while collapsed states handle page navigation and double-tap home exit.
4. **playerOffset Animatable Gesture Layer**:
   The fullscreen player is a custom follow-the-finger overlay driven by a single `Animatable(0f..1f)` in `MainActivity` (BottomSheetScaffold was removed: material3 alpha SheetState can't be dragged programmatically and desyncs on flings). All rise/fall visuals (player unfurl, home parallax, dock slide-out) must read the same value inside `graphicsLayer`/`offset` lambdas — never collect popup or expansion state at the activity top level (recomposition storm delays popups ~2s). See `docs/ARCHITECTURE.md` §7–§8 for the full gesture matrix and Compose API pitfalls.

---

## 🚀 Quick Start & Building

### Prerequisites
- JDK 17 or higher
- Android SDK Platform 35
- Gradle 8.9+ (included via wrapper)

### Build Commands
```bash
# 1. Assemble Debug APK
./gradlew :app:assembleDebug --daemon

# 2. Archive to root directory
cp app/build/outputs/apk/debug/app-debug.apk music.apk

# 3. Install to connected device or wireless ADB
adb install -r music.apk

# 4. Launch and test
adb shell monkey -p com.craftworks.music -c android.intent.category.LAUNCHER 1
```

---

## 📸 Screenshots

### Mobile
<p align="center">
    <img src="https://github.com/CraftWorksMC/Chora/blob/master/Github/Images/Now-Playing-Screen.png?raw=true" width=200>
    <img src="https://github.com/CraftWorksMC/Chora/blob/master/Github/Images/Now-Playing-SyncedLyrics.png?raw=true" width=200>
    <img src="https://github.com/CraftWorksMC/Chora/blob/master/Github/Images/HomeScreen.png?raw=true" width=200>
    <img src="https://github.com/CraftWorksMC/Chora/blob/master/Github/Images/AlbumScreen.png?raw=true" width=200>
    <img src="https://github.com/CraftWorksMC/Chora/blob/master/Github/Images/ArtistScreen.png?raw=true" width=200>
    <img src="https://github.com/CraftWorksMC/Chora/blob/master/Github/Images/RadioScreen.png?raw=true" width=200>
    <img src="https://github.com/CraftWorksMC/Chora/blob/master/Github/Images/PlaylistScreen.png?raw=true" width=200>
    <img src="https://github.com/CraftWorksMC/Chora/blob/master/Github/Images/SongScreen.png?raw=true" width=200>
</p>

### Android TV
<p align="left">
    <img src="https://github.com/CraftWorksMC/Chora/blob/master/fastlane/metadata/android/en-US/images/tvScreenshots/1.png?raw=true" width=400>
    <img src="https://github.com/CraftWorksMC/Chora/blob/master/fastlane/metadata/android/en-US/images/tvScreenshots/2.png?raw=true" width=400>
    <img src="https://github.com/CraftWorksMC/Chora/blob/master/fastlane/metadata/android/en-US/images/tvScreenshots/3.png?raw=true" width=400>
    <img src="https://github.com/CraftWorksMC/Chora/blob/master/fastlane/metadata/android/en-US/images/tvScreenshots/4.png?raw=true" width=400>
</p>

---

## 🌐 Community & Translations

- Help translate Chora on [Crowdin](https://crowdin.com/project/chora)
- Support the upstream project: [PayPal Donation](https://www.paypal.com/donate/?hosted_button_id=REWCVJBKECU34)

Made with ❤️ in Italy & enhanced by the Open Source Community.
