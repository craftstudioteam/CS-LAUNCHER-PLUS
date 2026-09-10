# Plus (+) → Creation Flow — Existing Architecture Analysis

## Existing entry points to profile creation (ALL currently go to `ProfileTypeSelectFragment` = "Setup Hub")
| Entry | File | Behavior |
|---|---|---|
| Floating + button | `RightPaneHomeFragment.java:71` (`fab_create_profile`, `ic_add`, neon green circle) | openChildPane/swapFragment → **Setup Hub** |
| Profile spinner "add" | `mcVersionSpinner.java:166` | swapFragment → **Setup Hub** |
| Instance picker "New instance" | `InstancePickerFragment.java:72` | → **Setup Hub** |

## Existing creation pages (DO NOT DUPLICATE — reuse)
| Type | Fragment | Layout | Notes |
|---|---|---|---|
| Vanilla version | `ProfileEditorFragment` (1251 lines, "Profile Studio") | `fragment_profile_editor.xml` | Creates new profile when args != null: `getProfile()` → `MinecraftProfile.createTemplate()` + `LauncherProfiles.getFreeProfileKey()`. Has icon (cropper), name, **`VersionSelectorDialog.open()`** for MC version, runtime, renderer, RAM, save. |
| Fabric / Quilt | `FabricInstallFragment` / `QuiltInstallFragment` extend `FabriclikeInstallFragment` (647 lines) | `fragment_fabric_install.xml` | Step wizard: game version → **loader version spinner** → download → auto-creates profile |
| Forge / NeoForge | `ForgeInstallFragment` / `NeoForgeInstallFragment` extend `ModVersionListFragment` | same base | Version lists → Java installer |
| OptiFine | `OptiFineInstallFragment` extends `ModVersionListFragment` | same base | OptiFine version list |
| **Modpack** | `ModpackCreateFragment` | `fragment_create_modpack_profile.xml` | Two buttons: Browse (`SearchModFragment` — CurseForge/Modrinth) / Import zip |
| **Client (CS Client)** | `CsClientVersionsFragment` → `CsClientBuilderFragment` → `CsClientPickerFragment` → `CsClientInstallFragment` | `fragment_cs_client_*.xml` | Bundled CS Client versions (Fabric-based), pick mods/modpack, full-screen installer |
| Type chooser (existing) | `ProfileTypeSelectFragment` | `fragment_profile_type.xml` (627 lines, "SETUP HUB") | Cards: CS CLIENT, OptiFine, Fabric, Quilt, Forge, NeoForge, Install Modpack, Vanilla |

## Reusable systems
- **Version data**: `VersionSelectorDialog.open(ctx, hideCustom, listener)` → uses `ExtraConstants.RELEASE_TABLE` (`JMinecraftVersionList`); listener `onVersionSelected(id, isSnapshot)`.
- **Profile model**: `MinecraftProfile.createTemplate()`, `LauncherProfiles.getFreeProfileKey()`, `LauncherProfiles.write()`, `ExtraConstants.REFRESH_VERSION_SPINNER`.
- **Navigation**: `Tools.swapFragment(activity, cls, tag, args)`; inside `MainMenuFragment` use `openChildPane(cls, tag, args)` (right pane), `clearRightPane()`, `reloadSpinner()`.
- **Online gate**: `Tools.hasOnlineProfile()` / `Tools.hasNoOnlineProfileDialog(activity)` (loaders/modpacks require login).
- **Motion**: `UiMotion` (revealScreen, slideIn, fadeInDown, heroIn, popIn, pressFeedback), `MotionCurves.SPRING_SOFT`.
- **Icon picking**: `CropperUtils.startCropper(launcher)` + `CropperListener.onCropped(Bitmap)` (same as ProfileEditor).

## IMPLEMENTATION (new, minimal — no duplicates)
1. **`dialog_creation_type.xml` + `CreationTypeDialog.java`** — 3-choice rounded popup: Normal Version / Client / Mod Pack. Dark graphite style consistent with launcher.
   - Normal Version → open NEW full-screen `VersionCreateFragment`
   - Client → existing `CsClientVersionsFragment`
   - Mod Pack → existing `ModpackCreateFragment`
2. **`fragment_version_create.xml` + `VersionCreateFragment.java`** — NEW full-screen creation page (the ONLY new screen): Icon → Name → Loader chips (Vanilla/Fabric/Forge/NeoForge/Quilt/OptiFine) → conditional Fabric Loader selector (only Fabric/Quilt) → Minecraft Version (`VersionSelectorDialog`) → CREATE button.
   - Create behavior per loader:
     - **Vanilla** → create profile directly (template + name + icon + lastVersionId), then open ProfileEditorFragment for advanced settings
     - **Fabric/Quilt/Forge/NeoForge/OptiFine** → route to existing install fragment (loader wizard handles loader-version + download + profile creation)
3. **Wire FAB** (`RightPaneHomeFragment`) + `InstancePickerFragment` "New instance" to show `CreationTypeDialog` instead of going straight to Setup Hub.
