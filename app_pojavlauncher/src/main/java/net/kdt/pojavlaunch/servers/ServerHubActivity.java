package net.kdt.pojavlaunch.servers;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The Server Hub: the launcher's own server list.
 *
 * <p>What is on screen is real, and there is nothing else to it: the built-in servers from
 * {@link FeaturedServers} plus this profile's saved servers (one JSON list in the launcher's own
 * preferences, shared with the game via {@code servers.dat}), live status/MOTD/favicon from
 * {@link ServerPinger}'s own status-handshake pings, and a join path that hands the address to the
 * launcher process as a pending quick-play. No mock rows, no decorative buttons, no
 * "coming soon" placeholders — the Discord button on a card only exists for servers that actually
 * have an invite link, for example.
 *
 * <p>Opened from the profile editor (which passes its own profile key) so "Play now" joins from
 * the profile whose settings you are looking at, not from whatever the launcher last ran.
 */
public class ServerHubActivity extends AppCompatActivity implements ServerAdapter.Listener {

    /** Optional: which profile this hub was opened for (and which one PLAY will launch). */
    public static final String EXTRA_PROFILE_KEY = "cs_hub_profile_key";

    private static final String TAG = "ServerHub";
    /** A hostname label, or a bracketed IPv6 literal — enough to reject typos, not to be clever. */
    private static final Pattern HOST_OK = Pattern.compile("[a-z0-9._\\-]{2,255}");
    private static final Pattern HOST_V6 = Pattern.compile("\\[[0-9a-f:.]{3,63}]");
    /** au_danger, kept here because it is set on a TextView that also shows the neutral hint. */
    private static final int COLOR_ERROR = 0xFFE09A96;

