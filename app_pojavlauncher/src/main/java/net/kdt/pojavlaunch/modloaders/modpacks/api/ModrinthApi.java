package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.app.Activity;
import android.net.Uri;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModrinthIndex;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;
import net.kdt.pojavlaunch.utils.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

public class ModrinthApi implements ModpackApi{
    private final ApiHandler mApiHandler;
    public ModrinthApi(){
        mApiHandler = new ApiHandler("https://api.modrinth.com/v2");
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        ModrinthSearchResult modrinthSearchResult = (ModrinthSearchResult) previousPageResult;

        // Fixes an issue where the offset being equal or greater than total_hits is ignored
        if (modrinthSearchResult != null && modrinthSearchResult.previousOffset >= modrinthSearchResult.totalResultCount) {
            ModrinthSearchResult emptyResult = new ModrinthSearchResult();
            emptyResult.results = new ModItem[0];
            emptyResult.totalResultCount = modrinthSearchResult.totalResultCount;
            emptyResult.previousOffset = modrinthSearchResult.previousOffset;
            return emptyResult;
        }


        // Build the facets filters
        HashMap<String, Object> params = new HashMap<>();
        StringBuilder facetString = new StringBuilder();
        facetString.append("[");
        String projectType = searchFilters.projectType;
        if (projectType == null || projectType.isEmpty()) {
            projectType = searchFilters.isModpack ? "modpack" : "mod";
        }
        facetString.append(String.format("[\"project_type:%s\"]", projectType));
        if(searchFilters.mcVersion != null && !searchFilters.mcVersion.isEmpty())
            facetString.append(String.format(",[\"versions:%s\"]", searchFilters.mcVersion));
        if(searchFilters.modLoader != null && !searchFilters.modLoader.isEmpty())
            facetString.append(String.format(",[\"categories:%s\"]", searchFilters.modLoader));
        if(searchFilters.categories != null && !searchFilters.categories.isEmpty())
            facetString.append(String.format(",[\"categories:%s\"]", searchFilters.categories));
        facetString.append("]");
        params.put("facets", facetString.toString());
        params.put("query", searchFilters.name);
        params.put("limit", 50);
        String sortIndex = searchFilters.sortIndex;
        if (sortIndex == null || sortIndex.isEmpty()
                || !(sortIndex.equals("downloads") || sortIndex.equals("follows")
                || sortIndex.equals("newest") || sortIndex.equals("updated"))) {
            sortIndex = "relevance";
        }
        params.put("index", sortIndex);
        if(modrinthSearchResult != null)
            params.put("offset", modrinthSearchResult.previousOffset);

        JsonObject response = mApiHandler.get("search", params, JsonObject.class);
        if(response == null) return null;
        JsonArray responseHits = response.getAsJsonArray("hits");
        if(responseHits == null) return null;

        ModItem[] items = new ModItem[responseHits.size()];
        for(int i=0; i<responseHits.size(); ++i){
            JsonObject hit = responseHits.get(i).getAsJsonObject();
            items[i] = new ModItem(
                    Constants.SOURCE_MODRINTH,
                    hit.get("project_type").getAsString().equals("modpack"),
                    hit.get("project_id").getAsString(),
                    hit.get("title").getAsString(),
                    hit.has("author") ? hit.get("author").getAsString() : null,
                    hit.has("downloads") ? hit.get("downloads").getAsString() : null,
                    hit.get("description").getAsString(),
                    hit.get("icon_url").getAsString()
            );
            if (hit.has("follows") && !hit.get("follows").isJsonNull()) {
                items[i].followers = hit.get("follows").getAsString();
            }
            if (hit.has("categories") && hit.get("categories").isJsonArray()) {
                JsonArray categories = hit.getAsJsonArray("categories");
                items[i].categories = new String[Math.min(categories.size(), 5)];
                for (int j = 0; j < items[i].categories.length; j++) {
                    items[i].categories[j] = categories.get(j).getAsString();
                }
            }
            if (hit.has("gallery") && !hit.get("gallery").isJsonNull() && hit.get("gallery").isJsonArray()) {
                JsonArray gallery = hit.getAsJsonArray("gallery");
                if (gallery.size() > 0) {
                    items[i].galleryUrls = new String[gallery.size()];
                    for (int j = 0; j < gallery.size(); j++) {
                        items[i].galleryUrls[j] = gallery.get(j).getAsString();
                    }
                    items[i].galleryUrl = items[i].galleryUrls[0];
                }
            }
        }
        if(modrinthSearchResult == null) modrinthSearchResult = new ModrinthSearchResult();
        modrinthSearchResult.previousOffset += responseHits.size();
        modrinthSearchResult.results = items;
        modrinthSearchResult.totalResultCount = response.get("total_hits").getAsInt();
        return modrinthSearchResult;
    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        return getModDetails(item, null, null);
    }

