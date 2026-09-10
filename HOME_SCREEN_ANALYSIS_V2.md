# CS Launcher V3 — Home Screen Analysis V2 (CURRENT design)

> Repo: `rohit451835/csl55` · Date: 2026-08-31
> ⚠️ The old `HOME_SCREEN_ANALYSIS.md` describes the **previous** two-pane `FastClientHomeFragment` home.
> Commits `bec129f` (30 Aug) + `2181606` (31 Aug) replaced it. **This doc analyses what is live on `main` right now.**

---

## 1. Current home = `HomeDashboardFragment` (single root)

| Layer | File | Role |
|---|---|---|
| Host | `LauncherActivity.java` L602 | Root fragment is now **always** `HomeDashboardFragment` (`fcEnabled` check removed) |
| Fragment | `fragments/HomeDashboardFragment.java` (400 lines) | All dashboard logic |
| Layout | `res/layout/fragment_home_dashboard.xml` (477 lines) | Rail + 2 columns, landscape |
| Instance cards | `fragments/HomeProfileAdapter.java` (451) + `item_home_profile_card.xml` | Grid cards: play/edit/mods/shortcut/favorite/drag |
| 3D player | `ui/SkinBody3DView.java` (317) | Custom isometric cuboid renderer, 64×64 skin UVs, idle bob + arm wave |
| Chrome | `activity_pojav_launcher.xml` | Legacy top bar (account spinner + settings) **hidden while home is visible**, restored on leave (`setLegacyChromeVisible`) |

## 2. Layout map (landscape, forced `sensorLandscape`)

```
┌────────────────────────────────────────────────────────────────────────────┐
│ bg #0A0710                                                                 │
│ ┌──────┐ ┌──────────────────────────────────┐ ┌──────────────────────────┐ │
│ │ RAIL │ │ CENTER (weight 1.9)              │ │ RIGHT (weight 0.92)      │ │
│ │ 64dp │ │                                  │ │                          │ │
│ │ ◉brand┤ │ FEATURED CARD (weight 1.5)      │ │ ACCOUNT (weight 1.5)     │ │
│ │      │ │  bg = instance bg / webp hero    │ │  [3D full-body player]   │ │
│ │ 🏠Home│ │  + dark scrim                   │ │   idle bob + wave        │ │
│ │ 🖱Cursor│ │  [FABRIC] badge (purple)       │ │  👤 Manage Profiles (btn) │ │
│ │ 🎮Ctrl│ │  Minecraft 1.20.1  (30sp bold)  │ │  📁 Game Directory (ghost)│ │
│ │ ℹAbout│ │  Instance • Fabric  (14sp)      │ ├──────────────────────────┤ │
│ │ ⋮     │ │  static marketing desc (13sp)   │ │ LAST PLAYED (weight 1)   │ │
│ │ ⚙Set │ │  [▶ Launch 1.20.1] (purple)     │ │  [icon 68dp] Name        │ │
│ │      │ ├──────────────────────────────────┤ │              version     │ │
│ │      │ │ INSTANCES (weight 1)             │ │              dd MMM yyyy │ │
│ │      │ │  ⊞ Instances   [＋ Create]       │ │              hh:mm a ⏱   │ │
│ │      │ │  ┌─────────┐ ┌─────────┐        │ │                          │ │
│ │      │ │  │card: ⭐ │ │card: ⭐ │  2-col │ │                          │ │
│ │      │ │  │icon name│ │icon name│  grid, │ │                          │ │
│ │      │ │  │ver•mods•RAM│ │…│  drag    │ │                          │ │
│ │      │ │  │[⧉][≡][Browse][▶]│ │…│       │ │                          │ │
│ │      │ │  └─────────┘ └─────────┘        │ │                          │ │
│ └──────┘ └──────────────────────────────────┘ └──────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────┘
```

## 3. Design tokens (current theme: "Dark premium + violet")

| Token | Value | Used by |
|---|---|---|
| Page bg | `#0A0710` (near-black violet) | root |
| Card | gradient `#1A1722→#131119` 135°, stroke `#262233`, radius **22dp** | rail, featured, instances, account, last-played |
| Instance card | gradient `#1B1C22→#101014` 270°, stroke `#2D2D35`, radius 20dp + top light hairline | grid cards |
| Primary button | gradient `#8B5CF6→#6D28D9` 135°, radius 16dp | Launch, Create, Manage, rail-selected |
| Badge | solid `#8B5CF6`, radius 8dp | loader tag (FABRIC/FORGE/VANILLA…) |
| Ghost button | `#1E1B29`, stroke `#34303F`, radius 14dp | Game Directory |
| Brand ring | oval `#16121F`, stroke `#3A2E55` | rail logo well |
| Muted text | `#C9C6D6` / `#B9B5C9` / `#8B5CF6` (accent) | secondary lines |
| Featured scrim | `#E60A0710→transparent` + text shadows | hero legibility |
| Font | default sans; `minecraftia` pixel font only on instance-card name | inconsistent |
| Units | plain `dp/sp` here; rest of app uses `sdp/ssp` | inconsistent |

## 4. Functional wiring

