package net.kdt.pojavlaunch.csclient;

import java.util.ArrayList;
import java.util.List;

/** In-memory draft shared by CS Client builder and its picker pages. */
public final class CsClientSelection {
    public static final List<CsClientCatalog.ProjectEntry> mods = new ArrayList<>();
    public static CsClientCatalog.ProjectEntry modpack;
    private CsClientSelection() {}
    public static void clear(){mods.clear();modpack=null;}
    public static boolean contains(String id){for(CsClientCatalog.ProjectEntry e:mods)if(e.id.equals(id))return true;return false;}
    public static void toggleMod(CsClientCatalog.ProjectEntry e){for(int i=0;i<mods.size();i++)if(mods.get(i).id.equals(e.id)){mods.remove(i);return;}mods.add(e);}
}