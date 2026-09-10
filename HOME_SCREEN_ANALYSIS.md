# CS Launcher V3 — Home Screen Analysis (Current Design)

> Repo: `rohit451835/csl55` (PojavLauncher fork, "CS Launcher V3")
> Date: 2026-08-30

## 1. Entry points (code map)

| Layer | File | Role |
|---|---|---|
| Activity | `java/.../LauncherActivity.java` (1323 lines) | Host activity. `sensorLandscape` (Manifest line 94). Root fragment chosen at line 604: `fcEnabled ? FastClientHomeFragment : MainMenuFragment` |
| Activity layout | `res/layout/activity_pojav_launcher.xml` (79 lines) | Top bar + fragment container + overlays |
| **Home fragment** | `java/.../fragments/FastClientHomeFragment.java` (299 lines) | Home screen logic |
| **Home layout** | `res/layout/fragment_home_fastclient.xml` (887 lines) | Home screen UI (two-pane) |
| Sponsor card | `res/layout/infrawire_sponsor_card_home.xml` | Included twice (play pane + feed), **both force-hidden in Java** |
| Play button | `java/.../ui/PremiumPlayButtonView.java` (392 lines) | Custom canvas-drawn capsule: platinum gradient + sheen, launch wave/glow/particles |
| Top-left account | `java/com/kdt/mcgui/mcAccountSpinner.java` | Custom spinner; tap opens account dialog (`bg_account_popup`), rows: add account / Microsoft / Ely.by / Local |

## 2. Screen structure (landscape, forced `sensorLandscape`)

```
┌────────────────────────────────────────────────────────────┐
│ TOP BAR (52sdp, bg = colorBgStatusBar)                     │
│  [mcAccountSpinner: head + name + ▾]        [⚙ Settings]   │
├────────────────────────────────────────────────────────────┤
│  ProgressLayout (download console, slides under top bar)   │
├───────────────────────────────┬────────────────────────────┤
│ LEFT PANE (weight 1.8)         │ RIGHT PANE (weight 1)      │
│ bg_fastclient_card #2C2C2C,    │ bg_fastclient_card         │
│ radius 20dp, padding 16sdp     │ padding 12sdp, scrollable  │
│                                │                            │
│ [head] PlayerName  [Online]    │ Header: ⚙ icon (WRONG —    │
│        📁 Profile: Default      │   settings gear used as    │
│        [⚙ Profile Setting]      │   "servers" icon)          │
│                                │ "Partner Servers"           │
│ Chips (h-scroll):              │ "Click to quick join"      │
│ [1.21.4][Fabric][Java21+][3.5GB]│ [View All Servers]        │
│                                │                            │
│ Profile selector row:          │ 5 hardcoded server cards:  │
│ 📁 Default    [2048 MB RAM]  ›  │ [#1 icon] BananaSMP        │
│                                │   bananasmp.net  ● 906/2500│
│ Notification card (gone by     │ [#2] AscendiaMC  ● 14/300  │
│ default; Firebase banner)      │ [#3] HappyMC     ● 81/999  │
│                                │ [#4] InSane SMP  ● 237/690 │
│ (spacer)                       │ [#5] EternalNetw.● 2/2026  │
│ ───────── divider              │   rank badges:             │
│                                │   gold/purple/blue/pink    │
│ [ ▶ PLAY ]  PremiumPlayButton  │                            │
│  (white/platinum capsule,      │ OFFICIAL PARTNERS (label)  │
│   dark text #0E0E11)           │ [Infrawire card — HIDDEN]  │
│ [Infrawire card — HIDDEN]      │                            │
└───────────────────────────────┴────────────────────────────┘
```

Overlays in activity: `launch_boot_overlay` ("Opening Game…" shortcut hand-off),
`welcome_back_overlay` (cosmetic "WELCOME BACK" greeting, never blocks touch).

## 3. Visual design tokens (current)

- **Theme:** "True Neutral Dark Grey & Graphite"
- Background: `bg_launcher_dark = #0B0B0E` (near-black)
- Cards: `#2C2C2C` radius 20dp; server cards `#14161E` + stroke `#26272F`, radius 14dp
- Rows: `bg_folder_row #1E1E1E` stroke `#2A2A2A`, radius 8dp
- Chips: `bg_chip_dark #252525` stroke `#333`, radius 6dp; RAM value chip: translucent white `#33D0D0D0`
- Accent / status: online green `#7FA98C`; rank gold `#CC8800`, purple, blue, pink
- Play button: platinum gradient `#FAFAFC → #FFFFFF → #BFC1CB`, dark label `#0E0E11`; violet glow on launch
- Font: `@font/minecraftia` (pixel font) on player name / profile folder name; rest is system sans
- Sizing: responsive `sdp`/`ssp` units throughout (`_10sdp`, `_52sdp` …)

## 4. Functional wiring (what every element does)

| Element | Action |
|---|---|
| Account spinner | Opens account dialog: switch/add (Microsoft, Ely.by, Local/offline), delete account |
| ⚙ Settings (top-right) | Opens launcher settings |
| Play button | `PremiumPlayButtonView.beginLaunch()` → haptic + `ExtraCore.LAUNCH_GAME = true`; morphs to progress wave, listens to `LaunchTracker` phases; stop mode cancels pending launch |
| Profile selector row (`btn_select_profile`) | → `InstancePickerFragment` |
| "⚙ Profile Setting" chip | → `ProfileEditorFragment` |
| Server cards ×5 | **Toast only** — "Joining bananasmp.net…" (quick-join NOT implemented) |
| Notification card | Firebase `FirebaseSyncManager.getLatestHomeBanner()`; tap → markdown dialog / CsPopup; ✕ dismisses (`dismissBanner`) |
| Infrawire sponsor cards ×2 | Fully wired (partner page / Deploy VPS / Learn More) but **force-set `GONE`** in `onViewCreated` |
| "View All Servers" | No click listener (dead) |
| Entrance animation | `UiMotion.realScreen()` + `heroIn(playBtn)`; name shine `UiMotion.shineText` |

Data bindings: player name/head (`SkinHead3DRenderer`), online/offline from accessToken;
profile name/version/loader (Vanilla/OptiFine/Fabric/Forge/Quilt/NeoForge detected from version id),
Java version (from javaDir path), RAM (`LauncherPreferences.PREF_RAM_ALLOCATION`).

## 5. Issues / smells found (redesign should fix)

1. **Hardcoded fake server list** — 5 servers with made-up player counts (906/2500 etc.), no real ping; quick-join is just a Toast.
2. **Dead "View All Servers"** text — no listener.
3. **Sponsor cards duplicated** in layout (left pane + feed) and both hidden in Java → dead layout weight.
4. **Wrong icons**: player head `ImageView` defaults to `ic_play_arrow`; right pane "servers" header uses `ic_menu_settings` gear; notification uses settings-bell icon.
5. **RAM shown twice** (chip `3.5GB` + row badge `2048 MB RAM`) with inconsistent values/units.
6. **Profile icon logic confused** — code reuses the player-head `ImageView` for modpack icon (`ivProfileIcon = iv_player_head`), comment admits uncertainty.
7. **Landscape-locked** (`sensorLandscape`) — no portrait layout.
8. Content density: left pane has a big empty spacer; right pane scrolls; balance is rough on small screens.
9. Pixel font (minecraftia) used inconsistently (only 2 labels).
10. Accessibility: emoji glyphs in text (▶, ⚙) instead of vector icons; several `contentDescription` mismatches.
