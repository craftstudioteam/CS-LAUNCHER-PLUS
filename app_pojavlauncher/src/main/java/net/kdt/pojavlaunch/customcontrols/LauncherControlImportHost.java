package net.kdt.pojavlaunch.customcontrols;

import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;

/** Launcher-only hooks surfaced inside the control property side panel. */
public interface LauncherControlImportHost {
    void requestControlImage(ControlInterface control);
}