| Element | Action | Status |
|---|---|---|
| Rail → Home | `loadData()` refresh only | ✅ |
| Rail → Cursor | `CursorCustomizationFragment` | ✅ |
| Rail → Controls | `CustomControlsActivity` | ✅ |
| Rail → About | `AboutFragment` | ✅ |
| Rail → Settings | `LauncherPreferenceFragment` | ✅ |
| Featured Launch | set current profile pref → `ExtraCore.LAUNCH_GAME=true` | ✅ plain button (no progress morph) |
| Featured card | = **first profile in saved order** (drag reorder changes it) | ✅ data-driven |
| Create Instance | `CreationTypeDialog` (type chooser → version flow) | ✅ |
| Manage Profiles | → `InstancePickerFragment` (instances list, not accounts) | ⚠️ naming |
| Game Directory | opens current instance game dir via `Tools.openPath` | ✅ |
| Instance card ▶ | launch that profile (`PremiumPlayButtonView` mini) | ✅ |
| Instance card ✎ | `ProfileEditorFragment` | ✅ |
| Instance card ⧉ | `ModsSearchFragment` (browse/download mods) | ✅ |
| Instance card ⊕ | home-screen shortcut wizard | ✅ |
| Card ⭐ / drag | favorite + reorder, **persisted** (`applyProfileOrder`) | ✅ |
| 3D player | real skin of current account, `loadCurrentSkin()` on resume | ✅ |
| Last Played | max(`lastUsed`) profile + date/time | ✅ |

## 5. Motion

`UiMotion.revealScreen()` entrance → `heroIn(featured)` + `slideIn(recycler, 120ms)` + `slideIn(player, 90ms)`; `pressFeedback` on all CTAs; cards: staggered fade/slide-up on bind; 3D body: idle bob + occasional wave; drag: lift (scale 1.04 + elevation).

---

## 6. What's GOOD (keep in redesign)

1. **Real information architecture finally** — rail nav + featured + instances + account + last-played beats the old fake-server two-pane.
2. **Fully data-driven** — profiles, order, favorites, lastUsed, icons/backgrounds, mod counts (async `mods/` scan) all real.
3. **Drag-reorder with persistence** → featured updates instantly. Nice touch.
4. **3D skin renderer** is custom, offline, and cheap (canvas paths, no GL) — a genuine signature element.
5. Consistent card/button token family; scrim + text shadows keep hero readable.

## 7. Gaps / issues (redesign targets)

