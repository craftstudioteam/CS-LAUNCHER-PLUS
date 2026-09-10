package net.kdt.pojavlaunch.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, offline Minecraft crash triage for the launcher crash screen. */
public final class CrashAnalyzer {
    private CrashAnalyzer() {}

    public static final class Result {
        public String category = "Unknown crash";
        public String title = "Minecraft closed unexpectedly";
        public String explanation = "The game stopped before a clear cause could be identified.";
        @Nullable public String culprit;
        public int confidence = 35;
        public final List<String> suggestions = new ArrayList<>();
    }

    @NonNull
    public static Result analyse(@Nullable String log, int exitCode) {
        Result r = new Result();
        String raw = log == null ? "" : log;
        String l = raw.toLowerCase(Locale.US);

        if (containsAny(l, "outofmemoryerror", "java heap space", "failed to allocate", "cannot allocate memory")) {
            set(r, "Memory", "The game ran out of memory",
                    "Minecraft, a mod, or the renderer exhausted the available Java/native memory.", 96);
            r.suggestions.add("Use the Balanced performance preset or lower allocated RAM if Android is killing the game.");
            r.suggestions.add("Lower resolution, render distance, shaders, and large resource packs.");
            r.suggestions.add("Remove recently added memory-heavy mods.");
        } else if (containsAny(l, "unsupportedclassversionerror", "class file version")) {
            set(r, "Java runtime", "Wrong Java version selected",
                    "This Minecraft/mod version was compiled for a newer Java runtime than the selected JRE.", 98);
            r.suggestions.add("Enable automatic JRE selection in Java Runtime settings.");
            r.suggestions.add("Use Java 8 for legacy versions, Java 17 for 1.17–1.20.4, and Java 21 for newer releases.");
        } else if (containsAny(l, "modresolutionexception", "incompatible mod set", "mod resolution encountered", "depends on", "requires version")) {
            set(r, "Mod dependency", "A mod is missing or incompatible",
                    "The mod loader rejected the installed mod set because a dependency or compatible version is missing.", 95);
            r.culprit = findModHint(raw);
            r.suggestions.add("Open Manage Mods and disable the most recently installed mod.");
            r.suggestions.add("Install the required dependency for the same Minecraft version and loader.");
            r.suggestions.add("Do not mix Fabric, Forge, NeoForge, and Quilt mod files.");
        } else if (containsAny(l, "mixin apply failed", "mixininjectionerror", "invalidinjectionexception", "mixintransformererror")) {
            set(r, "Mod conflict", "A mod mixin failed to apply",
                    "Two mods are conflicting, or one mod targets a different Minecraft/loader version.", 94);
            r.culprit = findMixinHint(raw);
            r.suggestions.add("Remove or update the mod named near the first Mixin error.");
            r.suggestions.add("Check that every mod matches this exact Minecraft and loader version.");
        } else if (containsAny(l, "nosuchmethoderror", "nosuchfielderror", "abstractmethoderror", "incompatibleclasschangeerror")) {
            set(r, "Binary incompatibility", "Two mods or libraries are incompatible",
                    "A mod called a method/field that does not exist in the loaded version of another library.", 93);
            r.culprit = findClassHint(raw);
            r.suggestions.add("Update or remove the mod shown in the first Caused by section.");
            r.suggestions.add("Check API/library mods such as Fabric API, Architectury, Cloth Config, or Forge dependencies.");
        } else if (containsAny(l, "noclassdeffounderror", "classnotfoundexception")) {
            set(r, "Missing library", "A required class or dependency is missing",
                    "A mod attempted to load a class that is not present in the current installation.", 92);
            r.culprit = findMissingClass(raw);
            r.suggestions.add("Install the missing dependency or use the correct mod build.");
            r.suggestions.add("Re-download the affected mod in case its jar is incomplete.");
        } else if (containsAny(l, "glfw error", "egl_bad", "failed to create opengl", "couldn't set pixel format", "glx", "osmesa")
                || (containsAny(l, "opengl", "vulkan", "zink", "gl4es", "mobileglues") && containsAny(l, "error", "failed", "crash"))) {
            set(r, "Graphics renderer", "The selected renderer or GPU driver failed",
                    "Minecraft could not create or maintain the required graphics context.", 90);
            r.suggestions.add("Try MobileGlues or Krypton/GL4ES for compatibility; use Zink only on supported Vulkan devices.");
            r.suggestions.add("Disable shaders and lower the resolution scale.");
            r.suggestions.add("If using Zink on Adreno, try switching between system Vulkan and Turnip.");
        } else if (containsAny(l, "unsatisfiedlinkerror", "dlopen failed", "cannot locate symbol", "native library")) {
            set(r, "Native component", "A native Android library could not load",
                    "The selected renderer, LWJGL module, or runtime contains a missing/incompatible native library.", 91);
            r.culprit = findNativeHint(raw);
            r.suggestions.add("Switch renderer and retry.");
            r.suggestions.add("Reinstall the Java runtime and verify launcher files.");
        } else if (containsAny(l, "sigsegv", "signal 11", "fatal error has been detected by the java runtime") || exitCode == 139) {
            set(r, "Native crash", "The Java or graphics native layer crashed",
                    "A native renderer/driver/JVM fault terminated the game process.", 88);
            r.suggestions.add("Switch renderer, disable shaders, and remove native-performance mods.");
            r.suggestions.add("Try another Java runtime matching the Minecraft version.");
        } else if (containsAny(l, "stackoverflowerror")) {
            set(r, "Mod recursion", "A mod caused a stack overflow",
                    "Code repeatedly called itself until the Java thread stack was exhausted.", 91);
            r.culprit = findClassHint(raw);
            r.suggestions.add("Disable recently added coremods, transformers, or recursive scripting mods.");
        } else if (containsAny(l, "accessdeniedexception", "permission denied", "read-only file system")) {
            set(r, "Storage", "Minecraft could not access a required file",
                    "Android storage permissions or a locked/read-only file prevented startup.", 93);
            r.suggestions.add("Check the selected game directory and Android storage permissions.");
            r.suggestions.add("Make sure another app is not editing or locking the same file.");
        } else if (containsAny(l, "authenticationexception", "invalid token", "unauthorized", "xsts")) {
            set(r, "Account", "Minecraft authentication failed",
                    "The Microsoft/Minecraft session expired or the authentication service rejected it.", 90);
            r.suggestions.add("Remove and sign in to the Microsoft account again.");
            r.suggestions.add("Check device date/time and internet connectivity.");
        } else if (exitCode == 137 || exitCode == 9) {
            set(r, "Android memory pressure", "Android terminated the game process",
                    "The process was killed externally, usually because the device ran low on free memory.", 85);
            r.suggestions.add("Lower Java RAM and resolution, close background apps, and avoid heavy shaders.");
        } else {
            String caused = lastMatch(raw, Pattern.compile("(?m)^Caused by:\\s*([^\\r\\n]+)"));
            String exception = lastMatch(raw, Pattern.compile("(?m)^(?:Exception in thread .*? )?([\\w.$]+(?:Exception|Error):?[^\\r\\n]*)"));
            if (caused != null || exception != null) {
                set(r, "Java exception", "A Java exception stopped Minecraft",
                        caused != null ? caused : exception, 68);
                r.culprit = findClassHint(raw);
            }
            r.suggestions.add("Open Full Log and inspect the first 'Caused by' section.");
            r.suggestions.add("Disable the most recently added mod or configuration change, then retry.");
        }
        return r;
    }

