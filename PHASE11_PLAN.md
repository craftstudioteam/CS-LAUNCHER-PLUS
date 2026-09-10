# PHASE 11 PLAN — CS Launcher Plus

Base: `be81c8f` (phase 10C, CI green). Ten items, shipped in three chunks; every chunk is
pushed and waits for CI before it is reported.

## Chunk A — fixes (items 1, 2, 3, 4, 7)

### 1. OptiFine must work in ONE click
Findings (recon):
* Download itself works (adloadx → tokenised downloadx, token lives ≈7.5 min).
* `OptiFineDownloadTask` scrapes the token FIRST, then downloads the whole vanilla
  version (minutes), then the jar → token can expire → 19-byte HTML "jar".
* `onDownloadFinished()` / `onDownloadError()` bail out with `if (!isAdded()) return;`
  → when the create page is no longer attached after a long download the installer
  hand-off is silently dropped (jar cached → next click is instant → "works the 2nd time").
* After the `:gui_installer` process exits the launcher only reloads profiles from
  `LauncherHomeFragment.onResume()`; nothing selects the new profile or tells the user,
  and `PendingProfileRename` matched the token `"1.21.11"` — which the vanilla profile
  also contains.
* The installer writes `javaArgs: -Xmx2G …` into the profile; that overrides the RAM slider.
Fix:
* Reorder: vanilla first, scrape+download jar last (fresh token); retry a bad body once
  even when the re-scrape returns the same URL.
* New `profiles/InstallerHandoff` helper (app context, no fragment needed): records the
  pending rename, launches `JavaGUILauncherActivity`, arms a return watcher.
  `VersionCreateFragment` calls it whether or not it is still attached; errors go to
  `CsNotifier` instead of a Toast that nobody sees.
* Return watcher: `LauncherActivity.onResume` + `launcher_profiles.json` mtime poll →
  `LauncherProfiles.loadAsync` → `PendingProfileRename.apply()` (precise token
  `<mc>-OptiFine`, strips installer `javaArgs`, selects the profile) →
  `ExtraConstants.REFRESH_VERSION_SPINNER` + `CsNotifier.success`.

### 2. Original OptiFine logo
Only official mark available (optifine.net favicon `of16r.png`): red panel, gold "OF".
`ic_optifine.xml` redrawn as pixel-exact vector from that bitmap (one path per colour).

### 3. Default version 1.21.11
* `assets/launcher_profiles.json` seed was still `(Default)` → 1.7.10 → first Play
  downloaded 1.7.10. Seed becomes "Minecraft 1.21.11" / `1.21.11`.
* `LauncherProfiles.findBestVersionForInstance` fallback 1.21.10 → `DEFAULT_VERSION`,
  prefers the default version when present instead of `files[0]`.

### 4. Remove the JAR loader option
`buildLoaderChips` keeps a GONE placeholder for `LDR_JAR` so `mChipViews` indices stay
aligned with `applyLoaderSelection`; `CustomJarInstallTask` and all `LDR_JAR` code paths
stay (unreachable from the UI).

### 5.–6. (chunk B) — see below.

### 7. Mods list keeps its scroll position
Root cause: every navigation destroys the list view; `onViewCreated` rebuilt the adapter
and ran a fresh page-1 query. Fix: `ModItemAdapter.saveState()/restoreState()`
(items + SearchResult + filters + last-page flag), LayoutManager state captured in
`onDestroyView` and restored after `setAdapter`; `DownloadListFragment` and
`SearchModFragment` skip the reload when a matching state exists (same query/version/
loader/sort), still call `applyInstallContext()/refreshInstallStates()`.

## Chunk B — control edit popup (5) + settings (6)

### 5. Control-customization edit popup → plain/default again
Upstream-style single column (`dialog_control_button_setting.xml`), `dialog_side_dialog.xml`
back to title + divider + scroll + two buttons. Every `btnedit_*`/`editCommand_*`/
`control_*`/`checkboxFps*` id stays (bound by `EditControlSideDialog`). `SideDialogView`
keeps the phase-10 functional fixes (clickable root, appear() width retry, docked side) and
drops `playEntrance`, glyph/kicker chrome; `setupSheetChrome()` removed. `ColorSelector`
untouched (only distinct design allowed).

### 6. Settings — radically simpler
Single flat page, ≤4 sections of everyday rows (plain toggles/sliders, big labels), one
"Advanced" page for the rest. Same ids, view types, pref keys and save manager.

## Chunk C — skins (8, 9) + runtime pages (10)

### 8. ely.by skin on home 3D + head
`skinsystem.ely.by/skins/<name>.png` 301s to `http://ely.by/...`; HttpURLConnection never
follows https→http and cleartext is blocked → Steve. Shared redirect-following helper
(rewrites http→https, ≤3 hops, rejects text/html) used by `SkinResolver` and
`MinecraftAccount.updateSkinFace`; prefer `textures/<name>` JSON; invalidate face cache +
`SkinHead3DRenderer` after fetch.

### 9. Hat/overlay layer
Back-face culling for overlay cuboids, alpha-test 0.5, legacy 64×32 all-black hat = empty,
identical in `SkinGLRenderer` and `SkinHead3DRenderer`.

### 10. Runtime download pages
Brand-new `RuntimeSetupActivity` layout/flow + picker dialog + rows, animated; keeps
`PREF_SHOWN`, `MultiRTConfigDialog` API, `RTRecyclerViewAdapter` contract, download queue
semantics; dead wizard files deleted.
