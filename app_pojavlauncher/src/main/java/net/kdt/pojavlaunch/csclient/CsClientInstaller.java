package net.kdt.pojavlaunch.csclient;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.modloaders.FabricVersion;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Atomic CS Client profile installer. */
public final class CsClientInstaller {
    public interface Callback { void onStatus(String text,int percent); void onDone(boolean ok,String message); }
    private CsClientInstaller(){}

    public static void install(Context context, CsClientCatalog.VersionEntry blaze,
                               List<CsClientCatalog.ProjectEntry> mods,
                               CsClientCatalog.ProjectEntry pack, Callback cb){
        PojavApplication.sExecutorService.execute(()->run(context,blaze,mods,pack,cb));
    }

    private static void run(Context ctx,CsClientCatalog.VersionEntry blaze,List<CsClientCatalog.ProjectEntry> mods,CsClientCatalog.ProjectEntry pack,Callback cb){
        String token="cs_client_"+blaze.minecraft.replaceAll("[^A-Za-z0-9._-]","_")+"_"+UUID.randomUUID().toString().substring(0,8);
        File tmp=new File(Tools.DIR_GAME_HOME,"custom_instances/.building_"+token);
        File dest=new File(Tools.DIR_GAME_HOME,"custom_instances/"+token);
        try{
            status(cb,"Preparing isolated profile…",3);tmp.mkdirs();new File(tmp,"mods").mkdirs();
            status(cb,"Resolving Fabric Loader for "+blaze.minecraft+"…",10);
            FabricVersion[] loaders=FabriclikeUtils.FABRIC_UTILS.downloadLoaderVersions(blaze.minecraft);
            if(loaders==null||loaders.length==0)throw new Exception("No Fabric Loader for "+blaze.minecraft);
            String loader=null;for(FabricVersion candidate:loaders)if(candidate.stable&&versionAtLeast(candidate.version,blaze.minimumLoader)){loader=candidate.version;break;}if(loader==null)throw new Exception("CS Client requires Fabric Loader "+blaze.minimumLoader+" or newer");
            String profileJson=net.kdt.pojavlaunch.utils.DownloadUtils.downloadString(FabriclikeUtils.FABRIC_UTILS.createJsonDownloadUrl(blaze.minecraft,loader));
            String versionId=new JSONObject(profileJson).getString("id");File versionDir=new File(Tools.DIR_HOME_VERSION,versionId);versionDir.mkdirs();Tools.write(new File(versionDir,versionId+".json").getAbsolutePath(),profileJson);

            if(pack!=null){status(cb,"Merging modpack: "+pack.title+"…",22);CsClientCatalog.FileEntry f=CsClientCatalog.latestFile(pack.id,blaze.minecraft,true);File mr=new File(Tools.DIR_CACHE,"csclient_pack.mrpack");download(f,mr);new ModrinthApi().installMrpack(mr,tmp,(current,total)->{int pct=22+(int)(18f*current/Math.max(1,total));status(cb,"Merging modpack: "+pack.title+"…",pct);});mr.delete();}

            status(cb,"Installing bundled CS Client…",48);CsClientManagedFiles.installCore(ctx,blaze,tmp);
            status(cb,"Installing Fabric API for "+blaze.minecraft+"…",60);removeMatching(new File(tmp,"mods"),"fabric-api");CsClientCatalog.FileEntry api=CsClientCatalog.latestFile(CsClientCatalog.FABRIC_API_PROJECT,blaze.minecraft,false);download(api,new File(tmp,"mods/"+api.name));
            int index=0;for(CsClientCatalog.ProjectEntry mod:mods){index++;status(cb,"Installing mod "+index+"/"+mods.size()+": "+mod.title,60+(int)(25f*index/Math.max(1,mods.size())));CsClientCatalog.FileEntry f=CsClientCatalog.latestFile(mod.id,blaze.minecraft,false);download(f,new File(tmp,"mods/"+f.name));}

            status(cb,"Finalizing CS Client profile…",92);dest.getParentFile().mkdirs();if(!tmp.renameTo(dest))throw new Exception("Could not finalize instance directory");
            LauncherProfiles.load();String key=LauncherProfiles.getFreeProfileKey();MinecraftProfile p=MinecraftProfile.createTemplate();p.name="CS Client • "+blaze.minecraft;p.type="custom";p.lastVersionId=versionId;p.gameDir="./custom_instances/"+token;p.icon=encodeLogo(ctx);p.background=net.kdt.pojavlaunch.profiles.ProfileGifSupport.DEFAULT_PROFILE_BG_URL;
            String now=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US).format(new java.util.Date());p.created=now;p.lastUsed=now;LauncherProfiles.mainProfileJson.profiles.put(key,p);LauncherProfiles.write();LauncherPreferences.DEFAULT_PREF.edit().putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE,key).commit();
            done(cb,true,"CS Client • "+blaze.minecraft+" created");
        }catch(Throwable e){try{org.apache.commons.io.FileUtils.deleteDirectory(tmp);}catch(Exception ignored){}try{org.apache.commons.io.FileUtils.deleteDirectory(dest);}catch(Exception ignored){}done(cb,false,e.getMessage()==null?"CS Client installation failed":e.getMessage());}
    }
    private static void download(CsClientCatalog.FileEntry f,File out)throws Exception{out.getParentFile().mkdirs();Tools.downloadFile(f.url,out.getAbsolutePath());if(f.sha1!=null&&!Tools.compareSHA1(out,f.sha1)){out.delete();throw new Exception("File verification failed: "+f.name);}}
    private static void removeMatching(File dir,String needle){File[]a=dir.listFiles();if(a!=null)for(File f:a)if(f.getName().toLowerCase().contains(needle))f.delete();}
    private static boolean versionAtLeast(String actual,String minimum){try{String[]a=actual.split("\\."),m=minimum.split("\\.");for(int i=0;i<Math.max(a.length,m.length);i++){int x=i<a.length?Integer.parseInt(a[i].replaceAll("[^0-9].*","")):0,y=i<m.length?Integer.parseInt(m[i].replaceAll("[^0-9].*","")):0;if(x!=y)return x>y;}return true;}catch(Exception e){return actual.compareTo(minimum)>=0;}}
    private static String encodeLogo(Context c){try{Bitmap b=BitmapFactory.decodeResource(c.getResources(),R.drawable.cs_logo);ByteArrayOutputStream o=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,o);b.recycle();return"data:image/png;base64,"+Base64.encodeToString(o.toByteArray(),Base64.NO_WRAP);}catch(Throwable e){return"default";}}
    private static void status(Callback cb,String s,int pct){Tools.runOnUiThread(()->cb.onStatus(s,pct));}
    private static void done(Callback cb,boolean ok,String s){Tools.runOnUiThread(()->cb.onDone(ok,s));}
}