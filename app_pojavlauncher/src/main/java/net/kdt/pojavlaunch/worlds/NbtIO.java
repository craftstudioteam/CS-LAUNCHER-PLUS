package net.kdt.pojavlaunch.worlds;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal, dependency-free Minecraft NBT codec (the codebase has no NBT
 * library). Reads and writes full gzipped NBT files such as level.dat so the
 * World Manager can display real world info and rename worlds safely.
 *
 * <p>Model mapping:
 * <ul>
 *   <li>TAG_Compound → {@code LinkedHashMap<String, Object>}</li>
 *   <li>TAG_List → {@link NbtList} (keeps its element type for round-trips)</li>
 *   <li>TAG_String → String, numbers → boxed primitives, arrays → java arrays</li>
 * </ul>
 */
public final class NbtIO {

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    private NbtIO() {}

    /** TAG_List with its element type so files can be written back losslessly. */
    public static final class NbtList {
        public final int elementType;
        public final List<Object> items = new ArrayList<>();
        public NbtList(int elementType) { this.elementType = elementType; }
        public int size() { return items.size(); }
        public Object get(int i) { return items.get(i); }
    }

    // ─────────────────────────── READ ───────────────────────────

    @NonNull
    public static Map<String, Object> readGzipped(@NonNull File file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new FileInputStream(file)))) {
            return readRoot(in);
        }
    }

    /** Some datapack/level files are written without gzip — fall back gracefully. */
    @NonNull
    public static Map<String, Object> readPossiblyGzipped(@NonNull File file) throws IOException {
        try {
            return readGzipped(file);
        } catch (IOException gzipFail) {
            try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
                return readRoot(in);
            }
        }
    }

    @NonNull
    private static Map<String, Object> readRoot(@NonNull DataInputStream in) throws IOException {
        int type = in.readByte() & 0xFF;
        if (type != TAG_COMPOUND) throw new IOException("Not an NBT compound (tag=" + type + ")");
        in.readUTF(); // root name (usually empty / "Data" container name)
        Map<String, Object> root = new LinkedHashMap<>();
        readCompoundPayload(in, root);
        return root;
    }

    private static Object readPayload(@NonNull DataInputStream in, int type) throws IOException {
        switch (type) {
            case TAG_BYTE: return in.readByte();
            case TAG_SHORT: return in.readShort();
            case TAG_INT: return in.readInt();
            case TAG_LONG: return in.readLong();
            case TAG_FLOAT: return in.readFloat();
            case TAG_DOUBLE: return in.readDouble();
            case TAG_BYTE_ARRAY: {
                int len = in.readInt();
                byte[] arr = new byte[len];
                in.readFully(arr);
                return arr;
            }
            case TAG_STRING: return in.readUTF();
            case TAG_LIST: {
                int elType = in.readByte() & 0xFF;
                int len = in.readInt();
                NbtList list = new NbtList(elType);
                for (int i = 0; i < len; i++) list.items.add(readPayload(in, elType));
                return list;
            }
            case TAG_COMPOUND: {
                Map<String, Object> map = new LinkedHashMap<>();
                readCompoundPayload(in, map);
                return map;
            }
            case TAG_INT_ARRAY: {
                int len = in.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = in.readInt();
                return arr;
            }
            case TAG_LONG_ARRAY: {
                int len = in.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = in.readLong();
                return arr;
            }
            default: throw new IOException("Unknown NBT tag " + type);
        }
    }

    private static void readCompoundPayload(@NonNull DataInputStream in,
                                            @NonNull Map<String, Object> map) throws IOException {
        while (true) {
            int type = in.readByte() & 0xFF;
            if (type == TAG_END) return;
            String name = in.readUTF();
            map.put(name, readPayload(in, type));
        }
    }

    // ─────────────────────────── WRITE ───────────────────────────

    public static void writeGzipped(@NonNull File file,
                                    @NonNull Map<String, Object> root) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new GZIPOutputStream(new FileOutputStream(tmp)))) {
            out.writeByte(TAG_COMPOUND);
            out.writeUTF("");
            writeCompoundPayload(out, root);
        }
        // Atomic-ish replace (backup the original first, never corrupt worlds)
        File bak = new File(file.getParentFile(), file.getName() + ".bak");
        //noinspection ResultOfMethodCallIgnored
        if (bak.exists()) bak.delete();
        if (file.exists() && !file.renameTo(bak)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Could not back up " + file.getName());
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            bak.renameTo(file);
            throw new IOException("Could not write " + file.getName());
        }
        //noinspection ResultOfMethodCallIgnored
        bak.delete();
    }

    private static int typeOf(@NonNull Object value) {
        if (value instanceof Byte) return TAG_BYTE;
        if (value instanceof Short) return TAG_SHORT;
        if (value instanceof Integer) return TAG_INT;
        if (value instanceof Long) return TAG_LONG;
        if (value instanceof Float) return TAG_FLOAT;
        if (value instanceof Double) return TAG_DOUBLE;
        if (value instanceof byte[]) return TAG_BYTE_ARRAY;
        if (value instanceof String) return TAG_STRING;
        if (value instanceof NbtList) return TAG_LIST;
        if (value instanceof Map) return TAG_COMPOUND;
        if (value instanceof int[]) return TAG_INT_ARRAY;
        if (value instanceof long[]) return TAG_LONG_ARRAY;
        throw new IllegalArgumentException("Unsupported NBT value: " + value.getClass());
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(@NonNull DataOutputStream out,
                                     @NonNull Object value) throws IOException {
        int type = typeOf(value);
        switch (type) {
            case TAG_BYTE: out.writeByte((Byte) value); break;
            case TAG_SHORT: out.writeShort((Short) value); break;
            case TAG_INT: out.writeInt((Integer) value); break;
            case TAG_LONG: out.writeLong((Long) value); break;
            case TAG_FLOAT: out.writeFloat((Float) value); break;
            case TAG_DOUBLE: out.writeDouble((Double) value); break;
            case TAG_BYTE_ARRAY: {
                byte[] arr = (byte[]) value;
                out.writeInt(arr.length);
                out.write(arr);
                break;
            }
            case TAG_STRING: out.writeUTF((String) value); break;
            case TAG_LIST: {
                NbtList list = (NbtList) value;
                out.writeByte(list.elementType);
                out.writeInt(list.items.size());
                for (Object item : list.items) writePayload(out, item);
                break;
            }
            case TAG_COMPOUND:
                writeCompoundPayload(out, (Map<String, Object>) value);
                break;
            case TAG_INT_ARRAY: {
                int[] arr = (int[]) value;
                out.writeInt(arr.length);
                for (int v : arr) out.writeInt(v);
                break;
            }
            case TAG_LONG_ARRAY: {
                long[] arr = (long[]) value;
                out.writeInt(arr.length);
                for (long v : arr) out.writeLong(v);
                break;
            }
            default: throw new IOException("Cannot write tag " + type);
        }
    }

    private static void writeCompoundPayload(@NonNull DataOutputStream out,
                                             @NonNull Map<String, Object> map) throws IOException {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object value = e.getValue();
            out.writeByte(typeOf(value));
            out.writeUTF(e.getKey());
            writePayload(out, value);
        }
        out.writeByte(TAG_END);
    }

    // ─────────────────────── level.dat helpers ───────────────────────

    /** Small immutable snapshot of the world fields the UI displays. */
    public static final class LevelInfo {
        public String levelName;
        public String versionName;   // e.g. "1.20.1" (may be null for old worlds)
        public long lastPlayedMs;    // epoch millis, 0 if unknown
        public long seed;            // Long.MIN_VALUE marker → unknown
        public boolean hardcore;
        public boolean hasSeed;
    }

    /** Read the interesting fields out of a world's level.dat. Never throws. */
    @NonNull
    public static LevelInfo readLevelInfo(@NonNull File worldDir) {
        LevelInfo info = new LevelInfo();
        info.lastPlayedMs = 0;
        info.hasSeed = false;
        File dat = new File(worldDir, "level.dat");
        if (!dat.exists()) dat = new File(worldDir, "level.dat_old");
        if (!dat.exists()) return info;
        try {
            Map<String, Object> root = readPossiblyGzipped(dat);
            Object data = root.get("Data");
            if (!(data instanceof Map)) return info;
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) data;

            Object name = d.get("LevelName");
            if (name instanceof String) info.levelName = (String) name;

            Object lastPlayed = d.get("LastPlayed");
            if (lastPlayed instanceof Number) info.lastPlayedMs = ((Number) lastPlayed).longValue();

            Object seed = d.get("RandomSeed");
            if (seed instanceof Number) { info.seed = ((Number) seed).longValue(); info.hasSeed = true; }

            Object hc = d.get("hardcore");
            info.hardcore = hc instanceof Byte && (Byte) hc != 0;

            Object ver = d.get("Version");
            if (ver instanceof Map) {
                Object vn = ((Map<?, ?>) ver).get("Name");
                if (vn instanceof String) info.versionName = (String) vn;
            }
        } catch (Exception ignored) {
            // Corrupt / non-standard level.dat: fields stay null, UI shows "—".
        }
        return info;
    }

    /**
     * Rename a world: rewrites LevelName inside level.dat (lossless round-trip,
     * original is kept as .bak during the swap).
     */
    public static void renameLevel(@NonNull File worldDir, @NonNull String newName) throws IOException {
        File dat = new File(worldDir, "level.dat");
        if (!dat.exists()) throw new IOException("level.dat missing");
        Map<String, Object> root = readPossiblyGzipped(dat);
        Object data = root.get("Data");
        if (!(data instanceof Map)) throw new IOException("No Data tag in level.dat");
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) data;
        d.put("LevelName", newName);
        writeGzipped(dat, root);
    }
}
