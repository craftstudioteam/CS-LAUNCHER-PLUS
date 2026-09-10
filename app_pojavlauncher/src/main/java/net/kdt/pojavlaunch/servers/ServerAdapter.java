package net.kdt.pojavlaunch.servers;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import net.kdt.pojavlaunch.R;
import java.util.List;

/**
 * Server Hub list adapter.
 *
 * <p>Two view types: the highlighted built-in card (artwork header, server icon, Discord
 * button, no delete) and the regular server card.
 */
public class ServerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_NORMAL = 0;
    private static final int TYPE_FEATURED = 1;

    public interface Listener {
        void onJoin(ServerEntry e);
        void onRemove(ServerEntry e);
        void onEdit(ServerEntry e);
    }

    private final List<ServerEntry> data;
    private final Listener listener;
    public ServerAdapter(List<ServerEntry> data, Listener l){ this.data=data; this.listener=l; }

    @Override public int getItemViewType(int position) {
        ServerEntry e = data.get(position);
        // A built-in gets the featured card; a user server that happens to be pinned by an
        // older build still gets it too, so nothing can show up as an empty normal card.
        return (e.pinned || FeaturedServers.is(e.address)) ? TYPE_FEATURED : TYPE_NORMAL;
    }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int viewType){
        LayoutInflater inflater = LayoutInflater.from(p.getContext());
        if (viewType == TYPE_FEATURED) {
            FeaturedVH h = new FeaturedVH(inflater.inflate(R.layout.item_server_featured, p, false));
            net.kdt.pojavlaunch.UiMotion.pressTiltFeedback(h.itemView);
            net.kdt.pojavlaunch.UiMotion.pressFeedback(h.btnPlay, h.btnDiscord);
            return h;
        }
        VH h = new VH(inflater.inflate(R.layout.item_server_card, p, false));
        net.kdt.pojavlaunch.UiMotion.pressFeedback(h.itemView, h.btnJoin, h.btnRemove, h.btnEdit);
        return h;
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos){
        ServerEntry e = data.get(pos);
        if (holder instanceof FeaturedVH) bindFeatured((FeaturedVH) holder, e, pos);
        else bindNormal((VH) holder, e, pos);
    }

    // ─────────────────────────── highlighted card ───────────────────────────

    private void bindFeatured(FeaturedVH h, ServerEntry e, int pos) {
        FeaturedServers.Item item = FeaturedServers.find(e.address);
        h.name.setText(e.name != null && !e.name.isEmpty()
                ? e.name : (item != null ? item.name : e.address));
        h.address.setText(e.getJoinAddress());
        h.motd.setText(e.online || (e.motdRaw != null && !e.motdRaw.isEmpty())
                ? MotdRenderer.render(e)
                : (item != null ? item.tagline : ""));

        // This server's own banner artwork, not a shared decoration.
        if (h.banner != null && item != null && item.bannerRes != 0) h.banner.setImageResource(item.bannerRes);

        // Live favicon when the ping brought one, otherwise the bundled server mark.
        Bitmap icon = e.decodeIcon();
        if (icon != null) h.icon.setImageBitmap(icon);
        else h.icon.setImageResource(item != null && item.logoRes != 0
                ? item.logoRes : R.drawable.ic_server_leaf);

        if (e.online && e.playersOnline >= 0) {
            h.players.setVisibility(View.VISIBLE);
            net.kdt.pojavlaunch.UiMotion.countUp(h.players, e.playersOnline, 750, "\u25cf ", " online");
        } else h.players.setVisibility(View.GONE);

        if (e.online && e.pingMs >= 0) {
            h.ping.setText(e.pingMs + " ms");
            h.ping.setTextColor(pingColor(e.pingMs));
        } else {
            h.ping.setText("connecting\u2026");
            h.ping.setTextColor(0xFFFFD166);
        }

        if (e.versionName != null && !e.versionName.isEmpty()) {
            h.version.setVisibility(View.VISIBLE);
            h.version.setText(e.versionName);
        } else h.version.setVisibility(View.GONE);

        h.btnPlay.setOnClickListener(v -> {
            ServerPlayDialog.press(v);
            if (listener != null) listener.onJoin(e);
        });
        // Only servers that actually have a community link get the button - a button that
        // opens nothing is worse than no button.
        final String discordUrl = item != null ? item.discordUrl : null;
        if (discordUrl == null || discordUrl.isEmpty()) {
            h.btnDiscord.setVisibility(View.GONE);
        } else {
            h.btnDiscord.setVisibility(View.VISIBLE);
            h.btnDiscord.setOnClickListener(v -> {
                ServerPlayDialog.press(v);
                ServerPlayDialog.openDiscord(v.getContext(), discordUrl);
            });
        }
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onJoin(e); });

        // ── entrance: card lifts in, icon pops, body fades up ──
        h.itemView.setAlpha(0f);
        h.itemView.setTranslationY(14f);
        h.itemView.setScaleX(0.985f);
        h.itemView.setScaleY(0.985f);
        h.itemView.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setInterpolator(new DecelerateInterpolator()).setDuration(340).start();
        if (h.iconCard != null) {
            h.iconCard.setScaleX(0.6f); h.iconCard.setScaleY(0.6f); h.iconCard.setAlpha(0f);
            h.iconCard.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setStartDelay(120).setDuration(420)
                    .setInterpolator(new OvershootInterpolator(2.2f)).start();
        }
        fadeUp(h.name, 160);
        fadeUp(h.address, 190);
        fadeUp(h.motd, 220);
        fadeUp(h.btnPlay, 260);
        fadeUp(h.btnDiscord, 290);

        startShine(h.shine);
        startGlow(h.glow);
    }

    private static void fadeUp(View v, long delay) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        v.setAlpha(0f);
        v.setTranslationY(10f);
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay)
                .setInterpolator(new DecelerateInterpolator()).setDuration(300).start();
    }

    private static int pingColor(long ping) {
        if (ping < 120) return 0xFFB6F5C6;
        if (ping < 260) return 0xFFFFE28A;
        return 0xFFFFB3B3;
    }

    /** Light sweep travelling across the artwork, restarted every few seconds. */
    private void startShine(View shine) {
        if (shine == null) return;
        shine.animate().cancel();
        shine.post(() -> {
            int width = ((View) shine.getParent()).getWidth();
            if (width <= 0) return;
            sweep(shine, width);
        });
    }

    private void sweep(View shine, int width) {
        if (!shine.isAttachedToWindow()) return;
        shine.setTranslationX(-shine.getWidth() - 20f);
        shine.setAlpha(0f);
        shine.animate().alpha(1f).setDuration(220).start();
        shine.animate().translationX(width + shine.getWidth())
                .setStartDelay(120).setDuration(1500)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    shine.setAlpha(0f);
                    shine.postDelayed(() -> sweep(shine, width), 2600);
                }).start();
    }

    /** Slow breathing gold rim. */
    private void startGlow(View glow) {
        if (glow == null) return;
        glow.animate().cancel();
        glow.setAlpha(0.35f);
        pulse(glow);
    }

    private void pulse(View glow) {
        if (!glow.isAttachedToWindow()) return;
        glow.animate().alpha(0.95f).setStartDelay(0).setDuration(1500)
                .withEndAction(() -> glow.animate().alpha(0.3f).setDuration(1500)
                        .withEndAction(() -> pulse(glow)).start())
                .start();
    }

    // ─────────────────────────── regular card ───────────────────────────

    private void bindNormal(@NonNull VH h, ServerEntry e, int pos){
        h.name.setText(e.name != null && !e.name.isEmpty() ? e.name : e.address);
        h.address.setText(e.address);

        // ── Coloured MOTD, rendered exactly like the vanilla multiplayer screen ──
        h.motd.setText(MotdRenderer.render(e));
        h.motd.setMaxLines(2);
        h.motd.setEllipsize(android.text.TextUtils.TruncateAt.END);

        if (e.online && e.playersOnline >= 0) {
            h.players.setVisibility(View.VISIBLE);
            net.kdt.pojavlaunch.UiMotion.countUp(h.players, e.playersOnline, 750, "",
                    "/" + (e.playersMax>=0?String.valueOf(e.playersMax):"\u221e"));
        } else h.players.setVisibility(View.GONE);

        int[] barIds = {R.id.ping_bar_1, R.id.ping_bar_2, R.id.ping_bar_3, R.id.ping_bar_4, R.id.ping_bar_5};
        h.pingContainer.setVisibility(View.VISIBLE);

        if (e.online && e.pingMs >= 0) {
            // Views are recycled: every property set in the offline branch must be undone here.
            h.ping.setText(e.pingMs + " ms");
            h.ping.setTextColor(0xFF9AA3B2);
            h.offlineText.setVisibility(View.GONE);
            int bars = getBars(e.pingMs);
            int filledColor = 0xFF62D99B;
            int emptyColor = 0xFF3A404C;
            if (bars <= 2) filledColor = 0xFFFF6B6B;
            else if (bars <= 3) filledColor = 0xFFFFD166;
            for (int i=0;i<5;i++) {
                View bar = h.itemView.findViewById(barIds[i]);
                if (bar != null) bar.setBackgroundColor(i < bars ? filledColor : emptyColor);
            }
            h.btnJoin.setAlpha(1f); h.btnJoin.setEnabled(true);
            h.btnJoin.setText("PLAY");
        } else {
            h.ping.setText("Offline");
            h.ping.setTextColor(0xFFFF6B6B);
            for (int id : barIds) {
                View bar = h.itemView.findViewById(id);
                if (bar != null) bar.setBackgroundColor(0xFF3A404C);
            }
            h.offlineText.setVisibility(View.VISIBLE);
            h.offlineText.setText("Offline");
            h.motd.setText(e.motdRaw != null && !e.motdRaw.isEmpty()
                    ? MotdRenderer.render(e)
                    : "Server offline \u2014 tap refresh to retry");
            h.players.setVisibility(View.GONE);
            // Offline servers can still be queued: Minecraft may reach a host the phone cannot.
            h.btnJoin.setAlpha(0.75f); h.btnJoin.setEnabled(true);
            h.btnJoin.setText("JOIN");
        }

        if (e.versionName != null && !e.versionName.isEmpty()) {
            h.version.setVisibility(View.VISIBLE); h.version.setText(e.versionName);
        } else h.version.setVisibility(View.GONE);
        h.dot.setVisibility(e.online ? View.VISIBLE : View.GONE);
        h.dot.setBackgroundResource(R.drawable.bg_server_online_dot);
        Bitmap icon = e.decodeIcon();
        if (icon != null) { h.icon.setImageBitmap(icon); h.icon.setPadding(0,0,0,0); }
        else h.icon.setImageResource(R.drawable.ic_globe);
        h.btnJoin.setOnClickListener(v -> {
            ServerPlayDialog.press(v);
            if (!e.online) Toast.makeText(v.getContext(), "Server did not answer the ping \u2014 you can still join", Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onJoin(e);
        });
        h.btnRemove.setVisibility(View.VISIBLE);
        h.btnRemove.setOnClickListener(v -> { if(listener!=null) listener.onRemove(e); });
        if (h.btnEdit != null) {
            h.btnEdit.setOnClickListener(v -> { if(listener!=null) listener.onEdit(e); });
        }
        h.itemView.setOnClickListener(v -> { if(listener!=null) listener.onEdit(e); });
        // entrance
        h.itemView.setAlpha(0f); h.itemView.setTranslationY(8f);
        h.itemView.animate().alpha(1f).translationY(0f).setStartDelay(pos*35L).setDuration(220).start();
    }

    private int getBars(long ping) {
        if (ping < 0) return 0;
        if (ping < 80) return 5;
        if (ping < 150) return 4;
        if (ping < 300) return 3;
        if (ping < 500) return 2;
        return 1;
    }
    @Override public int getItemCount(){ return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView icon; View dot; TextView name, address, motd, ping, players, version, offlineText, btnJoin;
        View btnRemove, btnEdit; View pingContainer;
        VH(View v){ super(v);
            icon=v.findViewById(R.id.server_icon); dot=v.findViewById(R.id.server_online_dot);
            name=v.findViewById(R.id.server_name); address=v.findViewById(R.id.server_address);
            motd=v.findViewById(R.id.server_motd); ping=v.findViewById(R.id.server_ping);
            players=v.findViewById(R.id.server_players); version=v.findViewById(R.id.server_version);
            offlineText=v.findViewById(R.id.server_offline_text);
            pingContainer=v.findViewById(R.id.server_ping_container);
            btnJoin=v.findViewById(R.id.server_btn_join); btnRemove=v.findViewById(R.id.server_btn_remove);
            btnEdit=v.findViewById(R.id.server_btn_edit);
        }
    }

    static class FeaturedVH extends RecyclerView.ViewHolder {
        TextView name, address, motd, ping, players, version, btnPlay, btnDiscord;
        ImageView icon, banner;
        View glow, shine, iconCard;
        FeaturedVH(View v){ super(v);
            name=v.findViewById(R.id.fs_name); address=v.findViewById(R.id.fs_address);
            motd=v.findViewById(R.id.fs_motd); ping=v.findViewById(R.id.fs_ping);
            players=v.findViewById(R.id.fs_players); version=v.findViewById(R.id.fs_version);
            btnPlay=v.findViewById(R.id.fs_btn_play); btnDiscord=v.findViewById(R.id.fs_btn_discord);
            icon=v.findViewById(R.id.fs_icon); banner=v.findViewById(R.id.fs_banner);
            iconCard=v.findViewById(R.id.fs_icon_card);
            glow=v.findViewById(R.id.fs_glow); shine=v.findViewById(R.id.fs_shine);
        }
    }
}