**UX / content**
1. **No account identity.** Account card shows the body but **no player name, no auth type (MS/ely/offline), no switch-account tap**. Worse: the legacy top-bar spinner is hidden on home → **you cannot switch/add an account from Home at all**.
2. **`hd_featured_desc` is dead hardcoded marketing copy** ("…bedrock & java experience…" — also factually wrong for a Java-only fork). Never bound in Java; sits inside an otherwise data-driven card.
3. **Featured vs Last Played redundancy** — featured = first-in-order, which usually *is* the last played; two cards often show the same instance.
4. **Instances grid has no empty state** (featured has one; grid just renders blank).
5. **No RAM / Java runtime / loader-version visibility** anywhere on Home (old design at least showed chips; launcher's #1 practical setting invisible).
6. **No search** for instances once you have many; 2-column `GridLayoutManager` fixed regardless of width.
7. Rail is icon-only (no labels/tooltips); selection state only ever set for Home.
8. **Naming confusion**: "Manage Profiles" opens *instances*, while launcher/MC convention says profiles ≈ accounts.

**Interaction / feedback**
9. Featured Launch is a plain `TextView` — the old `PremiumPlayButtonView` (haptic + wave/glow/particle launch morph, `LaunchTracker` phases) is **not** on the featured CTA; dashboard launch has no in-place progress feedback.
10. Emoji glyphs as icons (▶, ＋, 👤, 📁, ⏱) next to proper vector icons — inconsistent + a11y noise.

**Layout / tech**
11. **Landscape-locked**, hard-coded horizontal `LinearLayout` + fixed weights; breaks in portrait, awkward on 4:3 tablets/small phones.
12. Legacy chrome hack: top bar hidden/shown by fragment lifecycle — settings reachable both from rail and top bar elsewhere → inconsistent navigation model.
13. `android:tint` (deprecated) used; pixel font on only one label; `dp/sp` vs `sdp/ssp` mixed with the rest of the app.
14. Old `FastClientHomeFragment` + `fragment_home_fastclient.xml` (887 lines) still shipped but unreachable — dead weight (and its hidden sponsor cards).
15. Right column bottom-heavy: Account card is 60% empty space when skin is slim; Last Played has 4 stacked text rows where 2 would do.
16. **⚠ Rail overflow (measured):** rail fixed content = 14+46+26 + 4×(48+14) + 48 + 14 = **396dp**, but on a common 360dp-height landscape screen the rail only gets 360−24 = **336dp** → ~60dp short; flex items squeeze/shrink on most devices. Even 400dp screens are 20dp short.
17. **⚠ 3D player gets ~66dp only (measured):** Account card ≈ 219dp tall; minus header (~20) + 2 buttons (~44+40+18 margins) leaves **≈ 66–70dp** for `SkinBody3DView` — the dashboard's signature element renders tiny. (Same math makes Last Played ≈ 147dp with 4 small text rows.)

## 8. Redesign discussion starters (jab aap design batayen)

- Account card ko **identity block** banayein? (avatar + name + auth badge + switch-account button)
- Featured ko **"Continue playing"** bana ke Last Played merge karein, ya featured = favorite/pinned instance?
- Launch CTA par **premium progress morph** (PremiumPlayButtonView jaisa) wapas layein?
- RAM/Java/loader **meta chips** featured card mein add karein?
- Rail labels/tooltip + proper selected-state cycling?
- Portrait/tablet layout (sw600dp qualifier ya ConstraintLayout flow)?
- Dead code (FastClientHomeFragment) delete?

---

*Files: `fragment_home_dashboard.xml`, `HomeDashboardFragment.java`, `HomeProfileAdapter.java`, `item_home_profile_card.xml`, `SkinBody3DView.java`, drawables `bg_hd_*.xml`*

---

## 9. FIX STATUS (V2.1 — applied 2026-08-31)

| # | Problem | Fix applied |
|---|---|---|
| 1 | No account identity | Account card now shows live **player name + MICROSOFT/ELY.BY/LOCAL badge** + **Switch Account** button (opens the existing account dialog — add/switch/delete works from Home again) |
| 2 | Dead marketing copy | `hd_featured_desc` is now a live meta line: **mods • RAM • Java version • last played date** (async mod count, same logic as grid cards) |
| 3 | Featured ≈ Last Played | "Recently Played" now picks the most recent instance **other than the featured one**; card **hides itself** when redundant |
| 4 | Plain launch button | Featured CTA is now **PremiumPlayButtonView** — launch morph, STOP-cancel, LaunchTracker phases |
| 5 | Emoji as icons | All emoji glyphs replaced with vector drawables (`ic_add`, `ic_cs_refresh`, `ic_layers`, `ic_folder`, `ic_play_arrow`) |
| 6 | Icon-only rail | Every rail item got a **tooltip** (`TooltipCompat`) on top of contentDescription |
| 7 | Fixed 2-col grid, no empty state | **Responsive 2–4 columns** by width; grid + featured both have proper empty states; RAM/Java/mods visible on featured |
| 8 | Chrome hack (functional gap) | Functional half fixed — account switch/add now lives on the dashboard itself; legacy bar stays hidden |
| 9 | Rail overflow | Rail sizes moved to **dimens**: compact default (~324dp content) fits 360dp-height screens; `values-h420dp` restores roomy 48dp items |
| 10 | 3D player cramped | Redundant Recently-Played card hides → account card (and 3D body) takes the full column; button row compacted |
| 14 | Dead code | Deleted `FastClientHomeFragment.java`, `fragment_home_fastclient.xml`, `infrawire_sponsor_card_home.xml` |
| 13 | Deprecated `android:tint` | Dashboard layout switched to `app:tint` |

Bonus: Recently Played card is now **tap-to-launch**; "Manage Profiles" renamed to **Select Instance** (it opens the instance picker).

---

## 10. V3 — LAUNCHER HOME "STAGE" (complete redesign, 2026-08-31)

The dashboard was thrown away entirely. New home = `LauncherHomeFragment`:

- **Immersive stage** — the primary instance's artwork fills the whole screen
  (two-layer crossfade on switch) under a legibility scrim. No top bar, no
  branding header, no dashboard boxes.
- **Floating glass dock** (left) — Home / Cursor / Controls / About / Settings.
- **Account chip** (top-right) — avatar + name + MICROSOFT/ELY.BY/LOCAL badge →
  existing account dialog (add/switch/delete).
- **3D player centerpiece** — real skin, breathing bob, one-shot **greeting
  wave** (`SkinBody3DView.greet()`, new API) + rise-from-ground entrance.
- **Launch cluster** — eyebrow / instance name / version • loader • Java • mods
  + the signature platinum PLAY capsule (morph, STOP-cancel, LaunchTracker).
- **Continue pill** — most recent NON-primary instance, relative time, one tap
  to relaunch; hides itself when redundant.
- **Library carousel** (bottom) — artwork cards + "New Instance" tile.
  Tap = promote to primary (stage re-themes), ▶ = launch, ⋮ = bottom action
  sheet (primary/edit/mods/shortcut/directory/favorite), long-press = drag
  reorder (persists; position 0 = primary).
- Primary instance = first in saved order; ordering/favorites reuse
  LauncherProfiles/ProfileOrderManager exactly. Create flow reuses
  CreationTypeDialog. Mod download reuses ModsSearchFragment. Directory opens
  via Tools.openPath. All existing systems, zero duplicates.

Deleted: HomeDashboardFragment, HomeProfileAdapter, fragment_home_dashboard.xml,
item_home_profile_card.xml + 10 zero-ref drawables.

Portrait: the host activity is deliberately `sensorLandscape` (game runtime is
landscape-first), so the stage is designed for landscape + tablet widths
(compact/h420dp dimens variants).
