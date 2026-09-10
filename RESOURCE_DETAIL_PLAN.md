# Plan — Download Resources "page 2", compact Profile Architect popup, app name

## What the user asked (in their words, decoded)
1. **"Download resources ka second page ko badhiya banao"**
   Download Resources = nav button → `ModsSearchFragment` (page 1 = browse grid).
   Page 2 = what opens when you tap a card = **`ModDetailFragment` / `fragment_mod_detail.xml`**
   (hero, stats, install console, about, gallery, versions, deps, links, INSTALL bar).
   → Rebuild it structurally in the graphite language, with lots of mobile-friendly motion.
2. **"Jo popup banaya uske andar Modpack nahi dikh raha — usko chhota banao, beech mein rakho"**
   The Profile Architect bottom sheet is too tall for a landscape phone (sensorLandscape,
   ~360dp of height). Header (3 lines) + 3 cards × ~84dp + margins ≈ 420dp → the Modpack
   card falls below the screen edge. Fix = a **compact, centered** popup:
   - window gravity CENTER, width ≤ 420dp, root wrapped in a ScrollView as safety net
   - header shrunk to one eyebrow + one title line, cards to ~58dp rows
   - keep the three ids `creation_opt_normal/client/modpack` and their click contract.
3. **"App ka naam change karke CS Launcher Plus rakho"**
   Manifest label already resolves to "CS Launcher Plus", but the user still sees "V3" in:
   - GitHub release name "CS Launcher V3", APK `CS-LAUNCHER-V3.apk`, workflow name
   - README headline, `layout-land` comment
   → Rename release/artifact to `CS-LAUNCHER-PLUS.apk`, release "CS Launcher Plus",
     workflow name, README title. Update ci_build helper expectations.
   Leave `applicationId`, Firebase URL, User-Agent strings untouched (identity/backends).
4. **"Bahut saare mobile friendly animations"** — everywhere touched.

## Questions I asked myself
- Q: Is "second page" maybe the Resource Packs tab? → No: tabs are the same page; the
  user talks about pages in sequence ("second page" after tapping). Detail page it is.
- Q: Which ids/types must survive in fragment_mod_detail? → All `detail_*` ids read by
  ModDetailFragment (28 of them). Types: detail_compat_row / *_container = LinearLayout,
  detail_scroll_content = NestedScrollView (only `View` used → any view ok, keep NSV),
  detail_download_button = TextView, detail_backdrop_a/b = ImageView, gallery/deps/links
  cards = View. detail_back_button clickable.
- Q: Do any drawables I'll restyle belong to other screens? → pe5_topbar, pe4_back_btn,
  pe4_btn_save, bg_skinv2_* are shared → DO NOT edit; introduce new `rd_*` drawables.
  bg_mod_* / bg_csclient_row / bg_rs3_halo / pe4_btn_installed_green are detail-only.
- Q: Landscape-only app → detail page should use the width: two-column layout
  (left: sticky identity/install column; right: scrolling content). That is a genuinely
  new structure, not a recolor. Bottom INSTALL bar stays a fixed bar under the left column.
- Q: Java code builds version rows/chips with hard-coded colours → restyle those in code
  (rd_row drawables, silver selected state, staggered reveal when list populates).
- Q: Popup centered on tablets? → cap width 420dp, fine on both.
- Q: Renaming the release asset breaks anything? → In-app updater takes URL from Firebase
  remote config, not from GitHub naming. Safe. The rolling tag stays `v3` (a tag, not UI).

## Build order
1. dialog_creation_type.xml compact + CreationTypeDialog center/scale-in motion
2. New rd_* drawables (page, panel, hero halo, stat tile, pill, chip, row, warn, btn)
3. fragment_mod_detail.xml two-column rebuild (ids preserved)
4. ModDetailFragment: entrance choreography, staggered rows/chips, install button morph,
   compat warning slide, gallery cascade, backdrop parallax on scroll
5. Rename release/APK/workflow/README to CS Launcher Plus
6. Validate XML + id contracts, commit, push, CI, wait, report
