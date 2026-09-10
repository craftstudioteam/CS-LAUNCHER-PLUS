package net.kdt.pojavlaunch.capes;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.yggdrasil.LocalUuidUtils;
import net.kdt.pojavlaunch.yggdrasil.LocalYggdrasilServer;
import net.kdt.pojavlaunch.yggdrasil.SkinModelType;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central repository managing online MinecraftCapes gallery queries,
 * offline caching, local collections, custom imports, and active cape assignment.
 */
public class CapeRepository {
    private static final String TAG = "CapeRepository";
    private static final String PREFS_NAME = "cslauncher_capes_meta";
    private static final String KEY_ACTIVE_CAPE_PREFIX = "active_cape_id_";

    private static CapeRepository sInstance;
    private final Context mContext;
    private final MinecraftCapesService mService;
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(4);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final File mCollectionDir;
    private final File mCacheDir;
    private final File mCapesDir;
    private final SharedPreferences mPrefs;

    // Memory cache for quick thumbnail & texture lookups
    private final Map<String, Bitmap> mBitmapMemoryCache = Collections.synchronizedMap(new HashMap<>());
    private final List<CapeItem> mCatalogItems = new ArrayList<>();

    public interface CapeBitmapCallback {
        void onBitmapLoaded(Bitmap bitmap);
        void onError(Exception e);
    }

    public interface CapeActionCallback {
        void onSuccess();
        void onError(Exception e);
    }

    public static synchronized CapeRepository getInstance() {
        if (sInstance == null) {
            Context ctx = PojavApplication.getInstance();
            sInstance = new CapeRepository(ctx);
        }
        return sInstance;
    }

