# PHASE 10 PLAN — CS Launcher Plus

Written after read-only exploration (no edits yet). Every item below lists: what the user saw → root cause found in code → exact fix. Chunks are pushed one at a time; CI must be green before the next chunk starts.

Palette rule (standing): graphite only, NO green. Keep every existing id / view type contract. Every `res/layout/X.xml` rewrite must check `res/layout-land/X.xml`.

---

## 0. Repo recovery (before anything)
`/home/user/csl55/.git` is missing again (snapshot drops it). Steps: `git clone` → `/tmp/gitclone`, move `.git` into `/home/user/csl55`, `git checkout --` every ` D` file, `git diff --stat` must show only real edits, revert mode-only diffs, `chmod +x /home/user/ci_build.sh`. Needs the PAT (token file `/tmp/.ght`, never printed).

---

## 1. Settings — "ekdum easy to use"  (Chunk C)
**Now:** left 172dp rail + right console: header bar (back, icon, title/subtitle), inline search, live badge, dashboard 2-col category cards, pages = spec-sheet rows with icon well + fav star + control. 90 `SettingItem`s across 12 legacy pages, grouped into 5 hubs (General / Game / Display / Controls / Advanced) via `addCategoryPageTo` fan-out. Too dense, too many chrome elements (badges, stars, kicker, rail foot, dashboard stats).

**New structure — "Simple Settings" (phone-first, one column, big rows):**
- Root page = **one vertical list of 5 big section cards** (full width, 64dp tall, icon + title + one-line "what's inside" + chevron). No grid, no badges, no stats, no pinned rails.
- Section page = **plain grouped list**: group header (11sp caps) → rows 56dp+ with 15sp title, 12sp summary, control on the right. **Fav star removed from rows** (`setting_fav_star` kept in layout but GONE by default), icon well removed (id kept, GONE).
- Only ONE way to navigate: back arrow + tap. The rail is dropped visually (`settings_category_rail_scroll` stays in layout, GONE) — on landscape the list just gets max-width 640dp centered.
- Search stays but as a single full-width pill under the title (not inline). Placeholder "Search settings".
- **Advanced/Experimental/Maintenance items collapsed behind "Show advanced options" toggle row** at the bottom of each section (so casual users see ≤8 rows per section).
- Sliders show the value big on the right (`setting_value_text` 15sp) — most-tapped control.
- Save bar unchanged (`unsaved_changes_bar`, `btn_save_settings`).
- Files: `fragment_custom_settings.xml` (rewrite, keep ids), `item_settings_category.xml`, `item_setting_toggle/slider/dropdown/button.xml` (rewrite, keep ids), `item_setting_category_card.xml` (now a full-width row), `LauncherPreferenceFragment`: `bindDashboardHolder` → 1 column; `setupCategoryRail` → hides rail; new `advanced` filter in `isItemVisibleInternal` using a session flag per section.

## 2. In-game "blood line" — half the screen  (Chunk A)
No red drawable exists in qs_/sd_/igm_ (only `igm_card_danger` for the QUIT card, 268dp drawer). Verified candidates:
- **`BootLogOverlay`** (Phase 9 made it 440dp / 48% of screen wide, hairline `MATCH_PARENT`, lines coloured `0xFFFF6B74` for ERROR/EXCEPTION lines). It only dismisses when `g_csPresentsTotal` grows — on the **gl4es/Zink path `pojavSwapBuffers` is never called** (only `osm_bridge.c` increments the counter; `gl_bridge.c` swaps via `eglSwapBuffers_p` directly). So on most devices the overlay never sees a "first frame", stays up to the **4-minute HARD_CAP**, and a red ERROR line + hairline shows as a "blood line across half the screen" over the running game. ← root cause.
- Fix: (a) native: increment `g_csPresentsTotal` in `gl_bridge.c:gl_swap_buffers()` too (one line, matches osm); (b) Java: dismiss on `MainActivity.mFirstGameFramePresented` **and** on first `ACTION_DOWN` on the game surface, HARD_CAP 4 min → 45 s, hairline width = header width (wrap) not MATCH_PARENT, error colour softened to `#E8A0A6`, overlay capped at 36% width; (c) the ControlLayout drag guides get a safety `clearDragGuides()` in `setModifiable(false)` and `onDetachedFromWindow`.

