# Home layering / fullscreen / Settings fixes — root-cause plan

Sandbox has no Android SDK (source-verified, not emulator-verified).

## Root causes (verified in code, matched to the screenshot)

1. **Black strip at top (fullscreen).** `LauncherActivity.shouldIgnoreNotch()`
   returns `false` → `Tools.ignoreNotch(false)` sets
   `LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER`, which letterboxes the window out of the
   cutout/status area → a black bar. Fix: return `true` → `SHORT_EDGES` so the
   window is edge-to-edge; `LauncherHomeFragment` already pads interactive
   content out of the real cutout via `setOnApplyWindowInsetsListener`, so
   controls stay safe. No new black background is added.

2. **Raised hand + legs clipped; LAUNCH overlaps New Instance.** The stage was a
   `wrap_content` `LinearLayout` with `verticalBias` holding a fixed `240×150dp`
   `GLSurfaceView`. On a typical small landscape phone the stack (name + 150dp
   player + launch row ≈ 242dp) is taller than the space between the account chip
   and the carousel (≈214dp), so it overflows: LAUNCH drops onto the New
   Instance card and the short surface crops the character. Fix: rebuild the
   stage as real `ConstraintLayout` children —
   - name pinned near the top,
   - `GLSurfaceView` height `0dp` filling the middle (bottom pinned above the
     launch row), so it can never overlap anything,
   - launch row **bottom-anchored above the carousel with a fixed gap** →
     guaranteed breathing space; LAUNCH can never cover New Instance.
   Then reframe the renderer (Home only) so the whole figure + raised hand fit:
   `mZoomFactor 1.0 → 0.82`, recentre vertically. Skin Management renderer
   untouched (framing is per-instance).

3. **Two name tags.** There is a `lh_hero_name` TextView AND a GL nametag drawn
   at world-y 19.4 (clipped by the ±18 ortho). Fix: keep ONE — the TextView,
   which lives in the UI layer above the GL surface (so it never clips the hand,
   never hides behind the background, follows the stage, scales, stays clear of
   the account selector). Disable the duplicate GL nametag (`setNametag(null)`).
   Required layer order is then exactly: background → 3D character → name tag →
   UI controls.

4. **Settings — launcher background option.** Lives in
   `LauncherPreferenceFragment` (programmatic, "Launcher Customisation" page) as
   `set_custom_launcher_bg` + `remove_custom_launcher_bg`. Fix: stop adding those
   two items to the UI. The underlying code
   (`RightPaneHomeFragment.CUSTOM_BG_PATH`, the image-picker, `ThemeManager`)
   is preserved — only the visible options are removed.

## Settings structure note
The Settings hub is ALREADY a simplified "v5" design: five plain-language root
groups (General / Game / Display / Controls / Advanced) with power-user content
under Advanced, hidden by default, and every legacy page preserved behind them.
So req 7/9's "simpler grouped settings" is largely already in place. This change
therefore does the explicit, safe, testable part (remove the background option)
and keeps the existing grouping rather than blind-rewriting a 2243-line screen
with no build/emulator (which would risk breaking many real options).

## Not touched (req 12)
Left dock, account chip, carousel design, dock nav, download engine — unchanged.
