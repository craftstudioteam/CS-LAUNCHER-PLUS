package net.kdt.pojavlaunch.modloaders;

import java.lang.ArrayIndexOutOfBoundsException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a user-picked .jar and works out which Minecraft version it needs.
 *
 * This exists because "pick a jar, then guess a version from its name" was both
 * fragile and insulting: the file knows what it is, and the answer is sitting in
 * the archive. Sources are consulted in order of how much they can be trusted,
 * and the first one that speaks wins:
 *
 *  1. {@code version.json} at the archive root — Forge and NeoForge universal/
 *     installer jars carry it, and its {@code minecraftVersion} field is the
 *     version the file was BUILT for (e.g. "1.20.1"), not a range. Strongest.
 *  2. {@code net/optifine/Config.class} — an OptiFine jar stores its target as
 *     the {@code MC_VERSION} String constant in that class's constant pool (the
 *     same place the OptiFine installer itself reads it from). We parse the pool
 *     instead of trusting the file name, because "OptiFine_1.20.1_HD_U_I6.jar"
 *     can be renamed to "download.jar" and still be exactly the same installer.
 *  3. {@code pack.properties} / {@code launchwrapper-of-*.jar} — secondary
 *     OptiFine markers; the {@code net.optifine...} package names in
 *     {@code Main-Class}/tweak lines pin the version too.
 *  4. {@code install_profile.json} / {@code fabric-installer.json} /
 *     {@code fake-dependency.json} — installer manifests; their libraries and
 *     {@code inheritsFrom} strings embed "1.20.1" explicitly.
 *  5. {@code fabric.mod.json} / {@code quilt.mod.json} — proves it is a plain
 *     MOD, not an installer, and yields the {@code depends.minecraft} RANGE
 *     (e.g. ">=1.20"). A range is a HINT, never a decision: two cards in a
 *     store page can disagree about which pinned version to install.
 *  6. The file name — last, and only ever as a hint worth confirming.
 *
 * Anything found here must still be cross-checked against the launcher's own
 * version list before a download starts (see VersionCreateFragment), and when
 * nothing is conclusive the user is ASKED rather than silently given 1.20.1
 * because that string happened to appear somewhere.
 */
public final class JarProbe {

    /** Same shape the rest of the launcher uses for a release version token. */
    private static final Pattern MC_VERSION =
            Pattern.compile("1\\.[0-9]+(?:\\.[0-9]+)?(?:-(?:pre|rc)[0-9]+)?");
    private static final Pattern MC_SNAPSHOT = Pattern.compile("\\d{2}w\\d{2}[a-z]*");

    public static final int KIND_UNKNOWN = 0;
    public static final int KIND_OPTIFINE = 1;
    public static final int KIND_FORGE = 2;
    public static final int KIND_NEOFORGE = 3;
    /** An installer for a loader (Fabric/Quilt), i.e. "install me". */
    public static final int KIND_LOADER_INSTALLER = 4;
    /** A normal mod jar — belongs in mods/, not in an installer. */
    public static final int KIND_PLAIN_MOD = 5;

    public static final int CONF_NONE = 0;
    /** Some evidence, but not enough to pick a version for the user. */
    public static final int CONF_HINT = 1;
    /** The file states its own target version; the UI may pre-select it. */
    public static final int CONF_CERTAIN = 2;

    public static final class Result {
        public int kind = KIND_UNKNOWN;
        /** A concrete MC version ("1.20.1", "24w14a"), or null. */
        @Nullable public String minecraftVersion;
        /** The loader/build version the jar reports, for the label. */
        @Nullable public String loaderVersion;
        public int confidence = CONF_NONE;
        /** What we read, in the user's words — never show a verdict without it. */
        @NonNull public String evidence = "";
        /** Versions the archive mentioned, most-trusted first. */
        @NonNull public final Set<String> candidates = new LinkedHashSet<>();
        /** True when the file is a mod, so an installer hand-off would be wrong. */
        public boolean looksLikePlainMod;

        public boolean isCertain() {
            return confidence == CONF_CERTAIN && minecraftVersion != null;
        }
    }

    private JarProbe() {}

