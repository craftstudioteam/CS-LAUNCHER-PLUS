package net.kdt.pojavlaunch.skins;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** Exactly three local skin presets for one Minecraft account. */
public final class SkinSlotStore {
    public static final int SLOT_COUNT = 3;
    /** Default profileId every fresh LOCAL account carries (see MinecraftAccount). */
    private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";

    public static final class Slot {
        public final int index;
        public String name;
        public String model;
        public long updatedAt;
        Slot(int index) { this.index = index; this.name = "Skin Slot " + (index + 1); this.model = "classic"; }
    }

    private final MinecraftAccount account;
    private final File root;
    private final File metadataFile;
    private final Slot[] slots = new Slot[SLOT_COUNT];
    private int activeSlot = -1;

    public SkinSlotStore(@NonNull Context context, @NonNull MinecraftAccount account) {
        this.account = account;
        /*
         * Library key must be unique per ACCOUNT — and stable forever.
         *
         * Bug history: the key used to be "profileId when present". Local
         * (offline) accounts NEVER receive a profileId; they keep the default
         * ZERO_UUID. Every local account therefore opened the SAME library
         * root (skin_library/00000000-…): account B's saves overwrote what
         * account A saw as "my slots", which is exactly why a skin looked
         * "deleted from an earlier slot" after another account used skins.
         *
         * Rule now: only a real, non-zero profileId (Microsoft / Ely.by) may
         * key the library; everyone else keys by username — the same identity
         * the equipped-skin pipeline uses (skins/<username>_skin.png).
         */
        String rawId = account.profileId == null ? null : account.profileId.trim();
        boolean usableId = rawId != null && !rawId.isEmpty() && !ZERO_UUID.equals(rawId);
        String accountKey;
        if (usableId) {
            accountKey = rawId;
        } else if (account.username != null && !account.username.trim().isEmpty()) {
            accountKey = "user_" + account.username.trim();
        } else {
            accountKey = "default";
        }
        accountKey = accountKey.replaceAll("[^a-zA-Z0-9._-]", "_");
        root = new File(Tools.DIR_DATA, "skin_library/" + accountKey);
        metadataFile = new File(root, "slots.json");
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = new Slot(i);
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        load();
        migrateLegacySharedLibrary(usableId);
        migrateCurrentSkin();
    }

    /**
     * One-time rescue for skins saved by LOCAL accounts before the key fix:
     * everything that landed in the shared ZERO_UUID root is copied into this
     * account's own username-keyed root (only when the new root is still
     * empty). The legacy root is left untouched, so other accounts migrating
     * later still find their data. Nothing is deleted — no skin can be lost.
     */
    private void migrateLegacySharedLibrary(boolean usableId) {
        if (usableId) return;                               // paid accounts were always keyed correctly
        try {
            if (metadataFile.isFile()) return;              // own library exists already
            File legacy = new File(Tools.DIR_DATA, "skin_library/" + ZERO_UUID);
            if (!legacy.isDirectory() || legacy.equals(root)) return;
            File legacyMeta = new File(legacy, "slots.json");
            if (!legacyMeta.isFile()) return;               // nothing was ever saved there
            FileUtils.copyFile(legacyMeta, metadataFile);
            for (int i = 1; i <= SLOT_COUNT; i++) {
                File src = new File(legacy, "slot_" + i + ".png");
                if (src.isFile()) FileUtils.copyFile(src, fileFor(i - 1));
            }
            load();                                         // adopt migrated metadata
        } catch (Throwable ignored) { }
    }

    public Slot get(int index) { return slots[index]; }
    public int getActiveSlot() { return activeSlot; }
    public boolean isFilled(int index) { File f = fileFor(index); return f.isFile() && f.length() > 0; }
    public File fileFor(int index) { return new File(root, "slot_" + (index + 1) + ".png"); }

    public void saveSlot(int index, @NonNull File source, @Nullable String displayName, boolean slim) throws Exception {
        if (index < 0 || index >= SLOT_COUNT || !source.isFile()) throw new IllegalArgumentException("Invalid skin slot");
        File temp = new File(root, "slot_" + (index + 1) + ".tmp");
        FileUtils.copyFile(source, temp);
        File target = fileFor(index);
        if (target.exists() && !target.delete()) throw new Exception("Could not replace slot");
        if (!temp.renameTo(target)) { FileUtils.copyFile(temp, target); temp.delete(); }
        Slot slot = slots[index];
        slot.name = displayName == null || displayName.trim().isEmpty()
                ? "Skin Slot " + (index + 1) : displayName.trim();
        slot.model = slim ? "slim" : "classic";
        slot.updatedAt = System.currentTimeMillis();
        save();
    }

