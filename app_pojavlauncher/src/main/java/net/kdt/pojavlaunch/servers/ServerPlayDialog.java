package net.kdt.pojavlaunch.servers;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.cardview.widget.CardView;

import net.kdt.pojavlaunch.R;

/**
 * The small "play or save?" popup that appears when a server's PLAY button is tapped.
 *
 * <p>Kept intentionally simple: one short sentence and two buttons. Partner servers additionally
 * get their banner artwork and a Discord button.
 */
public final class ServerPlayDialog {

    public interface Action {
        void onPlayNow(ServerEntry entry);
        void onSaveOnly(ServerEntry entry);
    }

    private ServerPlayDialog() {}

    public static void show(Context ctx, ServerEntry entry, Action action) {
        if (ctx == null || entry == null) return;

        View view = LayoutInflater.from(ctx).inflate(R.layout.dialog_server_play, null, false);
        AlertDialog dialog = new AlertDialog.Builder(ctx).setView(view).create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        final FeaturedServers.Item item = FeaturedServers.find(entry.address);
        boolean partner = entry.pinned || item != null;

        CardView bannerCard = view.findViewById(R.id.spd_banner_card);
        TextView title = view.findViewById(R.id.spd_title);
        TextView address = view.findViewById(R.id.spd_address);
        TextView message = view.findViewById(R.id.spd_message);
        TextView btnSave = view.findViewById(R.id.spd_btn_save);
        TextView btnPlay = view.findViewById(R.id.spd_btn_play);
        TextView btnDiscord = view.findViewById(R.id.spd_btn_discord);

        title.setText(entry.name != null && !entry.name.isEmpty() ? entry.name : entry.address);
        address.setText(entry.getJoinAddress());

        if (partner) {
            message.setText("Play now, or just save it to your list?");
            bannerCard.setVisibility(View.VISIBLE);
            android.widget.ImageView banner = view.findViewById(R.id.spd_banner);
            if (banner != null && item != null && item.bannerRes != 0) banner.setImageResource(item.bannerRes);
            if (item != null && item.discordUrl != null && !item.discordUrl.isEmpty()) {
                btnDiscord.setVisibility(View.VISIBLE);
                btnDiscord.setOnClickListener(v -> {
                    press(v);
                    openDiscord(ctx, item.discordUrl);
                });
            } else {
                btnDiscord.setVisibility(View.GONE);
            }
        } else {
            bannerCard.setVisibility(View.GONE);
            btnDiscord.setVisibility(View.GONE);
            message.setText(entry.online
                    ? "Play now, or just save it to your list?"
                    : "This server did not answer. Play anyway, or just save it?");
        }

        btnPlay.setOnClickListener(v -> {
            press(v);
            dialog.dismiss();
            if (action != null) action.onPlayNow(entry);
        });
        btnSave.setOnClickListener(v -> {
            press(v);
            dialog.dismiss();
            if (action != null) action.onSaveOnly(entry);
        });

        dialog.show();

        // Pop-in animation: small overshoot on the card, fade on the content.
        View root = view.findViewById(R.id.spd_root);
        if (root != null) {
            root.setAlpha(0f);
            root.setScaleX(0.88f);
            root.setScaleY(0.88f);
            root.setTranslationY(24f);
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setInterpolator(new OvershootInterpolator(1.1f))
                    .setDuration(320).start();
        }
        animateIn(view.findViewById(R.id.spd_banner_card), 60);
        animateIn(title, 110);
        animateIn(address, 140);
        animateIn(message, 170);
        animateIn(btnSave, 200);
        animateIn(btnPlay, 200);
        animateIn(btnDiscord, 240);
    }

    public static void openDiscord(Context ctx, String url) {
        if (ctx == null || url == null || url.isEmpty()) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(ctx, url, Toast.LENGTH_LONG).show();
        }
    }

    private static void animateIn(View v, long delay) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        v.setAlpha(0f);
        v.setTranslationY(14f);
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(delay)
                .setInterpolator(new DecelerateInterpolator())
                .setDuration(260).start();
    }

    static void press(View v) {
        v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(150).start())
                .start();
    }
}