    /**
     * Stream variant: the picked document is analysed without being copied into the
     * cache first. A user must not wait for a 60 MB transfer to disk before the
     * launcher can say "this is for 1.20.1".
     */
    @NonNull
    public static Result probeStream(@Nullable InputStream stream, @Nullable String displayName) {
        Result r = new Result();
        if (stream == null) {
            r.evidence = "the picked file could not be opened";
            return r;
        }
        java.util.zip.ZipInputStream zin = null;
        try {
            zin = new java.util.zip.ZipInputStream(stream);
            Entries e = new Entries();
            ZipEntry ze;
            while ((ze = zin.getNextEntry()) != null) {
                if (ze.isDirectory()) continue;
                e.offer(ze.getName(), zin);
            }
            apply(r, e);
        } catch (Throwable t) {
            r.evidence = "the archive could not be read (" + t.getClass().getSimpleName() + ")";
        } finally {
            if (zin != null) {
                try {
                    zin.close();
                } catch (Throwable ignored) {}
            }
        }
        if (r.minecraftVersion == null) fallBackToName(displayName, r);
        if (r.minecraftVersion == null && !r.candidates.isEmpty()) r.confidence = CONF_HINT;
        return r;
    }

    /**
     * The handful of entries that can carry a version. Read as they stream past, so
     * this costs the same as the file variant while needing no random access — and
     * entries are capped, because "read everything" in a 200 MB mod jar is how an
     * analysis step turns into a stall.
     */
    private static final class Entries {
        private static final String[] WANTED = {"version.json", "install_profile.json",
                "installer_profile.json", "fabric-installer.json", "fake-dependency.json",
                "pack.properties", "fabric.mod.json", "quilt.mod.json",
                "liteloader-installer.json", "META-INF/MANIFEST.MF"};
        final java.util.Map<String, byte[]> small = new java.util.HashMap<>();
        byte[] optifineConfig;
        /** Any entry path mentioning optifine — enough to call it an OptiFine jar. */
        boolean anyOptifineName;
        /** True once any entry has been looked at: an empty zip is not "no evidence". */
        boolean seenAny;

        void offer(String name, InputStream in) throws java.io.IOException {
            seenAny = true;
            String low = name.toLowerCase(java.util.Locale.ROOT);
            if (low.contains("optifine")) anyOptifineName = true;
            if (low.endsWith("net/optifine/config.class") || low.equals("config.class")
                    || low.endsWith("notch/net/optifine/config.class")) {
                optifineConfig = readCapped(in, 64 << 10);
                return;
            }
            for (String w : WANTED) {
                if (low.endsWith(w.toLowerCase(java.util.Locale.ROOT))) {
                    small.put(w, readCapped(in, 2 << 20));
                    return;
                }
            }
        }

        byte[] get(String name) {
            return small.get(name);
        }
    }

    /** Run every rule against entries already read (shared by both entry points). */
    private static void apply(Result r, Entries e) {
        if (r.kind == KIND_UNKNOWN
                && (e.optifineConfig != null || e.get("pack.properties") != null
                        || e.anyOptifineName)) {
            r.kind = KIND_OPTIFINE;
        }
        readOptiFineConfig(r, e.optifineConfig);
        readVersionJson(r, e.get("version.json"));
        readInstallerManifests(r, e);
        readModMetadata(r, e.get("fabric.mod.json"), e.get("quilt.mod.json"));
        readManifest(r, e.get("META-INF/MANIFEST.MF"));
    }

