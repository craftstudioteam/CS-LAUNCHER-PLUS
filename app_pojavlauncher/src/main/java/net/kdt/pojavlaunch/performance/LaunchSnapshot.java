package net.kdt.pojavlaunch.performance;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Immutable, one-scan view of inputs used by a single Minecraft launch. */
public final class LaunchSnapshot {
    public final MinecraftProfile profile;
    public final File gameDirectory;
    public final String versionId;
    public final String runtimeName;
    public final String rendererId;
    public final String accountName;
    public final String accountType;
    public final File controlFile;
    public final String controlFileFingerprint;
    public final Set<String> modFileNames;
    public final String modDirectoryFingerprint;

    private LaunchSnapshot(MinecraftProfile profile, File gameDirectory, String versionId,
                           String runtimeName, String rendererId, String accountName,
                           String accountType, File controlFile, Set<String> mods,
                           String fingerprint) {
        this.profile=profile;this.gameDirectory=gameDirectory;this.versionId=versionId;
        this.runtimeName=runtimeName;this.rendererId=rendererId;this.accountName=accountName;
        this.accountType=accountType;this.controlFile=controlFile;
        this.controlFileFingerprint=controlFile==null?"none":controlFile.getAbsolutePath()+":"+controlFile.length()+":"+controlFile.lastModified();
        this.modFileNames=Collections.unmodifiableSet(mods);
        this.modDirectoryFingerprint=fingerprint;
    }

    public static LaunchSnapshot create(@NonNull MinecraftProfile profile,
                                        @NonNull File gameDirectory,
                                        @NonNull String versionId,
                                        @NonNull String runtimeName,
                                        @Nullable String rendererId,
                                        @Nullable MinecraftAccount account,
                                        @Nullable File controlFile) {
        HashSet<String> mods=new HashSet<>();StringBuilder fingerprint=new StringBuilder();
        File[] files=new File(gameDirectory,"mods").listFiles(f->f.isFile()&&f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if(files!=null){java.util.Arrays.sort(files,(a,b)->a.getName().compareToIgnoreCase(b.getName()));for(File f:files){String name=f.getName().toLowerCase(Locale.ROOT);mods.add(name);fingerprint.append(name).append(':').append(f.length()).append(':').append(f.lastModified()).append('\n');}}
        String accountType=account==null?"none":account.isMicrosoft?"microsoft":account.isElyByAccount()?"elyby":"local";
        return new LaunchSnapshot(profile,gameDirectory,versionId,runtimeName,rendererId,
                account==null?null:account.username,accountType,controlFile,mods,sha256(fingerprint.toString()));
    }

    public boolean matchesGameDirectory(File dir){return dir!=null&&gameDirectory.equals(dir);}
    public boolean containsAny(String...needles){if(needles==null)return false;for(String file:modFileNames)for(String needle:needles)if(needle!=null&&file.contains(needle.toLowerCase(Locale.ROOT)))return true;return false;}
    private static String sha256(String value){try{MessageDigest d=MessageDigest.getInstance("SHA-256");byte[]x=d.digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:x)b.append(String.format(Locale.ROOT,"%02x",v));return b.toString();}catch(Exception e){return Integer.toHexString(value.hashCode());}}
}
