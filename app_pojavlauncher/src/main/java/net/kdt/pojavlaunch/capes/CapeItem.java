package net.kdt.pojavlaunch.capes;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;

/**
 * Data model for a Minecraft Cape in the browser and local collection.
 */
public class CapeItem implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String CATEGORY_ALL       = "all";
    public static final String CATEGORY_MINECON   = "minecon";
    public static final String CATEGORY_OFFICIAL  = "official";
    public static final String CATEGORY_COMMUNITY = "community";
    public static final String CATEGORY_ANIMATED  = "animated";
    public static final String CATEGORY_POPULAR   = "popular";

    public final String id;
    public final String name;
    public final String category;
    public final String author;
    public final String description;
    public final String thumbnailUrl;
    public final String textureUrl;
    public final boolean isAnimated;
    public final boolean isOfficial;
    public final int downloads;
    public final int likes;

    public String localPath; // Non-null if downloaded to local collection
    public boolean isCustom;

    public CapeItem(String id, String name, String category, String author, String description,
                    String thumbnailUrl, String textureUrl, boolean isAnimated, boolean isOfficial,
                    int downloads, int likes, @Nullable String localPath) {
        this.id = id != null ? id : "";
        this.name = name != null ? name : "Unnamed Cape";
        this.category = category != null ? category : CATEGORY_COMMUNITY;
        this.author = author != null ? author : "MinecraftCapes";
        this.description = description != null ? description : "";
        this.thumbnailUrl = thumbnailUrl;
        this.textureUrl = textureUrl;
        this.isAnimated = isAnimated;
        this.isOfficial = isOfficial;
        this.downloads = downloads;
        this.likes = likes;
        this.localPath = localPath;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public String getAuthor() {
        return author;
    }

    public String getDescription() {
        return description;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public String getTextureUrl() {
        return textureUrl;
    }

    public boolean isAnimated() {
        return isAnimated;
    }

    public boolean isOfficial() {
        return isOfficial;
    }

    public int getDownloads() {
        return downloads;
    }

    public int getLikes() {
        return likes;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public JSONObject toJsonObject() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("category", category);
            json.put("author", author);
            json.put("description", description);
            json.put("thumbnailUrl", thumbnailUrl);
            json.put("textureUrl", textureUrl);
            json.put("isAnimated", isAnimated);
            json.put("isOfficial", isOfficial);
            json.put("downloads", downloads);
            json.put("likes", likes);
            json.put("localPath", localPath);
            json.put("isCustom", isCustom);
        } catch (JSONException ignored) {}
        return json;
    }

    public static CapeItem fromJsonObject(JSONObject json) {
        if (json == null) return null;
        String id = json.optString("id", "");
        String name = json.optString("name", "Cape");
        String category = json.optString("category", CATEGORY_COMMUNITY);
        String author = json.optString("author", "MinecraftCapes");
        String description = json.optString("description", "");
        String thumb = json.optString("thumbnailUrl", null);
        String texture = json.optString("textureUrl", null);
        boolean anim = json.optBoolean("isAnimated", false);
        boolean official = json.optBoolean("isOfficial", false);
        int dl = json.optInt("downloads", 0);
        int likes = json.optInt("likes", 0);
        String local = json.optString("localPath", null);
        CapeItem item = new CapeItem(id, name, category, author, description, thumb, texture, anim, official, dl, likes, local);
        item.isCustom = json.optBoolean("isCustom", false);
        return item;
    }

    @NonNull
    @Override
    public String toString() {
        return "CapeItem{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", category='" + category + '\'' +
                '}';
    }
}