    private final List<ServerEntry> mServers = new ArrayList<>();
    private ServerAdapter mAdapter;
    private TextView mStatus;
    private String mProfileKey = "default";
    /** Guards against a reply from a superseded ping round touching the list after a reload. */
    private long mRound;
    private int mPendingPings;
    /**
     * Stores are written from the UI thread otherwise: the list lives in a file shared with the
     * game process, and a status reply arriving for every row means several writes in a row, in
     * the exact moment the list is animating. One serial executor keeps the order and the frame.
     */
    private final java.util.concurrent.ExecutorService mWriter =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle savedState) {
        super.onCreate(savedState);
        setContentView(R.layout.activity_server_hub);

        mProfileKey = resolveProfileKey(getIntent().getStringExtra(EXTRA_PROFILE_KEY));

        mStatus = findViewById(R.id.hub_status);
        ((TextView) findViewById(R.id.hub_subtitle))
                .setText(getString(R.string.hub_subtitle, mProfileKey));

        RecyclerView list = findViewById(R.id.hub_list);
        mAdapter = new ServerAdapter(mServers, this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setHasFixedSize(false);
        list.setAdapter(mAdapter);
        UiMotion.revealList(list);

        View back = findViewById(R.id.hub_back);
        View add = findViewById(R.id.hub_add);
        View refresh = findViewById(R.id.hub_refresh);
        back.setOnClickListener(v -> {
            ServerPlayDialog.press(v);
            finish();
        });
        add.setOnClickListener(v -> {
            ServerPlayDialog.press(v);
            showEditor(null);
        });
        refresh.setOnClickListener(v -> {
            ServerPlayDialog.press(v);
            reload();
            pingAll();
        });
        UiMotion.pressFeedback(back, add, refresh);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read on every visit: the in-game multiplayer list can add and drop entries while the
        // launcher was in the background, and the launcher is the file's other writer.
        reload();
        pingAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mRound++; // in-flight pings may still be stored, they must not repaint a hidden screen
        mPendingPings = 0;
    }

    // ─────────────────────────── list plumbing ───────────────────────────

    /**
     * The profile the hub reads and launches: the one the caller passed, otherwise the launcher's
     * current profile. "default" is the profile key Pojav uses when nothing was ever selected, so
     * there is no state in which the hub has no list to show.
     */
    private static String resolveProfileKey(@Nullable String fromIntent) {
        if (!TextUtils.isEmpty(fromIntent)) return fromIntent;
        try {
            String key = LauncherPreferences.DEFAULT_PREF.getString(
                    LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "default");
            return TextUtils.isEmpty(key) ? "default" : key;
        } catch (Throwable t) {
            return "default";
        }
    }

    private void reload() {
        mServers.clear();
        try {
            mServers.addAll(ServerStore.load(this, mProfileKey));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "server list load failed", t);
        }
        mAdapter.notifyDataSetChanged();
        if (mPendingPings <= 0) {
            setStatus(mServers.isEmpty()
                    ? getString(R.string.hub_status_empty)
                    : getString(R.string.hub_status_idle, mServers.size()));
        }
    }

    /**
     * One status handshake per row, on the pinger's own threads. They run concurrently because a
     * dead server costs the full read timeout, and five of them in series is a spinner you stare
     * at for thirty seconds.
     */
    private void pingAll() {
        if (mServers.isEmpty()) return;
        final long round = ++mRound;
        final List<ServerEntry> targets = new ArrayList<>(mServers);
        mPendingPings = targets.size();
        setStatus(getString(R.string.hub_status_pinging, targets.size()));
        for (ServerEntry entry : targets) {
            ServerPinger.pingAsync(entry, new ServerPinger.Callback() {
                @Override
                public void onResult(ServerEntry done) {
                    onPinged(round, done);
                }

                @Override
                public void onError(ServerEntry done, Exception e) {
                    onPinged(round, done);
                }
            });
        }
    }

    private void onPinged(final long round, final ServerEntry entry) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || round != mRound) return;
            int idx = mServers.indexOf(entry);
            if (idx >= 0) mAdapter.notifyItemChanged(idx);
            // Stored per reply rather than once at the end: when the network dies halfway, the
            // rows that did answer keep their players, MOTD and favicon. The snapshot keeps the
            // write off the main thread without letting it read a list that is still changing.
            persist();
            if (--mPendingPings <= 0) {
                mPendingPings = 0;
                setStatus(getString(R.string.hub_status_updated, mServers.size(),
                        new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date())));
            }
        });
    }

    private void persist() {
        final List<ServerEntry> snapshot = new ArrayList<>(mServers);
        final String key = mProfileKey;
        final android.content.Context app = getApplicationContext();
        mWriter.execute(() -> {
            try {
                ServerStore.save(app, key, snapshot);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "server list save failed", t);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mWriter.shutdown(); // queued writes still run to completion, nothing is dropped
    }

    private void setStatus(String text) {
        mStatus.setText(text);
    }

    private static String labelOf(ServerEntry e) {
        return e.name != null && !e.name.isEmpty() ? e.name : e.address;
    }

    // ─────────────────────────── list actions ───────────────────────────

    @Override
    public void onJoin(final ServerEntry entry) {
        ServerPlayDialog.show(this, entry, new ServerPlayDialog.Action() {
            @Override
            public void onPlayNow(ServerEntry e) {
                ServerStore.add(ServerHubActivity.this, mProfileKey, e);
                launch(e);
            }

            @Override
            public void onSaveOnly(ServerEntry e) {
                ServerStore.add(ServerHubActivity.this, mProfileKey, e);
                syncListIntoGameDir();
                reload();
                Toast.makeText(ServerHubActivity.this,
                        getString(R.string.hub_added, labelOf(e)), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onRemove(ServerEntry entry) {
        if (!ServerStore.remove(this, mProfileKey, entry.address)) {
            // A built-in is not deletable; saying so beats silently doing nothing.
            Toast.makeText(this, R.string.hub_built_in_locked, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, getString(R.string.hub_removed, labelOf(entry)),
                Toast.LENGTH_SHORT).show();
        syncListIntoGameDir();
        reload();
        pingAll();
    }

    @Override
    public void onEdit(ServerEntry entry) {
        showEditor(entry);
    }

    /**
     * The hand-off that makes "Play now" actually join: queue the address for this profile, make
     * sure the profile's {@code servers.dat} contains it, then start the launcher pointed at this
     * profile. {@code Tools} appends {@code --quickPlayMultiplayer} from the queued address, which
     * is also why the profile key is made current first — the launch path reads the pending
     * address for the *current* profile.
     */
    private void launch(ServerEntry entry) {
        try {
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, mProfileKey)
                    .apply();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "could not set current profile", t);
        }
        ServerStore.setPending(this, mProfileKey, entry.address);
        syncListIntoGameDir();
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_PROFILE_KEY, mProfileKey);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(intent);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "launch request failed", t);
            Toast.makeText(this, R.string.hub_launch_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void syncListIntoGameDir() {
        try {
            ServerListSync.syncToGameDir(this, mProfileKey);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "servers.dat sync failed", t);
        }
    }

    // ─────────────────────────── add / edit ───────────────────────────

    private void showEditor(@Nullable final ServerEntry existing) {
        View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_hub_add_server, null, false);
        final TextView title = content.findViewById(R.id.hub_add_title);
        final TextView hint = content.findViewById(R.id.hub_add_error);
        final EditText addressField = content.findViewById(R.id.hub_add_address);
        final EditText nameField = content.findViewById(R.id.hub_add_name);

        if (existing != null) {
            title.setText(R.string.hub_edit_title);
            addressField.setText(existing.address);
            addressField.setSelection(addressField.getText().length());
            nameField.setText(existing.name == null || existing.name.equals(existing.address)
                    ? "" : existing.name);
        }

        // Phase 8: a real card popup. The old AlertDialog wrapped the left-rounded
        // sheet drawable in the stock white-cornered window — that was the
        // "broken add card". Transparent window + full-rounded card + motion.
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);
        Window dw = dialog.getWindow();
        if (dw != null) {
            dw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            dw.setDimAmount(0.62f);
            dw.setWindowAnimations(0);
        }
        content.findViewById(R.id.hub_add_cancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.hub_add_save).setOnClickListener(v -> {
            String address = addressField.getText().toString().trim();
            String error = validateAddress(address, existing);
            if (error != null) {
                hint.setText(error);
                hint.setTextColor(COLOR_ERROR);
                net.kdt.pojavlaunch.Anime.shake(addressField);
                return;
            }
            String customName = nameField.getText().toString().trim();
            if (existing == null) {
                ServerEntry entry = new ServerEntry(address);
                if (!customName.isEmpty()) entry.name = customName;
                ServerStore.add(this, mProfileKey, entry);
                Toast.makeText(this, getString(R.string.hub_added, labelOf(entry)),
                        Toast.LENGTH_SHORT).show();
            } else {
                saveEdit(existing, address, customName);
                Toast.makeText(this, getString(R.string.hub_saved, labelOf(existing)),
                        Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
            syncListIntoGameDir();
            reload();
            pingAll();
        });

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            float d = getResources().getDisplayMetrics().density;
            int width = (int) Math.min(440 * d, getResources().getDisplayMetrics().widthPixels - 32 * d);
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(android.view.Gravity.CENTER);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        // Entrance: card outBack, fields fade-up in sequence.
        content.setAlpha(0f); content.setScaleX(0.9f); content.setScaleY(0.9f);
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(net.kdt.pojavlaunch.Anime.OUT_BACK).withLayer().start();
        net.kdt.pojavlaunch.Anime.in(title, net.kdt.pojavlaunch.Anime.Fx.FADE_LEFT, 120, 460, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        net.kdt.pojavlaunch.Anime.in(addressField, net.kdt.pojavlaunch.Anime.Fx.FADE_UP, 180, 460, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        net.kdt.pojavlaunch.Anime.in(nameField, net.kdt.pojavlaunch.Anime.Fx.FADE_UP, 240, 460, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        net.kdt.pojavlaunch.Anime.in(content.findViewById(R.id.hub_add_save), net.kdt.pojavlaunch.Anime.Fx.FADE_UP, 300, 460, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        net.kdt.pojavlaunch.Anime.in(content.findViewById(R.id.hub_add_cancel), net.kdt.pojavlaunch.Anime.Fx.FADE_UP, 300, 460, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        // Show the keyboard on the address field straight away — this dialog exists to type a
        // hostname, and hunting for the field first is the usual annoyance.
        addressField.requestFocus();
    }

    /**
     * Write an edit back by POSITION, not by {@link ServerStore#update}: the store finds the row
     * to replace by matching addresses, so an edit that renames {@code old.host} to
     * {@code new.host} — the single most likely reason to open this dialog — would match nothing
     * and be dropped. Locating the saved row by the address it had when the dialog opened keeps
     * the entry's place in the list and makes an address change actually stick.
     */
    private void saveEdit(ServerEntry edited, String newAddress, String newName) {
        String was = FeaturedServers.normalize(edited.address);
        edited.address = newAddress;
        edited.name = newName.isEmpty() ? newAddress : newName;
        try {
            List<ServerEntry> user = ServerStore.loadUserServers(this, mProfileKey);
            for (int i = 0; i < user.size(); i++) {
                ServerEntry s = user.get(i);
                if (s != null && s.address != null && FeaturedServers.normalize(s.address).equals(was)) {
                    user.set(i, edited);
                    ServerStore.save(this, mProfileKey, user);
                    return;
                }
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "edit write failed", t);
        }
        ServerStore.add(this, mProfileKey, edited); // not saved yet (added this session): insert it
    }

    /** @return an error message, or null when the address can be stored. */
    @Nullable
    private String validateAddress(@Nullable String raw, @Nullable ServerEntry self) {
        String bad = getString(R.string.hub_address_invalid);
        if (TextUtils.isEmpty(raw)) return bad;
        String a = FeaturedServers.normalize(raw);
        if (a.isEmpty() || a.indexOf(' ') >= 0) return bad;

        int afterBracket = a.lastIndexOf(']');
        int colon = a.indexOf(':', afterBracket + 1);
        String host = colon < 0 ? a : a.substring(0, colon);
        boolean v6 = host.startsWith("[");
        if (!(v6 ? HOST_V6 : HOST_OK).matcher(host).matches()) return bad;
        if (colon >= 0) {
            String port = a.substring(colon + 1);
            if (port.isEmpty() || port.indexOf(':') >= 0) return bad;
            int value;
            try {
                value = Integer.parseInt(port);
            } catch (NumberFormatException e) {
                return bad;
            }
            if (value < 1 || value > 65535) return bad;
        }
        if (FeaturedServers.is(a)) return getString(R.string.hub_address_builtin);
        for (ServerEntry s : mServers) {
            if (s == self || s.address == null) continue;
            if (FeaturedServers.normalize(s.address).equals(a)) {
                return getString(R.string.hub_address_taken);
            }
        }
        return null;
    }
}
