package net.kdt.pojavlaunch;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.NonNull;

/**
 * Phase 8 — the launcher's community links in ONE place.
 *
 * <p>The About page, the CS Client promo and now the home brand cluster all
 * point at the same channels; keeping the URLs here means one edit updates
 * every button. {@link #open} tries the native app first (the YouTube /
 * Discord app claims these URLs) and falls back to the browser.
 */
public final class CsLinks {

    /** Same invite as {@code AboutFragment}. */
    public static final String DISCORD = "https://discord.gg/bpgYQMA59D";
    /** Same channel as {@code CsClientPromoDialog}. */
    public static final String YOUTUBE = "https://youtube.com/@craft-studio-official";
    public static final String WEBSITE = "https://cs-launchel.vercel.app/";
    public static final String GITHUB = "https://github.com/craftstudioteam";

    private CsLinks() {}

    public static void open(@NonNull Context ctx, @NonNull String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, "No app can open this link", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "Could not open link", Toast.LENGTH_SHORT).show();
        }
    }
}
