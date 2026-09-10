# Plus (+) → Creation Flow — Implementation Summary

## Flow (as specified)
```
PLUS (+) BUTTON  (fab_create_profile / instance picker / version spinner "+")
      │
      ▼
CreationTypeDialog (popup — 3 choices)
      ├── Normal Version → VersionCreateFragment (NEW full-screen page)
      │        Icon → Name → Loader chips → conditional loader version
      │        → Minecraft version (existing VersionSelectorDialog) → Create
      │             ├── Vanilla     → profile created directly → ProfileEditorFragment
      │             ├── Fabric/Quilt→ existing FabricInstall/QuiltInstall wizard
      │             ├── Forge/Neo   → existing ForgeInstall/NeoForgeInstall
      │             └── OptiFine    → existing OptiFineInstall
      ├── Client  → existing CsClientVersionsFragment (CS Client flow)
      └── Mod Pack→ existing ModpackCreateFragment (browse/import)
```

## New files (10)
| File | Purpose |
|---|---|
| `fragments/CreationTypeDialog.java` | 3-choice popup (Normal Version / Client / Mod Pack), routes via existing nav rules |
| `res/layout/dialog_creation_type.xml` | Popup layout — dark graphite, rounded option cards, entrance stagger |
| `fragments/VersionCreateFragment.java` | Full-screen guided version creation (the only new screen) |
| `res/layout/fragment_version_create.xml` | Icon + name + loader chips + conditional loader-version + MC version + CREATE |
| `bg_creation_dialog.xml` | Dialog shell (24dp radius, gradient, hairline stroke) |
| `bg_creation_option.xml` | Rounded option row w/ ripple |
| `bg_creation_icon_well.xml` | Rounded icon well |
| `bg_creation_field.xml` | Selectable field (version/loader picker) |
| `bg_loader_chip.xml` / `bg_loader_chip_active.xml` | Loader chips (idle glass / platinum selected) |

## Modified files (3) — only the + entry points
| File | Change |
|---|---|
| `RightPaneHomeFragment.java` | FAB now shows `CreationTypeDialog` instead of going straight to Setup Hub |
| `InstancePickerFragment.java` | "New instance" row now shows `CreationTypeDialog` |
| `com/kdt/mcgui/mcVersionSpinner.java` | Spinner "+" extra action shows `CreationTypeDialog` |

## Reused (NOT duplicated)
- Version data/picker: `VersionSelectorDialog` (RELEASE_TABLE)
- Loader install + profile creation: `FabricInstallFragment`/`QuiltInstallFragment` (FabriclikeInstallFragment wizard incl. loader-version), `ForgeInstallFragment`, `NeoForgeInstallFragment`, `OptiFineInstallFragment`
- Loader meta for the optional loader-version dropdown: `FabriclikeUtils.downloadLoaderVersions()`
- Client flow: `CsClientVersionsFragment` → `CsClientBuilderFragment` → `CsClientInstallFragment`
- Modpack flow: `ModpackCreateFragment` → `SearchModFragment` / import
- Profile model: `MinecraftProfile.createTemplate()`, `LauncherProfiles.getFreeProfileKey()/write()`, `ExtraConstants.REFRESH_VERSION_SPINNER`
- Advanced editor: `ProfileEditorFragment`
- Nav: `Tools.swapFragment` / `MainMenuFragment.openChildPane` / `clearRightPane`
- Motion: `UiMotion` (revealScreen, heroIn, slideIn, popIn, pressFeedback)
- Icon crop: `CropperUtils.registerCropper/startCropper`
- Online gate: `Tools.hasOnlineProfile` / `hasNoOnlineProfileDialog` (loaders/modpacks)

## Dynamic UI behavior
- Fabric/Quilt selected → "FABRIC/QUILT LOADER" version row appears; hidden for Vanilla/Forge/NeoForge/OptiFine
- Fabric/Quilt loader versions fetch from existing meta utils for the chosen MC version
- CREATE → Vanilla creates immediately; loaders route to existing wizards (login-required preserved)
- Design language: #0B0B0E graphite background, platinum gradient primary button, glass cards — matches existing CS UI
