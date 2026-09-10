# PHASE 8 — plan (explored before building)

Baseline: `f166076` (CI green). Rules: no green anywhere, keep old ids/view types,
genuinely new structure where a redesign is asked, push per chunk, wait for CI.

## Findings from exploration (root causes)

| # | Request | Root cause / current state | Fix |
|---|---------|----------------------------|-----|
| 1 | Default icon per loader | `VersionCreateFragment.mDefaultIcon` is always `cs_logo` | render loader drawable → webp data-URI per loader (fabric/quilt/forge/neoforge/vanilla=crafting table/jar=cs_logo); icon well follows chip |
| 2 | Version first → loader auto | `pickMinecraftVersion()`/`pickLoaderVersion()` toast "Select a loader first"; `applyLoaderSelection` wipes the picked version | LDR_NONE: MC picker lists vanilla → auto-selects Vanilla; loader-version picker lists ALL loaders ("Fabric · 0.16.9", "Forge · 1.21.1-52.0.31"…) → picking auto-selects loader (+MC for forge/neoforge). Loader change keeps the MC version |
| 3 | Default profile bg = uploaded GIF | `DEFAULT_PROFILE_BG_URL` = remote i.ibb.co (null while downloading, offline = nothing) | bundle `res/raw/cs_default_profile_bg.gif`; new key `res:cs_default_profile_bg`; `ProfileIconCache` aliases old URL + `cs_client_artwork` → bundled |
| 4 | Profile-edit green | `pe4_*`, `pe5_*` drawables (#62D99B family) + `#1A62D99B` divider | recolor whole pe4/pe5 set to graphite (shared by 18 screens — global no-green rule) |
| 5 | Mod version/loader download popup | `ModVersionSheet` = flat bottom list, no loader/MC filtering | NEW centred "File Picker" card: left rail = MC-version + loader filter chips, right = files (LATEST/OK/MISMATCH), footer = selection + USE THIS FILE; anime timeline. API kept + `withFilters()` |
| 6 | 3D skin missing (PNE/local + ely.by) | `updateSkinFace` uses **http://** skinsystem.ely.by → cleartext blocked (targetSdk 34, no cleartext flag); home only tries Mojang UUID pipeline; local skin download races the home load | new `SkinResolver`: MS → Mojang; ely.by → https skinsystem; local → Mojang-by-name → ely https → mc-heads; home retries once; `updateSkinFace` https |
| 7 | Default log animation | `LoggerView.startCliIntro()` (typed cmd + block logo + 3-step bar) | new boot timeline: cursor blink, logo reveal, brand tracking, animated checklist ticks, shimmer bar, "ready" pulse |
| 8 | Log page + mini boot overlay | only full-screen `LoggerView` (opened from drawer) | NEW `BootLogOverlay` in `activity_basemain.xml`: left side, 8.5sp mono, transparent, severity colours, EXPAND → full log, auto-hides on first presented frame; `view_logger.xml` graphite redesign with severity chips |
| 9 | Control-edit UI | `ActionRow` plain Buttons, handle pops in with no motion | pill action buttons, stagger POP on select, handle scale-in, selected control pulses |
| 10 | False "No compatible" (resource packs) | `computeCompatibility` requires `hasLoader(profile, "minecraft")` → false | loader check only for mods/plugins; universal tags (minecraft/vanilla/iris/optifine/canvas…) ignored; quilt accepts fabric. Same in `ModVersionPickerFragment` |
| 11 | Animations / slides | — | Anime timelines on every new surface |
| 12 | Local account page | 1-column form | NEW two-pane: live identity preview + rules checklist (ticks live) / input card + counter + CREATE. ids kept |
| 13 | Delete-account confirm | `AlertDialog` in `mcAccountSpinner` | `CsConfirmDialog` (XML `dialog_cs_confirm.xml`, danger button, anime in/out) |
| 14 | Server hub | PlantMC in `FeaturedServers`; add dialog = left-rounded `bg_au_sheet` inside a stock AlertDialog (broken look) | remove PlantMC; proper transparent Dialog + full-rounded card + entrance anim; hub_add pill restyled |
| 15 | Home top-left brand + YT/Discord | nothing there; links: Discord `https://discord.gg/bpgYQMA59D` (About), YouTube `https://youtube.com/@craft-studio-official` (promo dialog) | `lh_brand_cluster` top-left (name + 2 pills, YouTube red / Discord blurple) wired via `CsLinks`; launch row nudged down 6dp |

## Chunks (commit + push + CI each)
- **A** creation/profile: 1, 2, 3, 4
- **B** store: 5, 10
- **C** home/accounts/servers: 6, 13, 14, 15, 12
- **D** game: 8 (overlay + log redesign), 7, 9
