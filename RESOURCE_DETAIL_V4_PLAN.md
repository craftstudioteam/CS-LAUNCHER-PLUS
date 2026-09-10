# Resource Detail v4 + Profile Architect v4 — plan

## What the user said (decoded)
1. Detail page structure is fine ("badhiya hai theek hai"), BUT:
   a. **Version selection is not easy to use** → make it a first-class, obvious control.
   b. **Background image should slide / change** → the slideshow never changes today.
   c. **Card design should be better** → upgrade rows/sections to premium cards.
   d. **anime.js-style animations everywhere**, mobile friendly.
2. **Popup is now too small** → find the sweet spot: bigger than v3, but every option
   visible in landscape. Make it "perfect".

## Root causes found (explored)
- Backdrop never slides: `ModDetailFragment` only ever feeds `mModItem.imageUrl` to the
  stage; `mModDetail.screenshotUrls` is never populated for this fragment (only
  `ModInstallFragment` calls `fetchProjectInfo()` and copies `galleryUrls`). Also
  `ModItem.galleryUrls` from Modrinth search hits is ignored here.
- Backdrop is a 28px blob: `BACKDROP_TINY_W = 28` — intentionally blurred. For a
  96dp stage that just looks like mud. Use ~160px + scrim instead so the picture reads.
- Version selection: tiny "Selected file … CHANGE" console + a long list at the bottom of
  the right column. Users don't find the list; the AlertDialog picker is a stock list.

## Design decisions
### Detail page
- **Stage** grows to 120dp with real (160px) imagery, Ken-Burns pan+zoom per slide, crossfade,
  dot indicators (rd_dot on/off), tap on the stage = next slide; swipe left/right also works.
  Sources merged: `ModItem.galleryUrls` (immediate) → `fetchProjectInfo().galleryUrls`
  (background, best effort) → icon fallback.
- **Version selection**: the Install console becomes a **"FILE" selector card**:
  * big selected-file row (name, MC · loader, compat pill) with a chevron → tap opens a
    **graphite bottom-sheet picker** (new `ModVersionSheet` DialogFragment): search-free list,
    compatible files first with a "RECOMMENDED" tag, mismatches grouped below, each row
    animated in with stagger; tapping selects + closes with a spring.
  * a **horizontal quick-pick strip** of the top 6 compatible files right under it (chips),
    so most users never open the sheet.
  * "Available files" section on the page stays as the full list (still selectable), rows
    become richer cards (index badge, name, meta, changelog preview, compat pill, check).
- **Card design**: rd_section gets a top-light gradient + inner hairline; version rows gain a
  left accent bar when selected; the stat tiles get subtle gradients; consistent 18–20dp radii.
- **anime.js-inspired motion vocabulary** (implemented with Android animators, no JS):
  * eases: outExpo (fast-in/slow-settle) = PathInterpolator(0.16,1,0.3,1); outBack =
    OvershootInterpolator; spring(bounce .35) ≈ OvershootInterpolator(1.4); inOutQuart;
    outElastic for the check-mark.
  * stagger(60) grid/list reveals; stagger from:'center' for chips.
  * Timeline-like chained entrances (side panel → stage → hero → stats → console → dock).
  * keyframes: install button "pulse" when ready; sheet rows "fadeSlide"; dots "morph".
  * Text: counter roll-ups (already), title letter-spacing tween (fake "splitText").
  * All durations respect MotionSpeed; no infinite loops on clickable views.
### Popup
- Width 460dp max (was 420), height budget ≤ 300dp on a 360dp-tall landscape:
  header 48 + 3 rows × 64 + gaps 8×2 + paddings 32 ≈ 290dp. Rows 64dp with 44dp
  medallions, 13.5sp titles, 10sp subtitles, chevron pills 30dp. Add a slim footer hint
  line ("You can change the loader and version on the next screen").
- Motion: backdrop dim fades; card `outBack` scale 0.9→1 with y 24→0; rows stagger(70)
  from x+24 with outExpo; medallion icons pop with `outElastic`; badge shimmer once;
  featured row idle "breathing ring" (alpha 0.55↔1, non-clickable ring view so it does
  not fight press feedback); tap → chosen row scales 1.03 + others recede → sheet exits
  with inBack.

## Contracts kept
- fragment_mod_detail ids/types unchanged; only additions (detail_stage_dots,
  detail_quick_versions, detail_selected_* ids).
- CreationTypeDialog ids creation_opt_normal/client/modpack + routing unchanged.

## Order
1. Popup layout + Java (fast win)  2. Backdrop slideshow fix (gallery sources, size, KB)
3. Version selector card + quick strip + ModVersionSheet  4. Card polish + motion pass
5. Validate ids/drawables/XML, commit, push, CI wait, report.

## Shipped in this pass (phase 6)

| Area | What changed |
|---|---|
| Profile Architect popup | `dialog_creation_type.xml` v4: centred 460dp card, 64dp option rows, medallion icons, FEATURED ring; `CreationTypeDialog` runs an anime-style timeline (outBack card → stagger(70) rows → outElastic icons → badge pop → breathing ring) and a pick-and-go exit. |
| Motion vocabulary | `Anime.java`: outExpo/outQuart/inOutQuart/outBack/inBack/spring/outElastic eases, `in()/out()/stagger()/staggerCenter()/pulse()/shake()/pop()/breathe()/swapText()/tightenTitle()`. All durations go through `MotionSpeed`. |
| Stage slideshow | Merges search-hit gallery + `fetchProjectInfo().galleryUrls` + icon, 320px thumbs (was a 28px blur), Ken-Burns alternate pan, 700ms crossfade, dots morph into a pill, `1 / n` glass counter, tap = next, fling = prev/next, gallery thumb tap jumps the stage, parallax on scroll. |
| File selector card | One big selected-file row (check disc · name · MC/loader meta · state pill · CHANGE) that opens `ModVersionSheet`; quick-pick chips for the newest compatible files; "n of m compatible" pill on the history card. |
| ModVersionSheet | Bottom sheet (86% height) with RECOMMENDED / OTHER FILES bands, Compatible/All filter chips, stagger(45) rows, pop-then-close pick. Listener falls back to the parent fragment (`ModDetailFragment implements ModVersionSheet.Listener`) so it survives recreation. |
| Version history rows | Index disc → ✓ morph, meta line, 2-line changelog teaser, LATEST/OK/MISMATCH pill. |