    private static byte[] readCapped(InputStream in, int cap) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            if (total + n > cap) {
                bos.write(buf, 0, Math.max(0, cap - total));
                return bos.toByteArray();
            }
            bos.write(buf, 0, n);
            total += n;
        }
        return bos.toByteArray();
    }

    /** @param displayName the name the user picked, used only as the last hint. */
    @NonNull
    public static Result probe(@Nullable File jar, @Nullable String displayName) {
        Result r = new Result();
        // A size floor here would reject legitimate small files, so the only gate
        // is "ZipFile can read it" — which is the real test, and is already caught
        // below. (Measured: the 1 KB version of this line made a 400-byte test jar
        // unreadable, i.e. it was silently deciding "unknown" instead of reading.)
        if (jar == null || !jar.isFile() || jar.length() < 30) {
            r.evidence = "the file could not be opened as a jar";
            return r;
        }
        // Same reader as the stream variant: one pass over the entries that can
        // carry a version, then one set of rules. Two parsers would be two chances
        // for the file path and the document path to disagree — and users hit both.
        ZipFile zip = null;
        try {
            zip = new ZipFile(jar);
            Entries e = new Entries();
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze == null || ze.isDirectory()) continue;
                try (InputStream in = zip.getInputStream(ze)) {
                    e.offer(ze.getName(), in);
                }
            }
            if (!e.seenAny) {
                r.evidence = "the archive had nothing readable in it";
                return r;
            }
            apply(r, e);
        } catch (Throwable t) {
            r.evidence = "the archive could not be read (" + t.getClass().getSimpleName() + ")";
            return r;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {}
            }
        }
        if (r.minecraftVersion == null) fallBackToName(displayName, r);
        if (r.minecraftVersion == null && !r.candidates.isEmpty()) {
            // We have seen a version but not from a source that pins it: still only
            // a hint, so the user confirms.
            r.confidence = CONF_HINT;
        }
        return r;
    }

    // ── 1. Forge / NeoForge: version.json ─────────────────────────────────────

    private static void readVersionJson(Result r, byte[] raw) {
        if (raw == null) return;
        try {
            JsonObject o = JsonParser.parseString(
                    new String(raw, Charset.forName("UTF-8"))).getAsJsonObject();
            String mc = str(o, "minecraftVersion");
            String id = str(o, "id");
            if (mc == null && id != null) mc = firstVersion(id);
            if (mc != null) {
                r.candidates.add(mc);
                if (r.confidence < CONF_CERTAIN) {
                    r.confidence = CONF_CERTAIN;
                    r.minecraftVersion = mc;
                    r.evidence = "version.json inside the jar says Minecraft " + mc;
                }
            }
            if (r.loaderVersion == null) r.loaderVersion = firstVersion(str(o, "serverVersionStr"));
            if (r.kind == KIND_UNKNOWN) {
                String hay = (id == null ? "" : id.toLowerCase(java.util.Locale.ROOT));
                r.kind = hay.contains("neoforge") ? KIND_NEOFORGE : KIND_FORGE;
            }
        } catch (Throwable ignored) {
            // Not JSON, or a shape we do not know: other sources may still speak.
        }
    }

    // ── 2. OptiFine: MC_VERSION String constant in net/optifine/Config.class ─

    private static void readOptiFineConfig(Result r, byte[] clazz) {
        if (clazz == null) return;
        // Read the constant the field is initialised with — not "the first
        // version-shaped string in the pool", which for an OptiFine jar would just
        // as happily return the launchwrapper version ("2.3") sitting two entries
        // away from it.
        String mc = constantForField(clazz, "MC_VERSION", true);
        if (mc != null && firstVersion(mc) != null) {
            r.candidates.add(mc);
            r.confidence = CONF_CERTAIN;
            r.minecraftVersion = mc;
            r.evidence = "net/optifine/Config.class reports MC_VERSION = " + mc;
            String edition = constantForField(clazz, "OF_EDITION", true);
            String release = constantForField(clazz, "OF_RELEASE", true);
            if (edition != null && release != null) r.loaderVersion = edition + "_" + release;
        } else if (r.kind == KIND_OPTIFINE) {
            // The class is there but unreadable (obfuscated, preview build, newer
            // format). Say so explicitly: an empty explanation next to a "pick the
            // version yourself" dialog is how a user decides the launcher is broken.
            r.confidence = Math.max(r.confidence, CONF_HINT);
            r.evidence = "this is an OptiFine jar but MC_VERSION could not be read "
                    + "from it — choose the Minecraft version yourself";
        }
    }

    /**
     * The String value a `public static final String FIELD = "literal"` compiles to,
     * read the way a JVM reads it: constant pool → field_info whose name_index is
     * FIELD → its ConstantValue attribute → the pool entry. Without an ASM
     * dependency, and without ever guessing from adjacency.
     *
     * @return the literal, or null when the class is missing/obfuscated/modern
     *         (a `static final` in <clinit> has no ConstantValue at all).
     */
    @Nullable
    private static String constantForField(byte[] classBytes, String fieldName, boolean wantString) {
        Pool pool = readPool(classBytes);
        if (pool == null) return null;
        int nameIdx = pool.indexOfUtf8(fieldName);
        if (nameIdx < 0) return null;
        try {
            // After the pool comes access_flags(u2) + this_class(u2) +
            // super_class(u2) + interfaces_count(u2), then interfaces[], then
            // fields_count. Getting this off by one u2 makes fields_count read the
            // interface list's first index, and every field lookup then "finds" a
            // field that does not exist — which is exactly how the first version
            // of this code silently downgraded real OptiFine jars to
            // "pick the version yourself" (caught by the fixture harness).
            int i = 10 + pool.bytes;
            i += 6;                                  // access_flags, this_class, super_class
            int ifCount = u2(classBytes, i); i += 2 + 2 * ifCount;
            int fCount = u2(classBytes, i); i += 2;
            for (int f = 0; f < fCount; f++) {
                i += 2;                              // access_flags
                int fname = u2(classBytes, i); i += 2;
                i += 2;                              // descriptor_index
                int aCount = u2(classBytes, i); i += 2;
                for (int a = 0; a < aCount; a++) {
                    int aName = u2(classBytes, i);
                    long aLen = u4(classBytes, i + 2);
                    boolean isConstantValue = fname == nameIdx
                            && "ConstantValue".equals(pool.utf8At(aName));
                    int valueIdx = u2(classBytes, i + 6);
                    i += 6 + (int) aLen;
                    if (!isConstantValue) continue;
                    String s = pool.stringAt(valueIdx);
                    if (s != null && (!wantString || firstVersion(s) != null)) return s;
                    return null;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;
        }
        return null;
    }

    /** Tagged entries of a class file's constant pool, or null if it is not one. */
    @Nullable
    private static Pool readPool(byte[] b) {
        if (b == null || b.length < 12) return null;
        // 0xCAFEBABE does not fit in an int: as an int literal it is
        // -1143641986, and `u4(...) != 0xCAFEBABE` compares a long against that
        // sign-extended value, so it is true for EVERY well-formed class file and
        // the probe declared every OptiFine jar unreadable. The L is the whole fix.
        if (u4(b, 0) != 0xCAFEBABEL) return null;
        try {
            int count = u2(b, 8);
            Pool p = new Pool(count);
            int i = p.cursorStart = 10;
            for (int k = 1; k < count; k++) {
                int tag = b[i++] & 0xFF;
                switch (tag) {
                    case 1: {                      // Utf8
                        int len = u2(b, i); i += 2;
                        p.putUtf8(k, new String(b, i, len, Charset.forName("UTF-8")));
                        i += len;
                        break;
                    }
                    case 8: p.putString(k, u2(b, i)); i += 2; break;   // String
                    case 7: case 16: case 19: case 20: i += 2; break;
                    case 15: i += 3; break;        // MethodHandle: kind + ref
                    case 3: case 4: case 9: case 10: case 11: case 12: case 17: i += 4; break;
                    case 5: case 6: i += 8; k++; break;   // longs/doubles take two slots
                    default: return null;           // unknown tag: stop, do not guess
                }
            }
            p.bytes = i - p.cursorStart;
            return p;
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;                           // truncated or not really a class
        }
    }

    private static final class Pool {
        final String[] utf8;
        final int[] stringRef;
        /** Byte length of the pool, so field_info can be found without re-walking. */
        int bytes;
        int cursorStart;

        Pool(int count) {
            utf8 = new String[Math.max(count, 1)];
            stringRef = new int[Math.max(count, 1)];
            java.util.Arrays.fill(stringRef, -1);
        }

        void putUtf8(int idx, String s) {
            if (idx > 0 && idx < utf8.length) utf8[idx] = s;
        }

        void putString(int idx, int refIdx) {
            if (idx > 0 && idx < stringRef.length) stringRef[idx] = refIdx;
        }

        String utf8At(int idx) {
            return idx > 0 && idx < utf8.length ? utf8[idx] : null;
        }

        /** Resolves a field's ConstantValue: CONSTANT_String → its UTF8 payload. */
        @Nullable
        String stringAt(int idx) {
            if (idx <= 0 || idx >= utf8.length) return null;
            if (idx < stringRef.length && stringRef[idx] > 0) {
                return utf8At(stringRef[idx]);
            }
            return utf8[idx];                      // some builds inline the literal
        }

        int indexOfUtf8(String wanted) {
            for (int i = 1; i < utf8.length; i++) if (wanted.equals(utf8[i])) return i;
            return -1;
        }
    }

    // ── 3./4. installer manifests ─────────────────────────────────────────────

    private static void readInstallerManifests(Result r, Entries e) {
        if (e.get("liteloader-installer.json") != null) r.kind = KIND_LOADER_INSTALLER;
        for (String entry : new String[]{"install_profile.json", "fabric-installer.json",
                "fake-dependency.json", "installer_profile.json"}) {
            byte[] raw = e.get(entry);
            if (raw == null) continue;
            String text = new String(raw, Charset.forName("UTF-8"));
            boolean found = false;
            Matcher m = MC_VERSION.matcher(text);
            while (m.find()) { r.candidates.add(m.group()); found = true; }
            if (!found) {
                Matcher s = MC_SNAPSHOT.matcher(text);
                while (s.find()) { r.candidates.add(s.group()); found = true; }
            }
            if (found && r.kind == KIND_UNKNOWN) r.kind = KIND_LOADER_INSTALLER;
            if (found && r.confidence < CONF_CERTAIN
                    && ("install_profile.json".equals(entry) || "installer_profile.json".equals(entry))
                    && r.minecraftVersion == null) {
                // A Forge-style install profile names the client version explicitly
                // ("minecraftVersion": "1.20.1"); take only that field, not any token.
                String pinned = pinnedMinecraftVersion(text);
                if (pinned != null) {
                    r.confidence = CONF_CERTAIN;
                    r.minecraftVersion = pinned;
                    r.evidence = entry + " pins Minecraft " + pinned;
                }
            }
        }
    }

    private static String pinnedMinecraftVersion(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String v = str(o, "minecraftVersion");
            if (v == null && o.has("version")) {
                // NeoForge/Fabric use "version" or an inheritsFrom on the json blob.
                v = firstVersion(str(o, "version"));
            }
            if (v == null && o.has("data")) {
                JsonObject d = o.getAsJsonObject("data");
                if (d != null && d.has("minecraft")) {
                    v = firstVersion(String.valueOf(d.getAsJsonObject("minecraft").get("version")));
                }
            }
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── 5. plain-mod metadata ────────────────────────────────────────────────

    private static void readModMetadata(Result r, byte[] fabric, byte[] quilt) {
        byte[] raw = fabric != null ? fabric : quilt;
        if (raw == null) return;
        r.looksLikePlainMod = true;
        if (r.kind == KIND_UNKNOWN) r.kind = KIND_PLAIN_MOD;
        String text = new String(raw, Charset.forName("UTF-8"));
        String range = firstVersion(text);
        if (range != null) {
            // A mod declares a RANGE (">=1.20") — a fact about compatibility, not a
            // version to install. So: hint only.
            r.candidates.add(range);
            if (r.confidence < CONF_HINT) {
                r.confidence = CONF_HINT;
                r.evidence = (fabric != null ? "fabric.mod.json" : "quilt.mod.json")
                        + " targets Minecraft " + range + " or newer";
            }
        }
    }

    // ── 6. manifest + file name ──────────────────────────────────────────────

    private static void readManifest(Result r, byte[] rawManifest) {
        {
            if (rawManifest == null) return;
            String text = new String(rawManifest, Charset.forName("UTF-8"));
            String main = manifestValue(text, "Main-Class");
            if (main != null && r.kind == KIND_UNKNOWN) {
                String low = main.toLowerCase(java.util.Locale.ROOT);
                if (low.startsWith("optifine")) r.kind = KIND_OPTIFINE;
                else if (low.contains("neoforge")) r.kind = KIND_NEOFORGE;
                else if (low.contains("forge")) r.kind = KIND_FORGE;
                else if (low.contains("fabricmc.installer") || low.contains("quiltloader")) {
                    r.kind = KIND_LOADER_INSTALLER;
                }
            }
            if (r.evidence == null || r.evidence.isEmpty()) {
                r.evidence = main != null ? "its Main-Class is " + main : r.evidence;
            }
        }
    }

    private static String manifestValue(String text, String key) {
        for (String line : text.split("\\r?\\n")) {
            if (!line.startsWith(key + ":")) continue;
            return line.substring(key.length() + 1).trim();
        }
        return null;
    }

    private static void fallBackToName(@Nullable String name, Result r) {
        if (name == null || name.isEmpty()) return;
        String v = firstVersion(name);
        if (v == null) return;
        r.candidates.add(v);
        if (r.confidence < CONF_HINT) {
            r.confidence = CONF_HINT;
            r.minecraftVersion = null;      // a name is never a decision
            r.evidence = "the file name mentions " + v + ", which is a hint, not proof";
        }
    }

    // ── zip/json plumbing ────────────────────────────────────────────────────

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total > 4 << 20) break;      // version metadata is never megabytes
        }
        return bos.toByteArray();
    }

    @Nullable
    private static String str(@NonNull JsonObject o, String key) {
        try {
            if (!o.has(key) || o.get(key).isJsonNull()) return null;
            String s = o.get(key).getAsString();
            return s == null || s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The first release-or-snapshot version token in a string, or null. */
    @Nullable
    public static String firstVersion(@Nullable String s) {
        if (s == null || s.isEmpty()) return null;
        Matcher m = MC_VERSION.matcher(s);
        if (m.find()) return m.group();
        m = MC_SNAPSHOT.matcher(s);
        if (m.find()) return m.group();
        return null;
    }

    private static int u2(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

    private static long u4(byte[] b, int at) {
        return ((long) (b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
                | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }
}