    public void activate(int index) throws Exception {
        if (!isFilled(index)) throw new IllegalArgumentException("Skin slot is empty");

        // Activation is a non-destructive operation. Preserve all three library
        // files before touching the compatibility skin so vendor/filesystem
        // quirks can never make the previously active slot disappear.
        File[] backups = new File[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            File slot = fileFor(i);
            if (!slot.isFile()) continue;
            backups[i] = new File(root, ".activate_keep_" + (i + 1));
            FileUtils.copyFile(slot, backups[i]);
        }

        try {
            File skinsDir = new File(Tools.DIR_DATA, "skins");
            //noinspection ResultOfMethodCallIgnored
            skinsDir.mkdirs();
            File active = new File(skinsDir, account.username + "_skin.png");
            File temp = new File(skinsDir, account.username + "_skin.tmp");
            FileUtils.copyFile(fileFor(index), temp);
            // copyFile overwrites the compatibility target without ever moving
            // or deleting the source slot file.
            FileUtils.copyFile(temp, active);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            Tools.write(new File(skinsDir, account.username + "_metadata.json").getAbsolutePath(),
                    "{\n  \"model\": \"" + slots[index].model + "\",\n  \"slot\": " + (index + 1) + "\n}");
            activeSlot = index;
            save();
            account.clearFaceCache();
            // Phase 10: the active sheet is now the user's slot, not Mojang's copy.
            SkinResolver.forgetPremiumMark(account.username);
            net.kdt.pojavlaunch.ui.SkinHead3DRenderer.invalidate(account.username);
        } finally {
            for (int i = 0; i < SLOT_COUNT; i++) {
                File backup = backups[i];
                if (backup == null || !backup.isFile()) continue;
                File slot = fileFor(i);
                if (!slot.isFile() || !FileUtils.contentEquals(slot, backup)) {
                    FileUtils.copyFile(backup, slot);
                }
                //noinspection ResultOfMethodCallIgnored
                backup.delete();
            }
        }
    }

    public void deleteSlot(int index) {
        if (index < 0 || index >= SLOT_COUNT) return;
        //noinspection ResultOfMethodCallIgnored
        fileFor(index).delete();
        slots[index] = new Slot(index);
        if (activeSlot == index) {
            activeSlot = -1;
            File skinsDir = new File(Tools.DIR_DATA, "skins");
            //noinspection ResultOfMethodCallIgnored
            new File(skinsDir, account.username + "_skin.png").delete();
            //noinspection ResultOfMethodCallIgnored
            new File(skinsDir, account.username + "_metadata.json").delete();
            account.clearFaceCache();
        }
        save();
    }

    public void clearAll() {
        for (int i = 0; i < SLOT_COUNT; i++) deleteSlot(i);
    }

    private void migrateCurrentSkin() {
        if (metadataFile.exists()) return;
        File current = new File(Tools.DIR_DATA + "/skins/" + account.username + "_skin.png");
        if (current.isFile()) {
            try {
                boolean slim = false;
                File meta = new File(Tools.DIR_DATA + "/skins/" + account.username + "_metadata.json");
                if (meta.isFile()) slim = Tools.read(meta.getAbsolutePath()).toLowerCase().contains("slim");
                saveSlot(0, current, "Current Skin", slim);
                activeSlot = 0;
                save();
            } catch (Exception ignored) {}
        } else save();
    }

    private void load() {
        if (!metadataFile.isFile()) return;
        try {
            JSONObject rootJson = new JSONObject(Tools.read(metadataFile.getAbsolutePath()));
            activeSlot = rootJson.optInt("activeSlot", -1);
            JSONArray array = rootJson.optJSONArray("slots");
            if (array != null) {
                for (int i = 0; i < Math.min(array.length(), SLOT_COUNT); i++) {
                    JSONObject o = array.optJSONObject(i); if (o == null) continue;
                    slots[i].name = o.optString("name", "Skin Slot " + (i + 1));
                    slots[i].model = o.optString("model", "classic");
                    slots[i].updatedAt = o.optLong("updatedAt", 0L);
                }
            }
        } catch (Exception ignored) { activeSlot = -1; }
    }

    private void save() {
        try {
            JSONObject o = new JSONObject(); o.put("version", 1); o.put("activeSlot", activeSlot);
            JSONArray array = new JSONArray();
            for (Slot slot : slots) {
                JSONObject s = new JSONObject(); s.put("index", slot.index); s.put("name", slot.name);
                s.put("model", slot.model); s.put("updatedAt", slot.updatedAt); s.put("filled", isFilled(slot.index));
                array.put(s);
            }
            o.put("slots", array);
            File temp = new File(root, "slots.tmp"); Tools.write(temp.getAbsolutePath(), o.toString(2));
            if (metadataFile.exists()) metadataFile.delete();
            if (!temp.renameTo(metadataFile)) { FileUtils.copyFile(temp, metadataFile); temp.delete(); }
        } catch (Exception ignored) {}
    }
}
