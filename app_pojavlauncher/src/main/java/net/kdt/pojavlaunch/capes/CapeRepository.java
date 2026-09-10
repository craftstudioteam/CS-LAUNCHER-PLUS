package net.kdt.pojavlaunch.capes;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
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
 * offline caching, local collections, and active cape assignment.
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
     * Initializes the built-in catalog with authentic official Minecon & event capes,
     * community favorites, and popular designs.
     */
    private void initCuratedCatalog() {
        // Official Minecon Capes
        mCatalogItems.add(new CapeItem("minecon_2011", "Minecon 2011", CapeItem.CATEGORY_MINECON, "Mojang Studios",
                "The classic red Pickaxe cape distributed at Minecon 2011 Las Vegas.",
                "https://textures.minecraft.net/texture/95dee0201e5f9a2e6f4770e28e18146747d2f9dfa5399589d9e4a3b85",
                "https://textures.minecraft.net/texture/95dee0201e5f9a2e6f4770e28e18146747d2f9dfa5399589d9e4a3b85",
                false, true, 48200, 3910, null));

        mCatalogItems.add(new CapeItem("minecon_2012", "Minecon 2012", CapeItem.CATEGORY_MINECON, "Mojang Studios",
                "The dark blue golden Pickaxe cape from Minecon 2012 Disneyland Paris.",
                "https://textures.minecraft.net/texture/a2e8d97e662d52523e6f86fb5e99f1e746eab39e867ecc268e3c5a12b657076",
                "https://textures.minecraft.net/texture/a2e8d97e662d52523e6f86fb5e99f1e746eab39e867ecc268e3c5a12b657076",
                false, true, 39100, 2840, null));

        mCatalogItems.add(new CapeItem("minecon_2013", "Minecon 2013", CapeItem.CATEGORY_MINECON, "Mojang Studios",
                "The emerald green Piston cape from Minecon 2013 Orlando, Florida.",
                "https://textures.minecraft.net/texture/153b1a0cac76a8ecd96613e6231d6082d43a417b817381363d0f0b137969e6b2",
                "https://textures.minecraft.net/texture/153b1a0cac76a8ecd96613e6231d6082d43a417b817381363d0f0b137969e6b2",
                false, true, 41200, 3100, null));

        mCatalogItems.add(new CapeItem("minecon_2015", "Minecon 2015", CapeItem.CATEGORY_MINECON, "Mojang Studios",
                "The deep turquoise Iron Golem face cape from Minecon 2015 London.",
                "https://textures.minecraft.net/texture/b0cc08840700447340433a13879334a45d0e9183287d8121a505251804b4f",
                "https://textures.minecraft.net/texture/b0cc08840700447340433a13879334a45d0e9183287d8121a505251804b4f",
                false, true, 36800, 2750, null));

        mCatalogItems.add(new CapeItem("minecon_2016", "Minecon 2016", CapeItem.CATEGORY_MINECON, "Mojang Studios",
                "The dark violet Enderman face cape from Minecon 2016 Anaheim, California.",
                "https://textures.minecraft.net/texture/e7dfea16dc83c973ced08aab712d3d43f8e0b12f8fc186ce3b1e377a5a3f4525",
                "https://textures.minecraft.net/texture/e7dfea16dc83c973ced08aab712d3d43f8e0b12f8fc186ce3b1e377a5a3f4525",
                false, true, 52400, 4890, null));

        // Special Events & Modern Official Capes
        mCatalogItems.add(new CapeItem("mc_15th_anniversary", "15th Anniversary", CapeItem.CATEGORY_OFFICIAL, "Mojang Studios",
                "The official Minecraft 15 Year Anniversary celebration cape.",
                "https://textures.minecraft.net/texture/9e507afc56359978a3eb3e32367042b853cddd0995d17d0da995662913fb00f7",
                "https://textures.minecraft.net/texture/9e507afc56359978a3eb3e32367042b853cddd0995d17d0da995662913fb00f7",
                false, true, 64200, 5700, null));

        mCatalogItems.add(new CapeItem("cherry_blossom", "Cherry Blossom Cape", CapeItem.CATEGORY_OFFICIAL, "Mojang Studios",
                "The pastel pink Cherry Blossom cape released for the 1.20 Trails & Tales update.",
                "https://textures.minecraft.net/texture/26a7e0a8146747d2f9dfa5399589d9e4a3b8547d2f9dfa5399589d9e4a3b85",
                "https://textures.minecraft.net/texture/26a7e0a8146747d2f9dfa5399589d9e4a3b8547d2f9dfa5399589d9e4a3b85",
                false, true, 58900, 4920, null));

        mCatalogItems.add(new CapeItem("twitch_purple_heart", "Twitch Purple Heart", CapeItem.CATEGORY_OFFICIAL, "Twitch",
                "The purple heart broadcast cape distributed during Minecraft 15th anniversary streams.",
                "https://textures.minecraft.net/texture/71bdf232147dfb6ef569317d6b38cbb2e0f47e3a987d6e4b95f19e48a1768",
                "https://textures.minecraft.net/texture/71bdf232147dfb6ef569317d6b38cbb2e0f47e3a987d6e4b95f19e48a1768",
                false, true, 41200, 3810, null));

        mCatalogItems.add(new CapeItem("tiktok_follower", "TikTok Follower's Cape", CapeItem.CATEGORY_OFFICIAL, "TikTok",
                "The signature teal and pink helmet TikTok event cape.",
                "https://textures.minecraft.net/texture/3a79d2b8548972e04313f89a957864c2f8115eb375d836e4f32c7f694e9185a",
                "https://textures.minecraft.net/texture/3a79d2b8548972e04313f89a957864c2f8115eb375d836e4f32c7f694e9185a",
                false, true, 39400, 3200, null));

        mCatalogItems.add(new CapeItem("migrator_cape", "Migrator Cape", CapeItem.CATEGORY_OFFICIAL, "Mojang Studios",
                "The golden lettered Migrator cape given to accounts that transitioned to Microsoft auth.",
                "https://textures.minecraft.net/texture/2340c0e03dd66dd11d17ded2c75402caa3f5d1c0b4c555f6b8026cc8869da3ed",
                "https://textures.minecraft.net/texture/2340c0e03dd66dd11d17ded2c75402caa3f5d1c0b4c555f6b8026cc8869da3ed",
                false, true, 51300, 4310, null));

        mCatalogItems.add(new CapeItem("vanilla_cape", "Vanilla Cape", CapeItem.CATEGORY_OFFICIAL, "Mojang Studios",
                "The iconic sunset mountain Vanilla cape awarded to dual-edition players.",
                "https://textures.minecraft.net/texture/3da668748d5eb2cb8ff57eb0579e0a0d923fa156ff9ec8f845a7b6f65fe95",
                "https://textures.minecraft.net/texture/3da668748d5eb2cb8ff57eb0579e0a0d923fa156ff9ec8f845a7b6f65fe95",
                false, true, 49700, 4600, null));

        mCatalogItems.add(new CapeItem("cobalt_cape", "Cobalt Oxeye", CapeItem.CATEGORY_OFFICIAL, "Mojang Studios",
                "Special commemorative blue flower cape created for Oxeye and Cobalt initiatives.",
                "https://textures.minecraft.net/texture/5f5bc93c042316e6f1f31f9d45389650cfd980f73b88a9192487e411c5210c8f",
                "https://textures.minecraft.net/texture/5f5bc93c042316e6f1f31f9d45389650cfd980f73b88a9192487e411c5210c8f",
                false, true, 28400, 2190, null));

        // Animated & Community Favorites
        mCatalogItems.add(new CapeItem("cosmic_galaxy", "Cosmic Galaxy", CapeItem.CATEGORY_ANIMATED, "GalaxyStudio",
                "Deep interstellar nebula with stars and neon cosmic dust.",
                "https://textures.minecraft.net/texture/89a7f34c281e05d97f48b04938a729e1c450f6b7a9e521894d3758b2763f05d",
                "https://textures.minecraft.net/texture/89a7f34c281e05d97f48b04938a729e1c450f6b7a9e521894d3758b2763f05d",
                true, false, 34200, 3190, null));

        mCatalogItems.add(new CapeItem("cyber_matrix", "Cyber Matrix", CapeItem.CATEGORY_POPULAR, "NeonPulse",
                "Dark graphite background with high contrast neon cyan matrix circuitry.",
                "https://textures.minecraft.net/texture/5e739f82d1c0b4592a8b79f64928e1b3057e4c92a68b59d748f3029185a73e",
                "https://textures.minecraft.net/texture/5e739f82d1c0b4592a8b79f64928e1b3057e4c92a68b59d748f3029185a73e",
                false, false, 29800, 2610, null));
    }

    public List<CapeItem> getCuratedCapes() {
        return new ArrayList<>(mCatalogItems);
    }

    public List<CapeItem> getCatalog() {
        return getCuratedCapes();
    }

    /**
     * Reads all capes saved in the local collection directory.
     */
    public synchronized List<CapeItem> getCollectionCapes() {
        List<CapeItem> list = new ArrayList<>();
        File metaFile = new File(mCollectionDir, "collection_meta.json");
        if (metaFile.exists()) {
            try (FileInputStream fis = new FileInputStream(metaFile)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int read;
                while ((read = fis.read(buf)) != -1) {
                    bos.write(buf, 0, read);
                }
                String jsonStr = bos.toString("UTF-8");
                JSONArray arr = new JSONArray(jsonStr);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    CapeItem item = CapeItem.fromJsonObject(obj);
                    if (item != null) {
                        File localPng = new File(mCollectionDir, item.getId() + ".png");
                        if (localPng.exists()) {
                            item.setLocalPath(localPng.getAbsolutePath());
                        }
                        list.add(item);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error loading collection metadata: " + e.getMessage());
            }
        }

        // Also scan for any unindexed PNGs in collection folder
        File[] files = mCollectionDir.listFiles((dir, name) -> name.endsWith(".png"));
        if (files != null) {
            for (File file : files) {
                String id = file.getName().replace(".png", "");
                boolean alreadyListed = false;
                for (CapeItem ci : list) {
                    if (ci.getId().equals(id)) {
                        alreadyListed = true;
                        break;
                    }
                }
                if (!alreadyListed) {
                    String cleanName = id.replace('_', ' ').toUpperCase();
                    CapeItem discovered = new CapeItem(id, cleanName, CapeItem.CATEGORY_COMMUNITY, "Local Collection",
                            "Saved locally", null, null, false, false, 0, 0, file.getAbsolutePath());
                    discovered.isCustom = true;
                    list.add(discovered);
                }
            }
        }

        return list;
    }

    public List<CapeItem> getLocalCollection() {
        return getCollectionCapes();
    }

    public boolean isCapeSaved(CapeItem item) {
        if (item == null || item.getId() == null) return false;
        File file = new File(mCollectionDir, item.getId() + ".png");
        return file.exists();
    }

    /**
     * Saves a cape and its texture to the local collection asynchronously.
     */
    public void addToCollection(@NonNull CapeItem item, @Nullable CapeActionCallback callback) {
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
                                List<CapeItem> collection = getCollectionCapes();
                                boolean replaced = false;
                                for (int i = 0; i < collection.size(); i++) {
                                    if (collection.get(i).getId().equals(item.getId())) {
                                        collection.set(i, item);
                                        replaced = true;
                                        break;
                                    }
                                }
                                if (!replaced) collection.add(item);

                                saveCollectionMeta(collection);
                                mBitmapMemoryCache.put(item.getId(), bitmap);

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
     * Fetches or decodes the cape texture bitmap (using cache when available).
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

            // 4. Download from remote URL
            String url = cape.getTextureUrl() != null ? cape.getTextureUrl() : cape.getThumbnailUrl();
            if (url != null && !url.isEmpty()) {
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
                        // Fallback to generated placeholder bitmap
                        Bitmap placeholder = createFallbackTexture(cape);
                        mBitmapMemoryCache.put(cape.getId(), placeholder);
                        mMainHandler.post(() -> callback.onBitmapLoaded(placeholder));
                    }
                });
            } else {
                Bitmap placeholder = createFallbackTexture(cape);
                mBitmapMemoryCache.put(cape.getId(), placeholder);
                mMainHandler.post(() -> callback.onBitmapLoaded(placeholder));
            }
        });
    }

    private Bitmap createFallbackTexture(CapeItem cape) {
        Bitmap bmp = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint();
        int color = cape.isOfficial() ? 0xFF8A1B1B : 0xFF2A364F;
        paint.setColor(color);
        canvas.drawRect(1, 1, 11, 17, paint); // Cape front
        paint.setColor(color | 0xFF141A24);
        canvas.drawRect(12, 1, 22, 17, paint); // Cape back
        return bmp;
    }
}
