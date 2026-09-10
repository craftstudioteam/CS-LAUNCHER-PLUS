# V3 tweaks — skin size, name tag, left panel, black strip, Settings polish

## Home
1. **Left panel back to normal position.** My previous `shouldIgnoreNotch()`
   override returned `true`, forcing `SHORT_EDGES` on every device so the dock
   hugged the extreme screen edge even when the user's *Ignore Notch* setting
   was OFF. Revert it to `return PREF_IGNORE_NOTCH;` → dock sits where it used
   to, and the notch pref is respected again.
2. **Black horizontal line at the very top.** Cause: the launcher theme paints
   `statusBarColor = #0B0B0E` (a solid strip). Fix: set status + navigation bar
   colors to `@android:color/transparent` in the launcher theme (both `values`
   and `values-land`) so fullscreen is edge-to-edge with no painted strip. Not a
   cover-up — the bar simply stops drawing a colour.
3. **Bigger, centred skin.** Raise renderer zoom (0.82 → ~0.96) and recentre so
   the larger figure + raised hand still fit; the GL surface already fills the
   band and is horizontally centred over the LAUNCH capsule.
4. **Name tag right above the head.** Tighten the tag→player gap so the plate
   sits directly over the head.

## Settings — animated visual refresh
The Settings screen is already well-structured (header + search + category rail
+ recycler + save bar, 5 plain groups). This pass makes it look premium:
- New/upgraded drawables for row cards, category accent, chips.
- Staggered entrance animation for category groups + rows.
- Keep every option, grouping and the left navigation. No data changes.
