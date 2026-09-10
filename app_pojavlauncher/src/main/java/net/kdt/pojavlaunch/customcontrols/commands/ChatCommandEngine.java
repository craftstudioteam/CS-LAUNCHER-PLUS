package net.kdt.pojavlaunch.customcontrols.commands;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase-3 Command Studio engine.
 *
 * Backwards compatible: the ControlData.command field is still ONE String, so
 * all existing control JSON imports keep working. The string can now contain a
 * compact multi-line automation script:
 *
 *   # comment
 *   var:mode=pro
 *   /say Hello ${player}
 *   delay:250
 *   repeat:3 /particle minecraft:happy_villager ~ ~ ~
 *   if:${mode}==pro
 *   /effect give ${player} minecraft:speed 10 2
 *
 * Directives are parsed lazily at execution time so variables can change the
 * very next step. Parsing/execution is allocation-bounded (steps capped) and
 * every delay runs through the launcher main Handler.
 */
public final class ChatCommandEngine {

    public interface CommandSink {
        /** Sends one fully-resolved chat line (with or without leading slash). */
        void send(@NonNull String chatLine);
    }

    public static final class Step {
        public final int line;
        public final String text;
        public final boolean directive;

        Step(int line, String text, boolean directive) {
            this.line = line;
            this.text = text;
            this.directive = directive;
        }
    }

    public static final class ValidationResult {
        public final List<Step> steps = new ArrayList<>();
        public int errorLine = -1;
        public String error;

        public boolean isValid() {
            return error == null;
        }
    }

    private static final String HISTORY_FILE = "csl_command_studio";
    private static final String HISTORY_KEY = "script_history";
    private static final int HISTORY_CAP = 20;
    private static final int MAX_STEPS = 80;
    private static final long MAX_DELAY_MS = 15_000L;
    private static final int MAX_REPEAT = 20;

    private ChatCommandEngine() {}

