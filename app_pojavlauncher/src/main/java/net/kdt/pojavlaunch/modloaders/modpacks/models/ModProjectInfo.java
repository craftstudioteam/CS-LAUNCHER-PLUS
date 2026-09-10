package net.kdt.pojavlaunch.modloaders.modpacks.models;

import java.io.Serializable;

/**
 * Rich project metadata for the install page (Modrinth /project or
 * Curseforge /mods/{id}). All fields are optional — any value the source
 * API does not provide stays null (or -1 for numeric stats) and the UI
 * hides the corresponding section.
 */
public class ModProjectInfo implements Serializable {
    public String title;
    public String author;
    /** Short plain-text tagline */
    public String tagline;
    /** Full markdown description body (Modrinth "body", CF summary fallback) */
    public String body;
    public long downloads = -1;
    public long followers = -1;
    public String license;
    /** ISO-8601 date of the last project update */
    public String updatedIso;
    public String[] categories;
    public String[] loaders;
    public String[] gameVersions;
    /** Screenshot / gallery image URLs */
    public String[] galleryUrls;
    public String websiteUrl;
    public String sourceUrl;
    public String issuesUrl;
    public String wikiUrl;
    public String discordUrl;
}
