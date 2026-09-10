# Profile Hold → Drag Tutorial — Rebuild Plan (Priority 1 + 2)

> Date: 2026-09-05 · Scope: Master task Priority 1 & 2 (drag tutorial rebuild + auto dummy instance)

## Root cause of the old, insufficient demo

1. **Fake overlay card.** `ProfileDragDemoView` inflated `item_library_card` *inside the tutorial overlay* (decor view). The card floated over the screen, detached from the real carousel — users never saw *their* library respond.
2. **Guessed destination.** "Front slot" was hard-coded as `carouselLeft + 8dp`. Any carousel scroll state made the highlight point at the wrong place.
3. **Fixed one-shot script.** Finger hovered 8dp above the card; horizontal translate only; no visible card/finger lockstep, no readable HOLD phase → the critical "keep holding while moving" idea never landed.
4. **Zero-profile case broken.** With no profiles the carousel view is `GONE` (empty hint). The demo fell back to an arbitrary rectangle at screen bottom and the practice phase could never succeed.
5. **Fragile completion.** Completion came from an `AdapterDataObserver.onItemRangeMoved` + a long-click fallback on `getChildAt(0)` — neither *requires* hold+drag+reorder+release on a real card.

## New architecture

```
REAL InstanceLibraryAdapter  (data layer, transient demo items injected)
        │  renders with the REAL InstanceHolder / item_library_card binder
        ▼
"Steve" demo card  — a real RecyclerView item inside the real carousel,
                     at position 1 (position 0 = real primary, or "Alex")
        │
DragTutorialHost (implemented by LauncherHomeFragment, WeakRef registry)
        │  exposes: insert/remove dummies, live card View + screen rects,
        │           practice instrumentation from the real ItemTouchHelper
        ▼
ProfileDragDemoView (overlay choreography: finger + HOLD chip + dest glow)
```

### Demo phase (scripted, on the REAL card)
1. Host injects transient `Steve` (never persisted — key prefixed `\0tutorial.demo.`).
   If there are 0 real profiles, `Alex` is injected too so a "front" exists at all.
   Carousel is scrolled to position 0 and force-shown (empty hint hidden).
2. Finger appears → descends onto the real Steve card.
3. **PRESS** (250 ms): finger compresses.
4. **HOLD** (850 ms explicit pause): finger stays compressed, the *real card* lifts
   (scale 1.06, elevation 10dp), destination glow fades in, "HOLD…" chip shows.
5. **DRAG** (1000 ms): one shared `ValueAnimator` drives finger X **and** the card's
   `translationX` in perfect lockstep toward the real slot-0 bounds. Glow pulses.
6. **RELEASE** (300 ms): finger rises/fades; card settles with overshoot. "PERFECT!"
7. Dummies removed (or kept for practice when <1 real… see below) with RV animations.

### Practice phase (real, no fakes)
- Instrumented `ItemTouchHelper`: `onSelectedChanged(ACTION_STATE_DRAG)` arms,
  successful `onMove` with from≠to records a real reorder, `clearView` (release)
  with the reorder flag set ⇒ **success**. No taps, no timers.
- If the user has **≥2 real profiles**, dummies are removed after the demo; practice
  targets the real cards. With **0–1 real profiles**, demo card(s) stay so a real
  `ItemTouchHelper` reorder is physically possible (dummies are still never persisted).

### Isolation guarantees (Part 4)
- Demo keys live only in adapter memory; filtered out of `getOrderedProfileKeys()` ⇒
  `LauncherProfiles.applyProfileOrder()` can never see them.
- `dispatchOrderChanged()` is fully suppressed while the filtered list is empty.
- Dummies removed on: demo end, practice end, task change/skip, overlay cleanup,
  fragment destroy. App restart ⇒ gone (memory only).
- No Firebase, no shortcuts, no account state, no launch path ever touches them.