    @NonNull
    public static ValidationResult validate(@NonNull String script) {
        ValidationResult result = new ValidationResult();
        String[] lines = script.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int expanded = 0;

        String pendingCondition = null;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (expanded >= MAX_STEPS) {
                result.errorLine = i + 1;
                result.error = "script expands beyond " + MAX_STEPS + " actions";
                return result;
            }

            String lower = lower(trimmed);
            if (lower.startsWith("if:")) {
                if (trimmed.length() <= 3) {
                    result.errorLine = i + 1;
                    result.error = "empty condition";
                    return result;
                }
                pendingCondition = trimmed.substring(3).trim();
                result.steps.add(new Step(i + 1, trimmed, true));
                continue;
            }

            if (lower.startsWith("repeat:")) {
                int split = trimmed.indexOf(' ', 7);
                if (split < 0 || split + 1 >= trimmed.length()) {
                    result.errorLine = i + 1;
                    result.error = "repeat needs a count and a command";
                    return result;
                }
                int count = parsePositiveInt(trimmed.substring(7, split), -1);
                if (count < 1 || count > MAX_REPEAT) {
                    result.errorLine = i + 1;
                    result.error = "repeat count must be 1…" + MAX_REPEAT;
                    return result;
                }
                String payload = trimmed.substring(split + 1).trim();
                if (payload.isEmpty()) {
                    result.errorLine = i + 1;
                    result.error = "repeat payload is empty";
                    return result;
                }
                for (int r = 0; r < count && expanded < MAX_STEPS; r++) {
                    result.steps.add(new Step(i + 1, payload, false));
                    expanded++;
                }
                pendingCondition = null;
                continue;
            }

            if (lower.startsWith("delay:")) {
                long delay = parseLong(trimmed.substring(6), -1);
                if (delay < 0 || delay > MAX_DELAY_MS) {
                    result.errorLine = i + 1;
                    result.error = "delay must be 0…" + MAX_DELAY_MS + " ms";
                    return result;
                }
                result.steps.add(new Step(i + 1, trimmed, true));
                expanded++;
                continue;
            }

            if (lower.startsWith("var:")) {
                int eq = trimmed.indexOf('=');
                if (eq <= 4) {
                    result.errorLine = i + 1;
                    result.error = "var syntax is var:name=value";
                    return result;
                }
                result.steps.add(new Step(i + 1, trimmed, true));
                expanded++;
                continue;
            }

            // A condition applies to exactly the next executable step.
            result.steps.add(new Step(i + 1,
                    pendingCondition != null
                            ? "\0if\0" + pendingCondition + "\0" + trimmed
                            : trimmed,
                    false));
            pendingCondition = null;
            expanded++;
        }
        return result;
    }

    /** Execute a script. Calls step callbacks entirely through the main Handler. */
    public static void execute(@NonNull Context context,
                               @Nullable String script,
                               @NonNull CommandSink sink) {
        if (script == null) return;
        ValidationResult parsed = validate(script);
        if (!parsed.isValid() || parsed.steps.isEmpty()) return;
        recordHistory(context, script);

        Map<String, String> vars = defaultVars(context);
        List<Step> steps = parsed.steps;
        runStep(context, steps, 0, vars, sink);
    }

    /** Resolve the script for a Test/Preview dialog without touching game input. */
    @NonNull
    public static List<String> dryRun(@NonNull Context context, @NonNull String script) {
        List<String> out = new ArrayList<>();
        ValidationResult parsed = validate(script);
        if (!parsed.isValid()) {
            out.add("Error line " + parsed.errorLine + ": " + parsed.error);
            return out;
        }
        Map<String, String> vars = defaultVars(context);
        String pendingCondition = null;
        for (Step step : parsed.steps) {
            String text = step.text;
            String lower = lower(text);
            if (lower.startsWith("var:")) {
                applyVar(text, vars);
                out.add("set " + text.substring(4));
            } else if (lower.startsWith("delay:")) {
                out.add("wait " + text.substring(6) + " ms");
            } else if (lower.startsWith("if:")) {
                pendingCondition = text.substring(3);
                out.add("if " + resolveVars(pendingCondition, vars));
            } else if (text.startsWith("\0if\0")) {
                int end = text.indexOf('\0', 4);
                String condition = text.substring(4, Math.max(4, end));
                String payload = text.substring(end + 1);
                if (conditionPasses(condition, vars)) out.add("send " + resolveVars(payload, vars));
                else out.add("skip " + resolveVars(payload, vars));
                pendingCondition = null;
            } else {
                if (pendingCondition == null || conditionPasses(pendingCondition, vars)) {
                    out.add("send " + resolveVars(text, vars));
                } else {
                    out.add("skip " + resolveVars(text, vars));
                }
                pendingCondition = null;
            }
        }
        if (out.isEmpty()) out.add("No executable actions");
        return out;
    }

    private static void runStep(@NonNull Context context,
                                @NonNull List<Step> steps,
                                int index,
                                @NonNull Map<String, String> vars,
                                @NonNull CommandSink sink) {
        if (index >= steps.size()) return;
        Step step = steps.get(index);
        String text = step.text;
        String lower = lower(text);
        long delay = 0L;

        if (lower.startsWith("delay:")) {
            delay = Math.max(0L, parseLong(text.substring(6), 0L));
        }

        Tools.MAIN_HANDLER.postDelayed(() -> {
            if (lower.startsWith("delay:")) {
                // consumed above
            } else if (lower.startsWith("var:")) {
                applyVar(text, vars);
            } else if (lower.startsWith("if:")) {
                // Marker retained from validation; actual conditional payload
                // is represented by the encoded next step below.
            } else if (text.startsWith("\0if\0")) {
                int end = text.indexOf('\0', 4);
                if (end > 4) {
                    String condition = text.substring(4, end);
                    String payload = text.substring(end + 1);
                    if (conditionPasses(condition, vars)) {
                        sink.send(resolveVars(payload, vars));
                    }
                }
            } else {
                sink.send(resolveVars(text, vars));
            }
            runStep(context, steps, index + 1, vars, sink);
        }, delay);
    }

    @NonNull
    private static Map<String, String> defaultVars(@NonNull Context context) {
        Map<String, String> vars = new HashMap<>();
        String player = "Steve";
        String uuid = "00000000-0000-0000-0000-000000000000";
        try {
            MinecraftAccount account = PojavProfile.getCurrentProfileContent(context, null);
            if (account != null) {
                if (account.username != null && !account.username.isEmpty()) player = account.username;
                if (account.profileId != null) uuid = account.profileId;
            }
        } catch (Throwable ignored) {}
        vars.put("player", player);
        vars.put("username", player);
        vars.put("uuid", uuid);
        vars.put("time", String.valueOf(System.currentTimeMillis() / 1000L));
        vars.put("x", "0");
        vars.put("y", "0");
        vars.put("z", "0");
        return vars;
    }

    private static void applyVar(@NonNull String directive, @NonNull Map<String, String> vars) {
        int eq = directive.indexOf('=');
        if (eq <= 4) return;
        String name = directive.substring(4, eq).trim();
        String value = directive.substring(eq + 1).trim();
        if (!name.isEmpty()) vars.put(name, resolveVars(value, vars));
    }

    @NonNull
    private static String resolveVars(@NonNull String input, @NonNull Map<String, String> vars) {
        String out = input;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            String token = "${" + entry.getKey() + "}";
            if (out.contains(token)) out = out.replace(token, entry.getValue());
        }
        return out;
    }

    private static boolean conditionPasses(@NonNull String raw, @NonNull Map<String, String> vars) {
        String expr = resolveVars(raw, vars).trim();
        int contains = expr.indexOf("~=");
        if (contains > 0) {
            return expr.substring(0, contains).contains(expr.substring(contains + 2));
        }
        int neq = expr.indexOf("!=");
        if (neq > 0) {
            return !expr.substring(0, neq).trim().equals(expr.substring(neq + 2).trim());
        }
        int eq = expr.indexOf("==");
        if (eq > 0) {
            return expr.substring(0, eq).trim().equals(expr.substring(eq + 2).trim());
        }
        return !expr.isEmpty() && !"0".equals(expr) && !"false".equals(lower(expr));
    }

    @NonNull
    private static String previousCondition(@NonNull List<Step> steps) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            String text = steps.get(i).text;
            if (lower(text).startsWith("if:")) return text.substring(3);
        }
        return "";
    }

    @NonNull
    public static List<String> getHistory(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(HISTORY_FILE, Context.MODE_PRIVATE);
        String raw = prefs.getString(HISTORY_KEY, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String item : raw.split("\u241E")) {
            if (!item.isEmpty()) out.add(item.replace("\\n", "\n"));
        }
        return out;
    }

    public static void recordHistory(@NonNull Context context, @NonNull String script) {
        String trimmed = script.trim();
        if (trimmed.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(HISTORY_FILE, Context.MODE_PRIVATE);
        List<String> history = getHistory(context);
        history.remove(trimmed);
        history.add(0, trimmed);
        while (history.size() > HISTORY_CAP) history.remove(history.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (String item : history) {
            if (sb.length() > 0) sb.append('\u241E');
            sb.append(item.replace("\n", "\\n"));
        }
        prefs.edit().putString(HISTORY_KEY, sb.toString()).apply();
    }

    public static void clearHistory(@NonNull Context context) {
        context.getSharedPreferences(HISTORY_FILE, Context.MODE_PRIVATE)
                .edit().remove(HISTORY_KEY).apply();
    }

    private static int parsePositiveInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static long parseLong(String s, long fallback) {
        try { return Long.parseLong(s.trim()); }
        catch (Exception e) { return fallback; }
    }

    @NonNull
    private static String lower(@NonNull String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
