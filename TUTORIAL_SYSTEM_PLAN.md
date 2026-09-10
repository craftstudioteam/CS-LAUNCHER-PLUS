# CS Client - Interactive Guided Task Tutorial System Architecture

## 1. Vision & Core Philosophy

The **Interactive Guided Task Tutorial System** replaces static dialogs and slideshows with an active, hands-on learning experience. 

- **Every Tutorial is a Task:** The user learns by performing real interactions on the real UI.
- **Visual Cue Hierarchy:** Screen stays visible behind a light translucent dim -> Spotlight cutout on actual target -> Animated finger pointing down -> Compact directional tooltip.
- **Strict Real Action Detection:** No fake timer advances; completion requires real user actions (e.g., account created, card dragged and reordered, button tapped).
- **Graceful Skip Flow:** Skipping a task marks it `SKIPPED` and transitions to the next task in the chain without breaking the tutorial pipeline.
- **State Separation:** `COMPLETED` (action performed) is distinctly tracked from `SKIPPED` (bypassed).

---

## 2. System Architecture & Class Hierarchy

```
net.kdt.pojavlaunch.tutorial/
├── TutorialEngine.java              # Central orchestrator & sequence controller
├── TutorialTask.java                # Immutable task specification (ID, ActionType, Target, Copy, etc.)
├── TutorialTaskState.java           # State Enum: NOT_STARTED, ACTIVE, COMPLETED, SKIPPED
├── TutorialActionType.java          # Action Enum: INFORMATION, TAP, ACCOUNT_CREATION, DRAG_DEMO, FINISH
├── TutorialTarget.java              # Lazy view resolver (byId, firstVisible, custom predicate)
├── TutorialOverlayView.java         # Lightweight decor overlay managing views & touch routing
├── TutorialSpotlightView.java       # Hardware-accelerated clear cutout + glow + border
├── TooltipView.java                 # Responsive tooltip card with directional arrow & buttons
├── FingerPointerView.java           # Animated downward-pointing finger with bounce/pulse
├── ProfileDragDemoView.java         # 2-Phase drag demonstrator (Fake Card Demo -> Real Drag Detector)
├── AccountTaskObserver.java         # Monitors ExtraCore ACCOUNT_CHANGED & PojavProfile state
└── HomeTutorialSequence.java        # Concrete 7-task sequence for the Home Screen
```

---

## 3. Sequence Definition (Home Screen 7-Task Flow)

| # | Task ID | Action Type | Target Component | Description / Behavior | Completion Condition | Skip Behavior |
|---|---|---|---|---|---|---|
| **1** | `task_welcome` | `INFORMATION` | None (Center screen) | "Welcome to CS Client!" Short intro to the launcher. | User taps **[ Next ]** | Taps **[ Skip Tutorial ]** -> Skips to Finish or exits |
| **2** | `task_account` | `ACCOUNT_CREATION` | `R.id.lh_account_chip` | "Add an Account" -> Points to account chip. Guides user to add a Local/MS/Ely.by account. | `ExtraConstants.ACCOUNT_CHANGED` fired AND account exists in `PojavProfile` | Taps **[ Skip ]** -> Marks task `SKIPPED` -> Moves to Task 3 |
| **3** | `task_profile` | `TAP` *(Intercepted)* | `R.id.lh_account_chip` / `R.id.lh_hero_name` / `R.id.lh_player` | "Your Profile" -> Tap profile/3D model to inspect player info & cosmetics. | User taps the highlighted profile area | Taps **[ Skip ]** -> Moves to Task 4 |
| **4** | `task_library` | `TAP` *(Intercepted)* | `R.id.lh_library` | "Your Instances" -> Tap carousel to browse Minecraft instances & configurations. | User taps the library carousel | Taps **[ Skip ]** -> Moves to Task 5 |
| **5** | `task_drag_reorder` | `DRAG_DEMO` | `R.id.lh_library` | **Phase 1:** Fake "Steve" card demo (Press -> Hold -> Lift -> Drag to front -> Release).<br>**Phase 2:** "Now you try!" -> User performs real drag on carousel. | `AdapterDataObserver.onItemRangeMoved` triggered on real RecyclerView | Taps **[ Skip ]** -> Marks `SKIPPED` -> Moves to Task 6 |
| **6** | `task_play` | `TAP` *(Intercepted)* | `R.id.lh_launch_button` | "Ready to Play" -> Tap Play capsule to launch the primary instance. Intercepted during tutorial. | User taps Play button | Taps **[ Skip ]** -> Moves to Task 7 |
| **7** | `task_finish` | `FINISH` | None (Center screen) | "You're all set!" Summary card. | User taps **[ Finish ]** | Taps **[ Finish ]** -> Persists completion |

