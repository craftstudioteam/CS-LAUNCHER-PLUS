package net.kdt.pojavlaunch.csclient;

import net.kdt.pojavlaunch.utils.DownloadUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Serializable;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Bundled CS Client catalog; Modrinth remains the source for optional content. */
public final class CsClientCatalog {
    public static final String FABRIC_API_PROJECT = "P7dR8mSH";
    private static final String API = "https://api.modrinth.com/v2/";

    public static final class VersionEntry implements Serializable {
        public String minecraft, versionId, build, name, fileUrl, fileName, sha1;
        public String assetPath, sha256, minimumLoader;
    }
    public static final class ProjectEntry implements Serializable {
        public String id, title, description, iconUrl;
        public boolean modpack;
        public ProjectEntry(String id, String title, String description, String iconUrl, boolean modpack) {
            this.id=id;this.title=title;this.description=description;this.iconUrl=iconUrl;this.modpack=modpack;
        }
    }
    public static final class FileEntry {
        public String url, name, sha1;
        public FileEntry(String u,String n,String s){url=u;name=n;sha1=s;}
    }

    private CsClientCatalog() {}

    public static List<VersionEntry> fetchLatestByMinecraft() {
        List<VersionEntry> out=new ArrayList<>();
        out.add(bundled("1.21.11","1.0.0+1.21.11","0.18.2","cs-client-V1+1.21.11.jar","feabbf0d172834cba3eba8ba30ce724d820730946ed9d1f282ef9940491a4f17"));
        out.add(bundled("26.1","1.0.0+26.1","0.19.3","cs-client-V1+26.1.jar","a75474425f54130fbea228a82c31eea64f89161c970f69c6108b01e810446a62"));
        out.add(bundled("26.2","1.0.0+26.2","0.19.3","cs-client-V1+26.2.jar","8c1f9a45205dbba8d2df41553a1adeb4be7a2c7f244df7248eb3b91ac35a6365"));
        return out;
    }
    private static VersionEntry bundled(String mc,String build,String loader,String file,String sha){VersionEntry e=new VersionEntry();e.minecraft=mc;e.versionId="cs-client-"+build;e.build=build;e.name="CS Client V1";e.fileName=file;e.assetPath="components/cs_client/"+file;e.sha256=sha;e.minimumLoader=loader;return e;}

    public static List<ProjectEntry> search(String query,String mc,boolean packs) throws Exception {
        String facets="[[\"project_type:"+(packs?"modpack":"mod")+"\"],[\"versions:"+mc+"\"],[\"categories:fabric\"]]";
        String url=API+"search?limit=40&index=downloads&query="+URLEncoder.encode(query==null?"":query,"UTF-8")+"&facets="+URLEncoder.encode(facets,"UTF-8");
        JSONObject root=new JSONObject(DownloadUtils.downloadString(url));JSONArray hits=root.getJSONArray("hits");
        List<ProjectEntry> out=new ArrayList<>();
        for(int i=0;i<hits.length();i++){JSONObject h=hits.getJSONObject(i);out.add(new ProjectEntry(
                h.getString("project_id"),h.getString("title"),h.optString("description",""),h.optString("icon_url",null),packs));}
        return out;
    }

    public static FileEntry latestFile(String project,String mc,boolean modpack) throws Exception {
        JSONArray arr=new JSONArray(DownloadUtils.downloadString(API+"project/"+project+"/version"));
        for(int i=0;i<arr.length();i++){
            JSONObject v=arr.getJSONObject(i);if(!contains(v.optJSONArray("game_versions"),mc))continue;
            if(!modpack&&!containsIgnoreCase(v.optJSONArray("loaders"),"fabric"))continue;
            JSONObject f=primary(v.optJSONArray("files"));if(f==null)continue;
            JSONObject hashes=f.optJSONObject("hashes");return new FileEntry(f.getString("url"),f.getString("filename"),hashes==null?null:hashes.optString("sha1",null));
        }
        throw new IllegalStateException("No compatible "+(modpack?"modpack":"mod")+" version for Minecraft "+mc);
    }

    private static JSONObject primary(JSONArray a){if(a==null)return null;for(int i=0;i<a.length();i++){JSONObject f=a.optJSONObject(i);if(f!=null&&f.optBoolean("primary",false))return f;}return a.optJSONObject(0);}
    private static boolean contains(JSONArray a,String value){if(a==null)return false;for(int i=0;i<a.length();i++)if(value.equals(a.optString(i)))return true;return false;}
    private static boolean containsIgnoreCase(JSONArray a,String value){if(a==null)return false;for(int i=0;i<a.length();i++)if(value.equalsIgnoreCase(a.optString(i)))return true;return false;}
    private static int compareVersions(String a,String b){String[]x=a.split("\\."),y=b.split("\\.");for(int i=0;i<Math.max(x.length,y.length);i++){int xi=i<x.length?num(x[i]):0,yi=i<y.length?num(y[i]):0;if(xi!=yi)return Integer.compare(xi,yi);}return a.compareTo(b);}
    private static int num(String s){try{return Integer.parseInt(s.replaceAll("[^0-9].*",""));}catch(Exception e){return 0;}}
}