    public ModDetail getModDetails(ModItem item, String filterMcVersion) {
        return getModDetails(item, filterMcVersion, null);
    }

    public ModDetail getModDetails(ModItem item, String filterMcVersion, String filterLoader) {
        JsonArray response = mApiHandler.get(String.format("project/%s/version", item.id), JsonArray.class);
        if(response == null) return null;

        // Collect versions, optionally filtering by MC version and/or loader
        java.util.List<JsonObject> versions = new java.util.ArrayList<>();
        for (int i = 0; i < response.size(); i++) {
            JsonObject v = response.get(i).getAsJsonObject();
            if (filterMcVersion != null && !filterMcVersion.isEmpty()) {
                JsonArray gameVersions = v.get("game_versions").getAsJsonArray();
                boolean matches = false;
                for (int j = 0; j < gameVersions.size(); j++) {
                    if (filterMcVersion.equals(gameVersions.get(j).getAsString())) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) continue;
            }
            if (filterLoader != null && !filterLoader.isEmpty()) {
                JsonArray loaders = v.get("loaders").getAsJsonArray();
                boolean matches = false;
                for (int j = 0; j < loaders.size(); j++) {
                    if (filterLoader.equalsIgnoreCase(loaders.get(j).getAsString())) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) continue;
            }
            versions.add(v);
        }

        if (versions.isEmpty()) return null;

        int size = versions.size();
        String[] names      = new String[size];
        String[] mcNames    = new String[size];
        String[] urls       = new String[size];
        String[] hashes     = new String[size];
        String[][] depIds   = new String[size][];
        String[][] depTypes = new String[size][];
        String[][] loadersArr = new String[size][];
        String[][] mcListArr  = new String[size][]; // full game_versions per version (accurate compat)
        String[] changelogs   = new String[size];   // per-version changelog for the install page

        for (int i = 0; i < size; i++) {
            JsonObject version = versions.get(i);
            names[i]   = version.get("name").getAsString();
            JsonArray gameVersions = version.get("game_versions").getAsJsonArray();
            mcNames[i] = gameVersions.get(gameVersions.size() - 1).getAsString(); // newest for display
            java.util.List<String> mcList = new java.util.ArrayList<>();
            for (int j = 0; j < gameVersions.size(); j++) mcList.add(gameVersions.get(j).getAsString());
            mcListArr[i] = mcList.toArray(new String[0]); // full support list for compat checks
            urls[i]    = version.get("files").getAsJsonArray().get(0).getAsJsonObject().get("url").getAsString();

            JsonObject hashesMap = version.getAsJsonArray("files").get(0).getAsJsonObject()
                    .get("hashes").getAsJsonObject();
            hashes[i] = (hashesMap == null || hashesMap.get("sha1") == null) ? null
                    : hashesMap.get("sha1").getAsString();

            // Capture dependencies
            if (version.has("dependencies") && !version.get("dependencies").isJsonNull()) {
                JsonArray deps = version.getAsJsonArray("dependencies");
                java.util.List<String> ids   = new java.util.ArrayList<>();
                java.util.List<String> types = new java.util.ArrayList<>();
                for (int j = 0; j < deps.size(); j++) {
                    JsonObject dep = deps.get(j).getAsJsonObject();
                    if (dep.has("project_id") && !dep.get("project_id").isJsonNull()) {
                        ids.add(dep.get("project_id").getAsString());
                        types.add(dep.has("dependency_type") ? dep.get("dependency_type").getAsString() : "required");
                    }
                }
                depIds[i]   = ids.toArray(new String[0]);
                depTypes[i] = types.toArray(new String[0]);
            } else {
                depIds[i]   = new String[0];
                depTypes[i] = new String[0];
            }

            if (version.has("loaders") && !version.get("loaders").isJsonNull()) {
                JsonArray lds = version.getAsJsonArray("loaders");
                java.util.List<String> lList = new java.util.ArrayList<>();
                for (int j = 0; j < lds.size(); j++) {
                    lList.add(lds.get(j).getAsString());
                }
                loadersArr[i] = lList.toArray(new String[0]);
            } else {
                loadersArr[i] = new String[0];
            }

            // Changelog for the selected version (shown on the install page)
            if (version.has("changelog") && !version.get("changelog").isJsonNull()) {
                String cl = version.get("changelog").getAsString();
                changelogs[i] = (cl == null || cl.trim().isEmpty()) ? null : cl;
            } else {
                changelogs[i] = null;
            }
        }

        ModDetail detail = new ModDetail(item, names, mcNames, urls, hashes, depIds, depTypes, loadersArr);
        detail.mcVersionLists = mcListArr;
        detail.versionChangelogs = changelogs;
        return detail;
    }

