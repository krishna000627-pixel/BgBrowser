# BgBrowser: handoff

Paste this file and the latest `BgBrowser_vN.zip` at the start of the next chat.

## What the app is
Android browser (Kotlin, WebView, no Compose) that keeps a web page running in the background, mainly sites that read text aloud (web TTS) and auto-scroll/auto-next. The page never registers a media session, so the app does it for the page.

## Current state (v10)
- Package `com.bgbrowser`, minSdk 26, target/compile 34, AGP 8.5.2, Kotlin 1.9.24, Gradle 8.10.2 in CI. Tor: `info.guardianproject:tor-android:0.4.8.17.2` + `jtorctl:0.4.5.7` (driven by reflection in `TorManager.kt`, so only dependency resolution can break the build; 0.4.8.12 does not exist on Maven Central or Google, so v4 moved to 0.4.8.17.2; if resolution fails again check central.sonatype.com for a listed version).
- Three processes, one `BrowserActivity` base: `MainActivity` (normal), `IncognitoActivity` (`:incognito`), `TorActivity` (`:tor`). Each has its own WebView data dir suffix (wiped in `App.onCreate`), its own `KeepAliveService` subclass and its own `Bridge`. Incognito/Tor need Android 9+.
- `Prefs.kt`: file-backed settings readable from all processes. `History.kt`: simple history (normal mode only).
- UI: dark, bottom address bar, floating pill (play/pause, auto mode, speed, next), status chips, tab sheet, menu sheet, first-run checklist. All built in code (`Ui.kt` helpers, vector icons in `res/drawable`).
- `SettingsActivity.kt`: multipage. Home lists Browsing, Controls & display, Privacy & cookies, Auto mode, Background, Text-to-speech, Tor, About; each page is the same activity with extra `page`. About has version, how-to, welcome tour, checklist, GitHub, copy version info, reset all settings.
- `WelcomeActivity.kt`: 4-page first-launch tour (what it is, how it works, permissions, quick choices). Sets `firstRunDone` on finish. The old checklist sheet is still in the menu and About.
- Floating pill is optional (`showPill`, default off). With it off, Pause/Resume, Auto mode, Auto speed and Next page are at the top of the menu. Other new prefs: `pillFade`, `showChips`, `keepScreenOn`, `restoreTabs`. Menu also has Copy link and Share link.
- Stale-file trap: `cp -r` never deletes. v3 moved `MainActivity` into `Modes.kt`, so an old `MainActivity.kt` caused a redeclaration error until removed by hand.
- TTS: `<queries>` for TTS, startup engine/voice-data check with install chip, speech state mirrored to the MediaSession, optional "Native TTS bridge" (off by default, experimental).
- UI (v8): `C` in `Ui.kt` is now mutable and `C.apply()` reads `theme` (dark/amoled/light) and `accent`/`accentCustom`; every activity calls `applyTheme()` first. `themeSig()` changes make the normal browser (not private tabs) and Settings pages `recreate()` on resume. Settings has an Appearance page: theme, accent, address bar top/bottom (`barTop`), compact bars (`compact`), menu items (`menuHidden` list, ids in `MENU_TILES`), status chips (`showChips`, `chipsAll`). The menu is a tile grid (playback tiles when the pill is off, quick actions, then Settings, Clear data, Exit). Chips are "smart" by default: only warnings, Tor status, Auto when on.
- v10: auto scroll/swipe/speed/auto-next and the Auto settings page are gone (the pill is now play/pause + next; manual "Next" uses the `autoNextSel` selector from Controls). `SleepTimer.kt`: timer per process (menu tile "Sleep", chip, notification text). On expiry it sends the `sleep` command (all tabs: `Js.STOP_ALL` cancels speech, `about:blank`, `finishAndRemoveTask`), stops the service and kills its own process. It holds a partial wake lock until it fires. `KeepAliveService` listens for `ACTION_AUDIO_BECOMING_NOISY` (headphones/Bluetooth disconnect) and applies pref `onDisconnect` (stop default, pause, none); `stop` cancels speech instead of pausing.
- v9: `Blocklist.kt` (tracker host list, plus `Bookmarks` store used by the normal browser). Tor tabs by default: tracker blocking (`torTrackers`), tracking-parameter stripping (`torStrip`, via `cleanUrl()` in `loadIn` and `shouldOverrideUrlLoading`), fonts blocked at Safer+, JS forces noreferrer on links/forms and an empty `document.referrer`, no service workers/push. Normal browser has optional `blockTrackers` and `stripTracking`. Menu: Find in page, Bookmark (star) plus Bookmarks list, Tor info dialog (exit IP, circuit path, traffic, blocked count) and a security-level shortcut tile. New tile ids in `MENU_TILES`: find, bookmark, torinfo, level.
- Tor hardening (v7): security level `torLevel` (0 standard, 1 safer = no WebGL/WASM/autoplay, 2 safest = JS and DOM storage off), `Js.torHarden(level)` (navigator/screen/timezone/locale normalised, canvas noise, WebGL masked, coarse timers, sensors removed), one generic UA for all Tor tabs (`torUa()`), loopback/LAN/private hosts blocked in `shouldInterceptRequest` plus `removeImplicitRules` (reflection) on the proxy, `HTTPTunnelPort auto IsolateDestAddr` (pref `torIsolate`, falls back to plain `auto`), menu row "New Tor identity" (wipe, close tabs, NEWNYM via reflection). Not possible in WebView: per-site cookie/cache isolation, Accept-Language/referrer control, fonts, .onion.
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