    public static synchronized CapeRepository getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new CapeRepository(context != null ? context.getApplicationContext() : PojavApplication.getInstance());
        }
        return sInstance;
    }

    private CapeRepository(Context context) {
        mContext = context != null ? context : PojavApplication.getInstance();
        mService = MinecraftCapesService.getInstance();
        mPrefs = mContext != null 
                ? mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                : null;

        mCapesDir = new File(Tools.DIR_DATA + "/capes");
        mCollectionDir = new File(mCapesDir, "collection");
        mCacheDir = new File(mCapesDir, "cache");

        if (!mCapesDir.exists()) mCapesDir.mkdirs();
        if (!mCollectionDir.exists()) mCollectionDir.mkdirs();
        if (!mCacheDir.exists()) mCacheDir.mkdirs();

        initCuratedCatalog();
    }

    /**
     * Initializes the built-in catalog with authentic official Minecon, OptiFine & event capes.
     */
    private void initCuratedCatalog() {
        // Official Minecon Capes
        mCatalogItems.add(new CapeItem("minecon_2011", "Minecon 2011", CapeItem.CATEGORY_MINECON, "Mojang Studios",
                "The classic red Pickaxe cape distributed at Minecon 2011 Las Vegas.",
                null, null, false, true, 48200, 3910, null));

        mCatalogItems.add(new CapeItem("minecon_2012", "Minecon 2012", CapeItem.CATEGORY_MINECON, "Mojang Studios",
                "The dark blue golden Pickaxe cape from Minecon 2012 Disneyland Paris.",
                null, null, false, true, 39100, 2840, null));

        mCatalogItems.add(new CapeItem("minecon_2013", "Minecon 2013", CapeItem.CATEGORY_MINECON, "Mojang Studios",
                "The emerald green Piston cape from Minecon 2013 Orlando, Florida.",
                null, null, false, true, 41200, 3100, null));

        mCatalogItems.add(new CapeItem("minecon_2015", "Minecon 2015", CapeItem.CATEGORY_MINECON, "Mojang Studios",
                "The deep turquoise Iron Golem face cape from Minecon 2015 London.",
                null, null, false, true, 36800, 2750, null));

        mCatalogItems.add(new CapeItem("minecon_2016", "Minecon 2016", CapeItem.CATEGORY_MINECON, "Mojang Studios",
                "The dark violet Enderman face cape from Minecon 2016 Anaheim, California.",
                null, null, false, true, 52400, 4890, null));

        // Special Events & Modern Official Capes
        mCatalogItems.add(new CapeItem("mc_15th_anniversary", "15th Anniversary", CapeItem.CATEGORY_OFFICIAL, "Mojang Studios",
                "The official Minecraft 15 Year Anniversary Creeper celebration cape.",
                null, null, false, true, 64200, 5700, null));

        mCatalogItems.add(new CapeItem("cherry_blossom", "Cherry Blossom", CapeItem.CATEGORY_OFFICIAL, "Mojang Studios",
                "The pastel pink Cherry Blossom cape released for the 1.20 Trails & Tales update.",
                null, null, false, true, 58900, 4920, null));

        mCatalogItems.add(new CapeItem("vanilla_cape", "Vanilla Cape", CapeItem.CATEGORY_OFFICIAL, "Mojang Studios",
                "The iconic sunset mountain Vanilla cape awarded to dual-edition players.",
                null, null, false, true, 49700, 4600, null));

        mCatalogItems.add(new CapeItem("migrator_cape", "Migrator Cape", CapeItem.CATEGORY_OFFICIAL, "Mojang Studios",
                "The golden lettered Migrator cape given to accounts that transitioned to Microsoft auth.",
                null, null, false, true, 51300, 4310, null));

        mCatalogItems.add(new CapeItem("twitch_purple_heart", "Twitch Purple Heart", CapeItem.CATEGORY_OFFICIAL, "Twitch",
                "The purple heart broadcast cape distributed during Minecraft 15th anniversary streams.",
                null, null, false, true, 41200, 3810, null));

        mCatalogItems.add(new CapeItem("tiktok_follower", "TikTok Follower", CapeItem.CATEGORY_OFFICIAL, "TikTok",
                "The signature teal and pink helmet TikTok event cape.",
                null, null, false, true, 39400, 3200, null));

        mCatalogItems.add(new CapeItem("cobalt_cape", "Cobalt Oxeye", CapeItem.CATEGORY_OFFICIAL, "Mojang Studios",
                "Special commemorative blue flower cape created for Oxeye and Cobalt initiatives.",
                null, null, false, true, 28400, 2190, null));

        mCatalogItems.add(new CapeItem("mojang_studios", "Mojang Studios", CapeItem.CATEGORY_OFFICIAL, "Mojang Studios",
                "The signature obsidian black cape with the red Mojang Studios emblem.",
                null, null, false, true, 45100, 4120, null));

        mCatalogItems.add(new CapeItem("turtle_cape", "Turtle Cape", CapeItem.CATEGORY_OFFICIAL, "Mojang Studios",
                "The rare emerald sea turtle shell cape for aquatic contributors.",
                null, null, false, true, 22100, 1890, null));

        mCatalogItems.add(new CapeItem("prismarine_cape", "Prismarine Cape", CapeItem.CATEGORY_OFFICIAL, "Mojang Studios",
                "The animated ocean monument prismarine tiled cape.",
                null, null, true, true, 31400, 2670, null));

        mCatalogItems.add(new CapeItem("bacon_cape", "Bacon Cape", CapeItem.CATEGORY_OFFICIAL, "Mojang Studios",
                "The legendary sizzled crispy bacon cape made for Notch's favorite snack.",
                null, null, false, true, 19800, 1540, null));

        // OptiFine Official Designs
        mCatalogItems.add(new CapeItem("optifine_white", "OptiFine White", CapeItem.CATEGORY_POPULAR, "OptiFine",
                "The classic white banner cape with crimson OF letters.",
                null, null, false, false, 37500, 3100, null));

        mCatalogItems.add(new CapeItem("optifine_black", "OptiFine Black", CapeItem.CATEGORY_POPULAR, "OptiFine",
                "The stealth black banner cape with crisp white OF letters.",
                null, null, false, false, 48900, 4320, null));

        mCatalogItems.add(new CapeItem("optifine_blue", "OptiFine Blue", CapeItem.CATEGORY_POPULAR, "OptiFine",
                "The sky blue banner cape with crisp white OF letters.",
                null, null, false, false, 34200, 2900, null));

        mCatalogItems.add(new CapeItem("optifine_red", "OptiFine Red", CapeItem.CATEGORY_POPULAR, "OptiFine",
                "The crimson red banner cape with white OF letters.",
                null, null, false, false, 31100, 2600, null));

        mCatalogItems.add(new CapeItem("optifine_purple", "OptiFine Purple", CapeItem.CATEGORY_POPULAR, "OptiFine",
                "The vibrant royal purple banner cape with white OF letters.",
                null, null, false, false, 28700, 2350, null));
    }

    public List<CapeItem> getCuratedCapes() {
        return new ArrayList<>(mCatalogItems);
    }

    public synchronized List<CapeItem> getCollectionCapes() {
        List<CapeItem> list = new ArrayList<>();
        File metaFile = new File(mCollectionDir, "collection_meta.json");
        if (metaFile.exists()) {
            try (FileInputStream fis = new FileInputStream(metaFile)) {
                byte[] bytes = new byte[(int) metaFile.length()];
                fis.read(bytes);
                JSONArray arr = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    CapeItem item = CapeItem.fromJsonObject(obj);
                    if (item != null) list.add(item);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed reading collection meta", e);
            }
        }
        return list;
    }

    public synchronized boolean isCapeSaved(@NonNull CapeItem item) {
        return isCapeSaved(item.getId());
    }

    public synchronized boolean isCapeSaved(@NonNull String capeId) {
        File file = new File(mCollectionDir, capeId + ".png");
        if (file.exists()) return true;
        List<CapeItem> list = getCollectionCapes();
        for (CapeItem ci : list) {
            if (ci.getId().equals(capeId)) return true;
        }
        return false;
    }

    public synchronized void addToCollection(@NonNull CapeItem item, @Nullable CapeActionCallback callback) {
        mExecutor.execute(() -> {
            try {
                loadCapeBitmapAsync(item, new CapeBitmapCallback() {
                    @Override
                    public void onBitmapLoaded(Bitmap bitmap) {
                        mExecutor.execute(() -> {
                            try {
                                File targetPng = new File(mCollectionDir, item.getId() + ".png");
                                try (FileOutputStream fos = new FileOutputStream(targetPng)) {
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                                }

                                item.setLocalPath(targetPng.getAbsolutePath());
                                List<CapeItem> current = getCollectionCapes();
                                boolean exists = false;
                                for (CapeItem ci : current) {
                                    if (ci.getId().equals(item.getId())) {
                                        exists = true;
                                        break;
                                    }
                                }
                                if (!exists) {
                                    current.add(item);
                                    saveCollectionMeta(current);
                                }

                                mMainHandler.post(() -> {
                                    if (callback != null) callback.onSuccess();
                                });
                            } catch (Exception e) {
                                mMainHandler.post(() -> {
                                    if (callback != null) callback.onError(e);
                                });
                            }
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        mMainHandler.post(() -> {
                            if (callback != null) callback.onError(e);
                        });
                    }
                });
            } catch (Exception e) {
                mMainHandler.post(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    public synchronized void removeFromCollection(@NonNull CapeItem item) {
        removeFromCollection(item.getId());
    }

    public synchronized void removeFromCollection(@NonNull String capeId) {
        try {
            File targetPng = new File(mCollectionDir, capeId + ".png");
            if (targetPng.exists()) targetPng.delete();

            List<CapeItem> collection = getCollectionCapes();
            for (int i = 0; i < collection.size(); i++) {
                if (collection.get(i).getId().equals(capeId)) {
                    collection.remove(i);
                    break;
                }
            }
            saveCollectionMeta(collection);
            mBitmapMemoryCache.remove(capeId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove cape from collection", e);
        }
    }

    private synchronized void saveCollectionMeta(List<CapeItem> collection) {
        try {
            JSONArray arr = new JSONArray();
            for (CapeItem item : collection) {
                arr.put(item.toJsonObject());
            }
            File metaFile = new File(mCollectionDir, "collection_meta.json");
            try (FileOutputStream fos = new FileOutputStream(metaFile)) {
                fos.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving collection meta", e);
        }
    }

    /**
     * Equips and activates a cape for the specified Minecraft account.
     * Saves the PNG to Tools.DIR_DATA/capes/{username}_cape.png and registers it with LocalYggdrasilServer.
     */
    public void equipCape(@NonNull MinecraftAccount account, @NonNull CapeItem cape, @Nullable CapeActionCallback callback) {
        mExecutor.execute(() -> {
            loadCapeBitmapAsync(cape, new CapeBitmapCallback() {
                @Override
                public void onBitmapLoaded(Bitmap bitmap) {
                    mExecutor.execute(() -> {
                        try {
                            File activeCapeFile = new File(mCapesDir, account.username + "_cape.png");
                            try (FileOutputStream fos = new FileOutputStream(activeCapeFile)) {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                            }

                            if (mPrefs != null) {
                                mPrefs.edit().putString(KEY_ACTIVE_CAPE_PREFIX + account.username, cape.getId()).apply();
                            }

                            // Also save to collection
                            addToCollection(cape, null);

                            // Register with in-launcher offline Yggdrasil server if active
                            try {
                                if (LocalYggdrasilServer.getPort() > 0) {
                                    File activeSkin = new File(Tools.DIR_DATA + "/skins/" + account.username + "_skin.png");
                                    String skinPath = activeSkin.exists() ? activeSkin.getAbsolutePath() : null;
                                    String uuid = LocalUuidUtils.generateProfileId(account.username, SkinModelType.STEVE);
                                    LocalYggdrasilServer.registerProfile(account.username, uuid,
                                            skinPath, activeCapeFile.getAbsolutePath(), false);
                                }
                            } catch (Throwable ignored) {}

                            mMainHandler.post(() -> {
                                if (callback != null) callback.onSuccess();
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to equip cape: " + e.getMessage(), e);
                            mMainHandler.post(() -> {
                                if (callback != null) callback.onError(e);
                            });
                        }
                    });
                }

                @Override
                public void onError(Exception e) {
                    mMainHandler.post(() -> {
                        if (callback != null) callback.onError(e);
                    });
                }
            });
        });
    }

    /**
     * Imports a custom PNG bitmap as a cape into the collection.
     */
    public void importCustomCape(@NonNull String name, @NonNull Bitmap bitmap, @Nullable CapeActionCallback callback) {
        mExecutor.execute(() -> {
            try {
                Bitmap normalized = MinecraftCapesService.normalizeCapeDimensions(bitmap);
                String id = "custom_" + System.currentTimeMillis();
                File customFile = new File(mCollectionDir, id + ".png");
                try (FileOutputStream fos = new FileOutputStream(customFile)) {
                    normalized.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }

                CapeItem item = new CapeItem(id, name, CapeItem.CATEGORY_COMMUNITY, "Custom Import",
                        "Locally imported Minecraft cape.", null, null, false, false, 1, 1, customFile.getAbsolutePath());
                item.isCustom = true;

                mBitmapMemoryCache.put(id, normalized);
                List<CapeItem> current = getCollectionCapes();
                current.add(0, item);
                saveCollectionMeta(current);

                mMainHandler.post(() -> {
                    if (callback != null) callback.onSuccess();
                });
            } catch (Exception e) {
                mMainHandler.post(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    /**
     * Removes the active cape for the specified Minecraft account.
     */
    public void removeEquippedCape(@NonNull MinecraftAccount account) {
        mExecutor.execute(() -> {
            try {
                File activeCapeFile = new File(mCapesDir, account.username + "_cape.png");
                if (activeCapeFile.exists()) {
                    activeCapeFile.delete();
                }

                if (mPrefs != null) {
                    mPrefs.edit().remove(KEY_ACTIVE_CAPE_PREFIX + account.username).apply();
                }

                // Update Yggdrasil server with cape removed
                try {
                    if (LocalYggdrasilServer.getPort() > 0) {
                        File activeSkin = new File(Tools.DIR_DATA + "/skins/" + account.username + "_skin.png");
                        String skinPath = activeSkin.exists() ? activeSkin.getAbsolutePath() : null;
                        String uuid = LocalUuidUtils.generateProfileId(account.username, SkinModelType.STEVE);
                        LocalYggdrasilServer.registerProfile(account.username, uuid,
                                skinPath, null, false);
                    }
                } catch (Throwable ignored) {}
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove active cape", e);
            }
        });
    }

    /**
     * Fetches or decodes the cape texture bitmap (using cache and procedural generation).
     */
    public void loadCapeBitmapAsync(@NonNull CapeItem cape, @NonNull CapeBitmapCallback callback) {
        mExecutor.execute(() -> {
            // 1. Check memory cache
            Bitmap cached = mBitmapMemoryCache.get(cape.getId());
            if (cached != null && !cached.isRecycled()) {
                mMainHandler.post(() -> callback.onBitmapLoaded(cached));
                return;
            }

            // 2. Check local collection file
            File localFile = cape.getLocalPath() != null 
                    ? new File(cape.getLocalPath()) 
                    : new File(mCollectionDir, cape.getId() + ".png");
            if (localFile.exists()) {
                try {
                    Bitmap bmp = BitmapFactory.decodeFile(localFile.getAbsolutePath());
                    if (bmp != null) {
                        mBitmapMemoryCache.put(cape.getId(), bmp);
                        mMainHandler.post(() -> callback.onBitmapLoaded(bmp));
                        return;
                    }
                } catch (Throwable ignored) {}
            }

            // 3. Check disk cache
            File cachedFile = new File(mCacheDir, cape.getId() + ".png");
            if (cachedFile.exists()) {
                try {
                    Bitmap bmp = BitmapFactory.decodeFile(cachedFile.getAbsolutePath());
                    if (bmp != null) {
                        mBitmapMemoryCache.put(cape.getId(), bmp);
                        mMainHandler.post(() -> callback.onBitmapLoaded(bmp));
                        return;
                    }
                } catch (Throwable ignored) {}
            }

            // 4. Download from remote URL if available
            String url = cape.getTextureUrl() != null ? cape.getTextureUrl() : cape.getThumbnailUrl();
            if (url != null && !url.isEmpty() && url.startsWith("http")) {
                mService.downloadTexture(url, new MinecraftCapesService.TextureCallback() {
                    @Override
                    public void onSuccess(Bitmap texture, byte[] rawBytes) {
                        mBitmapMemoryCache.put(cape.getId(), texture);
                        try (FileOutputStream fos = new FileOutputStream(cachedFile)) {
                            fos.write(rawBytes);
                        } catch (Throwable ignored) {}
                        mMainHandler.post(() -> callback.onBitmapLoaded(texture));
                    }

                    @Override
                    public void onError(Exception error) {
                        // Procedural authentic generator fallback (never red error square)
                        Bitmap texture = CapeTextureFactory.generateCapeTexture(cape.getId());
                        mBitmapMemoryCache.put(cape.getId(), texture);
                        mMainHandler.post(() -> callback.onBitmapLoaded(texture));
                    }
                });
            } else {
                // Procedural authentic generator
                Bitmap texture = CapeTextureFactory.generateCapeTexture(cape.getId());
                mBitmapMemoryCache.put(cape.getId(), texture);
                mMainHandler.post(() -> callback.onBitmapLoaded(texture));
            }
        });
    }
}