    /**
     * Fetch rich project metadata for the install page.
     * Hits /v2/project/{id} plus /v2/project/{id}/members for the owner name.
     * Returns null on failure — the caller falls back to the search-hit data.
     */
    public net.kdt.pojavlaunch.modloaders.modpacks.models.ModProjectInfo fetchProjectInfo(String projectId) {
        JsonObject p;
        try {
            p = mApiHandler.get("project/" + projectId, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
        if (p == null) return null;

        net.kdt.pojavlaunch.modloaders.modpacks.models.ModProjectInfo info =
                new net.kdt.pojavlaunch.modloaders.modpacks.models.ModProjectInfo();
        info.title      = optString(p, "title");
        info.tagline    = optString(p, "description");
        info.body       = optString(p, "body");
        info.downloads  = p.has("downloads") && !p.get("downloads").isJsonNull()
                ? p.get("downloads").getAsLong() : -1;
        info.followers  = p.has("followers") && !p.get("followers").isJsonNull()
                ? p.get("followers").getAsLong() : -1;
        info.updatedIso = optString(p, "updated");
        info.sourceUrl  = optString(p, "source_url");
        info.issuesUrl  = optString(p, "issues_url");
        info.wikiUrl    = optString(p, "wiki_url");
        info.discordUrl = optString(p, "discord_url");

        if (p.has("license") && p.get("license").isJsonObject()) {
            JsonObject lic = p.getAsJsonObject("license");
            info.license = optString(lic, "id");
        }
        info.categories   = jsonStringArray(p, "categories");
        info.loaders      = jsonStringArray(p, "loaders");
        info.gameVersions = jsonStringArray(p, "game_versions");

        if (p.has("gallery") && p.get("gallery").isJsonArray()) {
            JsonArray gallery = p.getAsJsonArray("gallery");
            java.util.List<String> urls = new java.util.ArrayList<>();
            for (int i = 0; i < gallery.size(); i++) {
                JsonObject img = gallery.get(i).getAsJsonObject();
                String url = optString(img, "url");
                if (url != null && !url.isEmpty()) urls.add(url);
            }
            info.galleryUrls = urls.toArray(new String[0]);
        }

        // Owner display name (separate endpoint — best effort, never fatal)
        try {
            JsonArray members = mApiHandler.get("project/" + projectId + "/members", JsonArray.class);
            if (members != null) {
                String owner = null, first = null;
                for (int i = 0; i < members.size(); i++) {
                    JsonObject m = members.get(i).getAsJsonObject();
                    String name = null;
                    if (m.has("user") && m.get("user").isJsonObject()) {
                        name = optString(m.getAsJsonObject("user"), "username");
                    }
                    if (name == null) continue;
                    if (first == null) first = name;
                    String role = optString(m, "role");
                    if (role != null && role.equalsIgnoreCase("owner")) { owner = name; break; }
                }
                info.author = owner != null ? owner : first;
            }
        } catch (Exception ignored) {}

        return info;
    }

    private static String optString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        try { return o.get(key).getAsString(); } catch (Exception e) { return null; }
    }

    private static String[] jsonStringArray(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return null;
        JsonArray arr = o.getAsJsonArray(key);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i).isJsonPrimitive()) out.add(arr.get(i).getAsString());
        }
        return out.toArray(new String[0]);
    }

    @Override
    public ModLoader installMod(ModDetail modDetail, int selectedVersion) throws IOException{
        //TODO considering only modpacks for now
        return ModpackInstaller.installModpack(modDetail, selectedVersion, this::installMrpack);
    }

    @Override
    public ModLoader importModpack(File modpackFile) throws IOException, NoSuchAlgorithmException {
        return ModpackInstaller.importModpack(modpackFile, Constants.SOURCE_MODRINTH, this::installMrpack);
    }

    private static ModLoader createInfo(ModrinthIndex modrinthIndex) {
        if(modrinthIndex == null) return null;
        Map<String, String> dependencies = modrinthIndex.dependencies;
        String mcVersion = dependencies.get("minecraft");
        if(mcVersion == null) return null;
        String modLoaderVersion;
        if((modLoaderVersion = dependencies.get("forge")) != null) {
            return new ModLoader(ModLoader.MOD_LOADER_FORGE, modLoaderVersion, mcVersion);
        }
        if((modLoaderVersion = dependencies.get("fabric-loader")) != null) {
            return new ModLoader(ModLoader.MOD_LOADER_FABRIC, modLoaderVersion, mcVersion);
        }
        if((modLoaderVersion = dependencies.get("quilt-loader")) != null) {
            return new ModLoader(ModLoader.MOD_LOADER_QUILT, modLoaderVersion, mcVersion);
        }
        if((modLoaderVersion = dependencies.get("neoforge")) != null) {
            return new ModLoader(ModLoader.MOD_LOADER_NEOFORGE, modLoaderVersion, mcVersion);
        }
        return null;
    }

    public ModLoader installMrpack(File mrpackFile, File instanceDestination) throws IOException {
        return installMrpack(mrpackFile, instanceDestination,
                new DownloaderProgressWrapper(R.string.modpack_download_downloading_mods, ProgressLayout.INSTALL_MODPACK),
                true);
    }

    /**
     * Installs an mrpack while reporting only to the supplied callback. This is
     * used by the full-screen CS Client installer so the launcher's global
     * ProgressLayout is never started.
     */
    public ModLoader installMrpack(File mrpackFile, File instanceDestination,
                                   Tools.DownloaderFeedback feedback) throws IOException {
        return installMrpack(mrpackFile, instanceDestination, feedback, false);
    }

    private ModLoader installMrpack(File mrpackFile, File instanceDestination,
                                    Tools.DownloaderFeedback feedback,
                                    boolean publishGlobalProgress) throws IOException {
        try (ZipFile modpackZipFile = new ZipFile(mrpackFile)){
            ModrinthIndex modrinthIndex = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(modpackZipFile, "modrinth.index.json")),
                    ModrinthIndex.class);

            ModDownloader modDownloader = publishGlobalProgress
                    ? new ModDownloader(instanceDestination)
                    : new ModDownloader(instanceDestination, false, null);
            for(ModrinthIndex.ModrinthIndexFile indexFile : modrinthIndex.files) {
                modDownloader.submitDownload(indexFile.fileSize, indexFile.path, indexFile.hashes.sha1, indexFile.downloads);
            }
            modDownloader.awaitFinish(feedback);
            if (publishGlobalProgress) ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.modpack_download_applying_overrides, 1, 2);
            else feedback.updateProgress(1, 2);
            ZipUtils.zipExtract(modpackZipFile, "overrides/", instanceDestination);
            if (publishGlobalProgress) ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 50, R.string.modpack_download_applying_overrides, 2, 2);
            else feedback.updateProgress(2, 2);
            ZipUtils.zipExtract(modpackZipFile, "client-overrides/", instanceDestination);
            return createInfo(modrinthIndex);
        }
    }

    class ModrinthSearchResult extends SearchResult {
        int previousOffset;
    }
}