## 3. Control editor: select → edit popup disappears  (Chunk A)
Flow: long-press → `editControlButton` → `mControlDialog.appear(side)`. `SideDialogView.appear` posts to the UI thread and retries ≤3 times while width==0 — but `EditControlSideDialog.setupSheetChrome()` re-parents the shell to `MATCH_PARENT` height + changes width **after** inflate, forcing a second layout pass; on the frame where `appear()` runs, `mScrollView.getWidth()` is 0 → after 3 retries it gives up **silently** (never sets `mDisplaying`). Then the ACTION_UP of the same long-press reaches `ControlLayout.onTouchEvent` → `disappearLayer()` → `disappear(false)` on a sheet parked at −280sdp. Second regression: `dialog_side_dialog.xml` root is not clickable, so any tap inside the sheet bubbles to `ControlLayout.onTouchEvent` (ACTION_UP → `disappearLayer()`), i.e. **touching the popup closes it**.
- Fix: `SideDialogView.inflateLayout`: `mDialogLayout.setClickable(true); setFocusable(true)` (consumes touches); `appear()` — retry bounded by time (up to 10 frames / 250 ms) instead of 3, and if still 0 use the LayoutParams width as the slide distance; `isAtRight()` uses `mDisplaying` + last requested side, not X; `ControlLayout.onTouchEvent` ignores an ACTION_UP that arrives < 300 ms after `editControlButton` (`mEditOpenedAt`). ColorSelector keeps opposite side.

## 4. Modpack download pages 2 + 3 — brand-new design  (Chunk B)
Page 2 = `ModVersionPickerFragment` (`fragment_version_picker.xml` + `item_version_row.xml`), page 3 = `ModInstallFragment` (`fragment_mod_install.xml`, ~50 `install_*` ids bound by type).
- **Page 2 "Release Ledger"**: left 40% = sticky project panel (icon, title, author, target profile chip "for Fabric 1.21.4"), right 60% = version list as timeline rows: left rail with dot (latest = filled), version name 15sp, MC + loader as mono chips, compat badge as a right-edge state strip (ok = silver, bad = muted). Pagination = bottom segmented pill (prev · "Page 2 / 7" · next). Loading = 6 skeleton rows shimmer. Keep ids: `version_picker_back/title/loading/error`, `version_list` (RecyclerView), `pagination_footer/text/prev/next`, row `version_name/mc_badge/compat_badge/loader_badge/recommended_badge` (TextViews).
- **Page 3 "Install Dossier"**: full-bleed backdrop A/B (keep ImageViews) with a bottom-up graphite gradient; overlay top bar; below: a 2-column layout — left column = summary card (icon 72dp, title 22sp, author, stats as 3 mono tiles), install CTA pinned inside the card; right column = tab strip (Overview · Details · Gallery · Deps · Changelog) as an underline tab bar + the sections. Bottom bar keeps `install_button`. All `install_*` ids/types preserved; containers stay ViewGroups; chips created from Java untouched.
- Animations: page 2 rows stagger FADE_LEFT; page 3 hero parallax on scroll (`NestedScrollView` listener, backdrop translationY×0.4), tab underline slides.

## 5. Premium (Microsoft) account head wrong in the user list  (Chunk A)
`mcAccountSpinner.bindAccount` → `SkinHead3DRenderer.resolve(res, username)` → renders `skins/<user>_skin.png`. For a Microsoft account that file is written by `SkinResolver.resolve()` at login (Phase 9). But `MinecraftAccount.updateSkinFace()` for **non-Microsoft** and the `LocalLoginFragment` path write a **default skin** into the very same `<user>_skin.png` when the user had a local account with the same name earlier — and `SkinResolver.resolve()` returns early if `isUsableSkin(out)` (any 64×32 file counts). Result: an old local/default sheet shadows the premium one forever.
- Fix: `SkinResolver.resolve(account)`: for `isMicrosoft` accounts, if the cached file is not marked premium (`<user>_skin.src` sidecar == "mojang:<uuid>") → re-fetch via UUID and overwrite; write the sidecar on success. `SkinHead3DRenderer.invalidate` after. `mcAccountSpinner` loads heads async (executor + post) so the list never shows the fallback while the file is being fetched; also trigger `SkinResolver.resolve` for every listed Microsoft account missing the sidecar.

## 6. Home top-left brand  (Chunk A)
`fragment_launcher_home.xml` L97-118: "CS LAUNCHER" 12sp + "PLUS" 7.5sp on two lines. → `lh_brand_name` = **"CS Launcher Plus"** single line (`maxLines=1`, 12.5sp, letterSpacing .02), `lh_brand_sub` = row: 🇮🇳 flag (new vector `ic_flag_india` 14×10dp, saffron/white/green tricolour + navy chakra — this is the flag, exempt from the no-green rule) + "Made in India" 8.5sp. No java change (ids kept).

## 7. About page: co-founder images broken by rounding  (Chunk A)
Checked with PIL: `cs_team_rohit.png` 256² has a **transparent background** (a render cut-out) — rounding looks fine. `cs_team_notdanger/mineradi/ender.png` are 256² full-bleed portraits; on the `ab_avatar_ring` 44dp circle they look "cut". Fix: new `AbAvatar` styling → **rounded-square 14dp radius**, `scaleType=centerCrop`, `clipToOutline` via `bg_ab_avatar_mask`; the transparent one gets a graphite well behind it so all four read the same. Pre-process nothing (files are already square).