    private static void set(Result r, String category, String title, String explanation, int confidence) {
        r.category = category; r.title = title; r.explanation = explanation; r.confidence = confidence;
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    @Nullable private static String findModHint(String log) {
        String[] patterns = {"(?i)mod id[:=]\\s*([a-z0-9_.-]+)", "(?i)mod ['\"]([^'\"]+)['\"]", "(?i)from mod ([a-z0-9_.-]+)"};
        for (String regex : patterns) { String v = lastMatch(log, Pattern.compile(regex)); if (v != null) return v; }
        return null;
    }
    @Nullable private static String findMixinHint(String log) {
        return lastMatch(log, Pattern.compile("(?i)(?:mixin|from mod)[: ]+([a-z0-9_.$/-]+)"));
    }
    @Nullable private static String findMissingClass(String log) {
        String v = lastMatch(log, Pattern.compile("(?i)(?:NoClassDefFoundError|ClassNotFoundException):\\s*([^\\s]+)"));
        return v == null ? null : v.replace('/', '.');
    }
    @Nullable private static String findNativeHint(String log) {
        return lastMatch(log, Pattern.compile("(?i)(?:dlopen failed:|UnsatisfiedLinkError:)[^\\r\\n]*?([\\w.-]+\\.so)"));
    }
    @Nullable private static String findClassHint(String log) {
        return lastMatch(log, Pattern.compile("(?m)^\\s*at\\s+([a-zA-Z0-9_.$]+)\\("));
    }
    @Nullable private static String lastMatch(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text); String found = null;
        while (m.find()) found = m.group(1).trim();
        return found;
    }
}
