package net.kdt.pojavlaunch.modloaders.modpacks;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * Two questions the browse list and the detail page could not answer, both
 * answered here so they cannot disagree:
 *
 *  • "is a jar for THIS project already sitting in that mods folder, even though
 *    the index knows nothing about it?" — store titles and file names disagree in
 *    three predictable ways, and the rule below is tuned so that the disagreement
 *    never turns into a lie;
 *  • "is the file I am about to download already in that folder under another
 *    name?" — name+size equality, the cheap half of InstalledModCopy's check, safe
 *    to run before a download where hashing a 200 MB jar would not be.
 *
 * Listing a mods directory costs a stat per card, so the listing is memoed for a
 * few hundred ms — long enough to cover one bind of the visible cards, too short
 * to survive a screen change, and keyed on the directory's own mtime so a file
 * dropped between binds is still seen.
 */
public final class InstalledModFolder {

    private static final long TTL_MS = 400L;
    private static final String DISABLED = ".disabled";
    private static String sPath;
    private static long sStamp;
    private static long sAt;
    @Nullable private static String[] sNames;

    private InstalledModFolder() {}

    /** Lower-cased file names of a content directory (mods / shaderpacks / …). */
    @Nullable
    private static String[] listing(@Nullable File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        long stamp = dir.lastModified();
        long now = System.currentTimeMillis();
        String path;
        try {
            path = dir.getCanonicalPath();
        } catch (Throwable t) {
            path = dir.getAbsolutePath();
        }
        if (sNames != null && path.equals(sPath) && stamp == sStamp && now - sAt < TTL_MS) {
            return sNames;
        }
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        String[] out = new String[kids.length];
        int n = 0;
        for (File f : kids) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (name.endsWith(".part")) continue;
            out[n++] = name.toLowerCase(java.util.Locale.ROOT);
        }
        if (n < out.length) {
            String[] tight = new String[n];
            System.arraycopy(out, 0, tight, 0, n);
            out = tight;
        }
        java.util.Arrays.sort(out);
        sPath = path; sStamp = stamp; sAt = now; sNames = out;
        return out;
    }

    /**
     * A jar in {@code dir} that belongs to this project title.
     *
     * Candidate names, and the rule each is allowed to use:
     *  • the whole title squashed to letters and digits ("Sodium 1.21.5" → sodium1215,
     *    "Just Enough Items (JEI)" → justenoughitemsjei) — a strong candidate, so it
     *    may be followed by digits ("sodium058") but never by more letters;
     *  • the same minus its trailing version ("sodium") — strong, same rule;
     *  • the title without its trailing parenthetical ("justenoughitems") — strong;
     *  • the parenthetical itself ("jei") — WEAK, an alias, so it must own the exact
     *    file name. This is what keeps "Fabric Loader" from lighting up on
     *    fabric-api.jar: its leading word would otherwise be a candidate, and a wrong
     *    badge is worse than none, because the card stops meaning "you can install
     *    this".
     */
    public static boolean hasJarMatchingTitle(@Nullable File dir, @Nullable String title) {
        String[] names = listing(dir);
        String full = slug(title);
        if (names == null || names.length == 0 || full.length() < 3) return false;
        boolean[] weakArr = new boolean[4];
        String[] cands = candidates(full, title, weakArr);
        for (String name : names) {
            if (name == null) continue;
            String base = baseKey(name);
            if (base.length() == 0) continue;
            for (int i = 0; i < cands.length; i++) {
                String p = cands[i];
                if (!base.startsWith(p)) continue;
                if (base.length() == p.length()) return true;
                // Carried out of candidates() instead of re-derived from the
                // strings: "starts with the alias" and "is a prefix of the title"
                // are different claims, and guessing between them from lengths is
                // how a heuristic like this starts lying.
                if (weakArr[i]) continue;
                // A strong candidate may be followed by a version number, nothing else.
                if (Character.isDigit(base.charAt(p.length()))) return true;
            }
        }
        return false;
    }

    /** "sodium-0.5.8.jar", "...jar.disabled" -> "sodium058". */
    private static String baseKey(String fileName) {
        String base = fileName;
        if (base.endsWith(DISABLED)) base = base.substring(0, base.length() - DISABLED.length());
        int cut = base.lastIndexOf('.');           // NOT indexOf: the version has dots
        if (cut > 0) base = base.substring(0, cut);
        return slug(base);
    }

    /** Letters and digits only, lower case — the alphabet both sides agree on. */
    private static String slug(@Nullable String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = Character.toLowerCase(s.charAt(i));
            if (Character.isLetterOrDigit(ch)) sb.append(ch);
        }
        return sb.toString();
    }

    /** @param weakOut one-element array; weakOut[0] = true when a candidate is an alias. */
    private static String[] candidates(String full, @Nullable String title, boolean[] weakOut) {
        String[] tmp = new String[4];
        boolean[] weak = new boolean[4];
        int n = 0;
        tmp[n++] = full;
        int lastNonDigit = full.length();
        while (lastNonDigit > 0 && !Character.isDigit(full.charAt(lastNonDigit - 1))) lastNonDigit--;
        if (lastNonDigit > 0) {                     // trailing version: cut it off
            int head = lastNonDigit;
            while (head > 1 && Character.isDigit(full.charAt(head - 1))) head--;
            // One backward pass, not two: a second one would jump over the letters
            // of the name and cut "justenoughitems1201" down to "just".
            if (head >= 3 && head < full.length()) tmp[n++] = full.substring(0, head);
        }
        String t = title == null ? "" : title.trim();
        if (t.endsWith(")")) {
            int open = t.lastIndexOf('(');
            if (open > 0) {
                String alias = slug(t.substring(open + 1, t.length() - 1));
                if (alias.length() >= 3) { tmp[n] = alias; weak[n] = true; n++; }
                String longName = slug(t.substring(0, open));
                if (longName.length() >= 4) { tmp[n] = longName; weak[n] = false; n++; }
            }
        }
        String[] out = new String[n];
        boolean[] w = new boolean[n];
        System.arraycopy(tmp, 0, out, 0, n);
        System.arraycopy(weak, 0, w, 0, n);
        for (int i = 0; i < n; i++) weakOut[i] = w[i];
        return out;
    }

    /**
     * A file with the same name and size as {@code src} inside {@code dir}.
     * Deliberately not a hash: this runs before a download, on a path the user is
     * waiting on, and an identical size under an identical name is already strong
     * enough to say "we are not fetching that twice".
     */
    @Nullable
    public static File exactDuplicate(@Nullable File src, @Nullable File dir) {
        if (src == null || !src.isFile() || dir == null || !dir.isDirectory()) return null;
        long len = src.length();
        if (len <= 0) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        String wanted = src.getName();
        for (File f : kids) {
            if (f == null || f.equals(src) || !f.isFile()) continue;
            String name = f.getName();
            if (name.endsWith(".part")) continue;
            String base = name.endsWith(DISABLED)
                    ? name.substring(0, name.length() - DISABLED.length()) : name;
            if (base.equals(wanted) && f.length() == len) return f;
        }
        return null;
    }

    /** Drop the memo (after an install/uninstall, so the next bind is honest). */
    public static void invalidate() {
        sNames = null;
        sPath = null;
        sStamp = 0L;
        sAt = 0L;
    }
}