## 8. Welcome popup too small  (Chunk A)
`PlusWelcomeDialog.onCreateDialog` never calls `window.setLayout` → the 400dp card gets squeezed by the default dialog width. Fix: `onStart()` → `setLayout(min(640dp, screenW − 32dp), WRAP)`, dim .6, `dialog_plus_welcome.xml` card MATCH_PARENT, body 14.5sp / line spacing 1.25, chips 11sp, buttons 44dp; entrance scale .92→1 OUT_BACK.

## 9. Profile "select version" popup → XML + Minecraft mini font  (Chunk B)
`ProfileEditorFragment` L426 uses `VersionSelectorDialog.open` = stock `AlertDialog` + `ExpandableListView` (`dialog_expendable_list_view.xml`, `android.R.layout.simple_expandable_list_item_1`). Same dialog is used from `ModsSearchFragment` L491/510 and `SearchModFragment` L295/309.
- New `dialog_version_selector.xml` (card 560dp × 78% h): header "SELECT VERSION" in **Minecraftia** (`res/font/minecraftia.ttf` — already bundled, it is the classic Minecraft pixel font people call the "Minecraft mini font"; research: Minecraftia by Andrew Tyler / the in-game font; Monocraft is the monospace variant), group tabs Installed · Release · Snapshot · Beta · Alpha as pixel-font pills, a search box, list rows `item_version_choice_row.xml` (version id in Minecraftia 14sp, type chip, "installed" dot). `VersionSelectorDialog.open` keeps the same signature + `VersionSelectorListener`, so all 5 callers work unchanged. `VersionListAdapter` keeps the data model; new `getGroupView/getChildView` inflate the XML rows.

## 10. Default download version = 1.21.11 Vanilla  (Chunk A — highest priority)
Mojang manifest latest release is now **26.2**, so every "latest" fallback drifted. Pin:
- `MinecraftProfile.LATEST_RELEASE` → `"1.21.11"`; `getDefaultProfile()` `lastVersionId "1.7.10"` → `"1.21.11"`, name "Minecraft 1.21.11"; `createTemplate()` uses it.
- `MinecraftAccount.selectedVersion` L29/L276 `"1.7.10"` → `"1.21.11"`.
- `AsyncMinecraftDownloader` L12: `latest-release` → `"1.21.11"` explicitly (not manifest latest).
- `LauncherHomeFragment.buildDemoProfile` L1085 `"1.21.1"` → `"1.21.11"`.
- `VersionCreateFragment`: on open, `mMinecraftVersion = "1.21.11"` with Vanilla pre-selected (`applyLoaderSelection(LDR_VANILLA, keep=true)`), field shows "1.21.11 · default"; the create button is enabled immediately; any pick overrides.
- `ProfileEditorFragment` new profile template → 1.21.11.

## 11. OptiFine downloader  (Chunk B)
Verified live from the sandbox with the app's UA: `optifine.net/downloads` 200 → `adloadx?f=OptiFine_1.21.11_HD_U_J9.jar` 200 → `<span id="Download"><a href="downloadx?f=…&x=<token>">` → GET 200 `application/java-archive` 8,045,116 B. Scraper emulation on the saved page: 53 `<h2>Minecraft X</h2>` groups, 222 `tr.downloadLine*` rows, `colFile`/`colMirror` parse correctly (incl. `preview_` rows). So `OptiFineDownloadTask`/`OFDownloadPageScraper`/`OptiFineScraper` work — **the feature is simply unreachable**: `VersionCreateFragment` has no OptiFine loader (LDR_JAR replaced it, "pick any .jar" = the "different downloader" the user sees), `OptiFineInstallFragment` has no caller.
- Fix: add **`LDR_OPTIFINE = 6`** chip ("OptiFine", `ic_optifine`) in `VersionCreateFragment`: MC list = `OptiFineUtils.downloadOptiFineVersions().minecraftVersions` (strip "Minecraft "), loader-version list = that MC's `versionName`s (previews labelled "· preview"), auto-select newest non-preview build; create → `OptiFineDownloadTask(version, listener, activity)`; `onDownloadFinished` → existing JAR branch (`OptiFineUtils.addAutoInstallArgs` + `PendingProfileRename.record(token=mc)`). Auto-name "OptiFine 1.21.11". JAR option stays. Cache: `downloadStringCached("of_downloads_page")` TTL 24 h → bypass cache when the requested MC version is missing from the cached list (refetch once).
- `OptiFineDownloadTask.determineMinecraftVersion` stays as a safety net; progress goes to the create button ("DOWNLOADING… 43%").

---

## Chunks
- **A** (fixes, ship first): 10 default 1.21.11 · 2 blood line · 3 edit popup · 5 premium head · 6 brand + flag · 7 about avatars · 8 welcome size.
- **B** (features): 11 OptiFine loader · 4 modpack pages 2+3 · 9 version selector XML + Minecraftia.
- **C** (redesign): 1 simple settings.

Each chunk: local parse-scan (`javac -proc:none` syntax scan + XML well-formedness + resource-ref check) → commit → push → `ci_build.sh` → wait for SUCCESS → report in Hinglish.
