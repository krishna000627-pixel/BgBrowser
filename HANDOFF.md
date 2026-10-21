# BgBrowser: handoff

Paste this file and the latest `BgBrowser_vN.zip` at the start of the next chat.

## What the app is
Android browser (Kotlin, WebView, no Compose) that keeps a web page running in the background, mainly sites that read text aloud (web TTS) and auto-scroll/auto-next. The page never registers a media session, so the app does it for the page.

## Current state (v6)
- Package `com.bgbrowser`, minSdk 26, target/compile 34, AGP 8.5.2, Kotlin 1.9.24, Gradle 8.10.2 in CI. Tor: `info.guardianproject:tor-android:0.4.8.17.2` + `jtorctl:0.4.5.7` (driven by reflection in `TorManager.kt`, so only dependency resolution can break the build; 0.4.8.12 does not exist on Maven Central or Google, so v4 moved to 0.4.8.17.2; if resolution fails again check central.sonatype.com for a listed version).
- Three processes, one `BrowserActivity` base: `MainActivity` (normal), `IncognitoActivity` (`:incognito`), `TorActivity` (`:tor`). Each has its own WebView data dir suffix (wiped in `App.onCreate`), its own `KeepAliveService` subclass and its own `Bridge`. Incognito/Tor need Android 9+.
- `Prefs.kt`: file-backed settings readable from all processes. `History.kt`: simple history (normal mode only).
- UI: dark, bottom address bar, floating pill (play/pause, auto mode, speed, next), status chips, tab sheet, menu sheet, first-run checklist. All built in code (`Ui.kt` helpers, vector icons in `res/drawable`).
- `SettingsActivity.kt`: multipage. Home lists Browsing, Controls & display, Privacy & cookies, Auto mode, Background, Text-to-speech, Tor, About; each page is the same activity with extra `page`. About has version, how-to, welcome tour, checklist, GitHub, copy version info, reset all settings.
- `WelcomeActivity.kt`: 4-page first-launch tour (what it is, how it works, permissions, quick choices). Sets `firstRunDone` on finish. The old checklist sheet is still in the menu and About.
- Floating pill is optional (`showPill`, default off). With it off, Pause/Resume, Auto mode, Auto speed and Next page are at the top of the menu. Other new prefs: `pillFade`, `showChips`, `keepScreenOn`, `restoreTabs`. Menu also has Copy link and Share link.
- Stale-file trap: `cp -r` never deletes. v3 moved `MainActivity` into `Modes.kt`, so an old `MainActivity.kt` caused a redeclaration error until removed by hand.
- TTS: `<queries>` for TTS, startup engine/voice-data check with install chip, speech state mirrored to the MediaSession, optional "Native TTS bridge" (off by default, experimental).
- Tor: dead-port proxy until bootstrapped and verified via check.torproject.org, WebRTC removed by injected script, HTTPS-only, request blocking while Tor is down, heartbeat re-blocks if circuits drop.
- v2 bug fixed: the "next" button regex had `\\b` in a raw string, so text-based next never matched.

## Not verified yet (no device test)
Tor library API names (reflection), `ProxyController` with `http://` tunnel / `socks5://` rules, native TTS shim on real sites, speechSynthesis surviving screen lock, WebView data dir wipe paths.

## How we work (same as QuestBlock)
- No compiler in chat. GitHub CI is the first build; user pastes errors back, fix only those.
- Short replies. Everything in one update. Max one question.
- Every update is a zip `BgBrowser_vN.zip` containing folder `BgBrowser_vN/`. Bump `versionCode` by 1 and `versionName` to `N.0` every time, and bump the zip name by 1. Never reuse a name.
- End every reply with the Termux update command:
  `cd ~ && unzip -oq ~/storage/downloads/BgBrowser_vN.zip -d ~/bgN && cp -r ~/bgN/BgBrowser_vN/. ~/BgBrowser/ && rm -rf ~/bgN && cd ~/BgBrowser && bash termux/release.sh "vN: what changed"`
- Keep `termux/setup.sh`, `termux/release.sh`, `.github/workflows/build.yml` working (signed APK as GitHub release, keystore in `~/.bgbrowser`, repo `krishna000627-pixel/bgbrowser`).
- Avoid heavy new dependencies; the build must stay reliable.

## Known limits
- Swiping the app away from recents kills the page.
- Auto-next is a text guess; custom selector setting exists.
- Native swipe mode only works with the screen on; scroll mode works with it off.
- Some phone makers need manual unrestricted-battery/autostart settings.
- Audio over Tor can stutter; Google sites may show captchas over Tor.
- Tor is weaker than Tor Browser against fingerprinting and logins.
- No downloads in private tabs, no fullscreen video yet.
