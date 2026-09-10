# Phase 7 — Normal-version defaults, crafting-table icon, page transitions, search loading, de-green, account popup, motion everywhere

## What the user asked (in their words, distilled)
1. **Normal Version defaults**: icon = CS Launcher logo by default, name auto-generated from the
   loader + version (e.g. picking Fabric → "Fabric 1.21.1"), user can still change the name.
2. **Crafting-table icon**: the white "Normal Version" icon → a real crafting-table image
   downloaded from the web.
3. **Page-to-page transition**: the best possible animation between screens.
4. **Mod search loading**: a proper loading animation while results load.
5. **No green**: everything dark + grey. Remove the green accents I introduced.
6. **Account-select popup**: redesign with animation.
7. **Animations everywhere**, mobile friendly (anime.js vocabulary from `Anime.java`).

## Questions I asked myself
- *Where does the auto-name come from?* `VersionCreateFragment` already knows loader + MC version
  + loader version. Rule: `<Loader> <MC>` (Vanilla → `Minecraft <MC>`, JAR → `<jar base name>`).
  Only overwrite the field while the user has NOT typed their own text (track `mNameAuto`).
  Hint text also becomes the suggested name so an empty field still reads well.
- *Default icon persisted or display-only?* Persisted: encode `cs_logo` as the data-URI once
  (`mEncodedIcon == null` → default), so the created profile really carries the CS logo. Fabric /
  Quilt tasks and PendingProfileRename already accept a data-URI icon → zero new plumbing.
- *Crafting table asset*: minecraft.wiki isometric render (real alpha PNG). Exported to
  `drawable-{m,h,xh,xxh,xxxh}dpi/img_crafting_table.png` (48→192 px) — a NEW name so the existing
  vector `ic_crafting_table.xml` (white silhouette) does not collide.
- *Page transition*: `MotionTransitions` is the single choke point (Tools.swapFragment + panes).
  Upgrade the default `slide` set → "cinematic": longer travel (32%p), 1.0→0.92 depth scale on the
  outgoing page, staged alpha and the emphasized-decelerate curve; pop variants mirrored. Also give
  `zoom` (deep-dive) a stronger 0.80→1 spring. Users who picked jelly/bounce/fade/off keep theirs.
- *Search loading*: `DownloadListFragment` has a skeleton grid + 2dp progress line. Replace the
  plain alpha pulse with a **shimmer sweep** (gradient band translating across each card) + staggered
  card breathing + a small "Searching…" capsule with three bouncing dots (anime staggered keyframes).
  The RecyclerView footer must stay gone (crash history).
- *De-green*: in my files only (repo-wide greens belong to other screens the user did not ask about
  and touching them risks regressions): `9FE8C4` → silver `#D2D6DE`; installed button gradient
  `rd_btn_installed` → light silver with dark label; account check `22C9CB` → `#E6E9EF`;
  `bg_account_row_active` stroke → `#C9CED8`.
- *Account popup*: `mcAccountSpinner.performClick()` builds a Dialog in code. Rebuild the panel as a
  proper graphite sheet: header (title + count + close), rows from a NEW `item_account_pick.xml`
  (skin head in ring, name, badge, subtitle, ✓, delete) — keep ALL old ids (`account_row`,
  `account_head`, `account_name`, `account_badge`, `account_subtitle`, `account_check`,
  `delete_account_button`) because `bindAccount()` reads them. Motion: sheet outBack scale-in,
  rows stagger(55) FADE_UP, tapped row pulses, exit inBack. Window sized in code (min(430dp, w-32)).
- *Motion everywhere*: `Anime` timeline in `VersionCreateFragment` (chips stagger from center,
  fields FLIP_UP, dock spring), loader-chip selection pulse, auto-name `swapText`, icon POP on default,
  version rows / dependency rows already staggered.

## Contracts kept
- Ids/view types of `item_minecraft_account.xml` (used by `getView` compact rows) untouched; new
  layout only for the popup.
- `VersionCreateFragment` ids unchanged; `mNameField` remains an `EditText`.
- MotionTransitions API unchanged (`current()`, `apply()`, `applyDeepDive()`).

## Shipped (phase 7)
| Area | Change |
|---|---|
| Normal version defaults | `VersionCreateFragment`: CS logo in the icon well by default and persisted (encoded `cs_logo` data-URI via `effectiveIcon()`), auto-name `"<Loader> <MC>"` (Minecraft / Fabric / Quilt / Forge / NeoForge / jar base name) that follows the selection until the user types their own; emptying the field re-enables the suggestion; empty-at-create falls back to the suggestion. Anime timeline entrance, chip pulse, value pops, name pulse. |
| Crafting table | `img_crafting_table.png` (minecraft.wiki isometric render, real alpha) at m/h/xh/xxh/xxxhdpi; used by the Profile Architect "Normal Version" medallion (30dp) and the MC-version field. |
| Page transitions | `motion_slide_*` → cinematic shared-axis (32%p travel, 0.92/0.90 depth, outExpo `motion_out_expo`, inQuart exits); `motion_zoom_*` → 0.80 spring deep-dive with lift. New interpolators `motion_out_expo`, `motion_in_quart`. |
| Search loading | `ShimmerOverlay` (light band sweep, one shader) inside every skeleton card; `LoadingDots` (staggered bounce) in a floating "SEARCHING / LOADING MORE" glass capsule; skeleton cards pour in on a diagonal stagger; result cards use the same diagonal `stagger` with outExpo. Search page header/search/tabs/sort/grid timeline. |
| De-green | Detail page + version sheet state colours → silver; `rd_btn_installed` silver gradient; browse card installed pill/icon/text → silver; `bg_mp_stable_pill`, `bg_mp_tag_stable`, picker badge texts → silver; account check/active ring → silver. |
| Account popup | `dialog_account_picker.xml` + `item_account_pick.xml` (+ `acc_*` drawables): header with count pill and close, dashed "add account" row, active row silver ring + ✓ disc, delete with shake→confirm; timeline: sheet outBack → rows stagger(55) → checks pop → footer; pick = others recede, row pulses, sheet exits inBack, then login. |