---

## 4. Detailed Component Designs

### 4.1. Task States & Persistence
- **Storage:** `LauncherPreferences.DEFAULT_PREF` (`"cslauncher_settings"`).
- **Keys:**
  - `tutorial.task.<id>.state`: `"COMPLETED"` | `"SKIPPED"` | `"NOT_STARTED"`
  - `tutorial.home.current_task`: String ID of active task
  - `tutorial.home.completed`: Boolean flag indicating full sequence completion.
- **Recovery:** On app restart, if `tutorial.home.completed` is false, `TutorialEngine` resumes from `tutorial.home.current_task`.

### 4.2. Account Creation Detection (`AccountTaskObserver`)
1. **Initial Check:** If the user already has a valid account on first launch (`!PojavProfile.getAllProfilesList().isEmpty()`), the task can highlight the account chip and auto-complete or prompt for confirmation.
2. **Creation Event Hook:**
   - Listens to `ExtraConstants.ACCOUNT_CHANGED` via `ExtraCore.addExtraListener`.
   - When fired, checks `PojavProfile.getCurrentProfileContent(ctx, null) != null`.
   - On success, shows "Account Connected!" banner and auto-advances to Task 3.
3. **Skip Handling:** If user presses `[ Skip ]`, observer unhooks immediately and proceeds to Task 3 without blocking.

### 4.3. Profile Drag Demonstration & Detection (`ProfileDragDemoView`)
1. **Phase 1 (Visual Demonstration):**
   - Renders a temporary `188x48dp` glass-styled fake card ("Steve") on the overlay.
   - Finger touches card -> Presses (scale 1.05x, elevation 10dp) -> Holds -> Drags leftward to slot 0 -> Destination glow activates -> Card settles -> Finger fades out.
   - Card moves synchronously with the finger.
2. **Phase 2 (User's Turn):**
   - Fake card removed. Real `lh_library` RecyclerView highlighted with spotlight.
   - Tooltip updates to: *"Now you try! Press and hold a profile, then drag it to the front."*
   - Touches forwarded to real RecyclerView.
   - `AdapterDataObserver.onItemRangeMoved` listens for actual item swap.
   - On detected reorder: Plays success pulse, shows *"Perfect!"*, then advances to Task 6.

### 4.4. Visual Overlay & Positioning
- **Translucent Dim:** `0x55141018` (~33% black) — Home screen controls and graphics remain clearly visible.
- **Spotlight Hole:** Hardware-accelerated `PorterDuff.Mode.CLEAR` with `18dp` corner radius, `2.5dp` purple border (`0xBB8B5CF6`), and `5dp` glow stroke (`0x338B5CF6`).
- **Downward Pointing Finger:** Stylized teardrop pointer pointing DOWN at target with an infinite gentle tap/bounce animation (`scale 1.0 -> 0.88`, `translationY 0 -> +6dp`).
- **Responsive Tooltip:**
  - Placed ABOVE target if target is in bottom half of screen (arrow points DOWN).
  - Placed BELOW target if target is in top half of screen (arrow points UP).
  - Uses `FrameLayout.LayoutParams` margins (`leftMargin`, `topMargin`) to guarantee position stability across view tree relayout passes.
  - Retries positioning if dimensions are pending measurement (`w <= 0 || h <= 0`).

---

## 5. Extensibility for Future Tutorials
Future modules can be registered as independent sequences:
- `CosmeticsTutorialSequence` (Store navigation, 3D cape preview, equipping items)
- `CustomControlsTutorialSequence` (Button mapping, layout editor, gesture controls)
- `SettingsTutorialSequence` (RAM allocation, renderer selection, Java arguments)

Each sequence runs through the same `TutorialEngine` with full lifecycle management.
