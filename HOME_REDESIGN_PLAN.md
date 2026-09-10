# Home Screen — Renderer + Launch + Progress-Bar Rework (implementation plan)

> Follows the developer prompt: **RESEARCH → ANALYZE → IMPLEMENT → TEST**.
> Sandbox has **no Android SDK**, so this branch is source-verified, not
> emulator-verified. On-device test checklist is at the bottom.

## Findings from analysis (what is really going on)

1. **The 3D renderer is already advanced.** `SkinGLRenderer` (GLES20) already
   does: Classic + Slim geometry, 64×64 UV layout, legacy 64×32 expansion with
   mirrored limbs, overlay layers (hat/jacket/sleeves/pants + cape) with real
   alpha blending, NEAREST texture filtering, diffuse **and** fresnel-rim
   lighting, a soft contact-shadow disc, correct depth, per-joint pose hooks,
   a floating Minecraft name-tag, transparent (non-black) compositing, correct
   `onSurfaceChanged` resize, `RENDERMODE_WHEN_DIRTY` (battery-safe), and
   native-crash breadcrumbs. It is NOT a flat/box toy model.

2. **THE actual visible defect — a units bug.** Pose angles are applied with
   `Matrix.rotateM(...)`, which takes **degrees**. But `LauncherHomeFragment`
   was feeding radian-magnitude numbers: left arm `2.62`, head yaw `0.17`, etc.
   `2.62°` ≈ no movement → the "hand-up greeting" was effectively invisible.
   **Fixing this is the single highest-impact change** and is why the character
   looked like it was "just standing".

3. **Progress bar is cleanly separable.** `ProgressKeeper` is the data layer
   (drives the notification service, `LaunchTracker`, cancellation, task count);
   `ProgressLayout` is ONLY the on-screen widget. Its `setProgress()` statics
   just forward to `ProgressKeeper`. => We can delete the widget from the UI and
   every download call keeps working, notifications keep working.

## Changes implemented

### 1. Launch button moved up (Req 1)
`fragment_launcher_home.xml`: the launch cluster (name ↓ 3D char ↓ LAUNCH)
vertical bias raised `0.28 → 0.20`, and the LAUNCH row top-margin trimmed so it
sits closer under the character without overlapping it. Order/spacing preserved.

### 2. Renderer upgrade (Req 2, 3, 4)
- Kept the proven `SkinGLRenderer` pipeline (it already meets the technical
  bullets) and **rebuilt the Home pose driver on top of it** correctly.
- `LauncherHomeFragment.drivePose()` rewritten to use **degrees**, so the
  **LEFT arm is genuinely raised** (~150°, hand up beside the head, clear of it).
- **HELLO greeting state machine**: IDLE → NOTICE (head turns) → RAISE (eased
  arm lift) → WAVE (a few smooth oscillations) → HOLD (relaxed raised-hand pose)
  with subtle head/shoulder/torso/breathing motion. Uses easing, driven by the
  real arm joint — the whole body is never rotated to fake it.
- Motion runs only while Home is visible (`onResume`/`onPause`), one animator,
  render-on-demand — no extra render loops, no leaks.

### 3. Account skin parity + robustness (Req 5, 6, 10)
- `loadPlayerSkin()` now also loads the account **cape** (`{username}_cape.png`),
  matching the Skin Management page exactly (same `{username}_skin.png` source).
- Premium (Microsoft) skins already resolve through the shared
  `{username}_skin.png` file that `updateOfficialSkin()` / Skin Management write;
  Home reads the same file, and falls back to the Mojang UUID→textures pipeline
  (`ShortcutSkinHeadHelper.getFullSkinFile`) if the file is not present yet.
- Missing/failed network → valid cached skin if present, else the bundled
  default; name-tag shows the username, or **"Player"** when logged out
  (never null/blank).

### 4. Old progress bar removed (Req 8) WITHOUT breaking downloads (Req 9)
- Removed `<com.kdt.mcgui.ProgressLayout>` from BOTH
  `layout/activity_pojav_launcher.xml` and `layout-land/…`.
- `LauncherActivity`: dropped the field, `findViewById`, `observe(...)`,
  `cleanUpObservers()`, and `add/removeTaskCountListener(mProgressLayout)`.
  The "tasks ongoing" guard now asks `ProgressKeeper.hasOngoingTasks()`.
- **Untouched:** `ProgressKeeper`, `DownloadControl`, every
  `ProgressLayout.setProgress/clearProgress` call in the download tasks (they
  forward to `ProgressKeeper`), the notification `ProgressService`, retry,
  cancellation, verification. Download system is fully intact; only the on-screen
  bar is gone.

## On-device test checklist (needs a real build + device)
- [ ] Classic/Steve skin renders correct proportions + overlays.
- [ ] Slim/Alex skin renders with 3px arms.
- [ ] Microsoft account (e.g. `OnlyJahirOG`) shows its real skin, not Steve.
- [ ] Switch account → skin + name-tag transition; no null/blank tag.
- [ ] Logged-out → default character + "PLAYER" tag.
- [ ] Left hand raises and waves smoothly (verify Z sign on device; flip
      `GREET_ARM_DEG` sign if it swings the wrong way).
- [ ] Home recreate / navigate away & back → pose restarts, no leak.
- [ ] Background/foreground lifecycle → GL pauses/resumes.
- [ ] Start any download → NO progress bar appears anywhere; notification still
      shows status; cancel/retry still work.
