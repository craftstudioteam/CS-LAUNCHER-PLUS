package net.kdt.pojavlaunch.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Anime;
import net.kdt.pojavlaunch.R;

/**
 * Phase 9 — the in-game drawer adapter. Still an {@code ArrayAdapter<String>}
 * (MainActivity holds the field with that type and the ListView contract is
 * unchanged), but each row is now a full action card: icon well, title,
 * subtitle and an index key-cap. Row 0 (force close) uses the danger variant.
 *
 * Rows animate in with a stagger the first time they are drawn after
 * {@link #armEntrance()} — MainActivity calls that from the DrawerListener
 * so every drawer open replays the reveal.
 */
public class InGameMenuAdapter extends ArrayAdapter<String> {

    private final String[] mSubtitles;
    private final int[] mIcons;
    private final int mDangerIndex;
    private boolean mEntranceArmed = true;
    private long mArmedAt = 0L;

    public InGameMenuAdapter(@NonNull Context context, @NonNull String[] titles,
                             @NonNull String[] subtitles, @NonNull @DrawableRes int[] icons, int dangerIndex) {
        super(context, R.layout.item_ingame_menu_card, R.id.igm_title, titles);
        mSubtitles = subtitles;
        mIcons = icons;
        mDangerIndex = dangerIndex;
    }

    /** Call right before the drawer becomes visible so rows replay their stagger. */
    public void armEntrance() {
        mEntranceArmed = true;
        mArmedAt = System.currentTimeMillis();
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = LayoutInflater.from(getContext()).inflate(R.layout.item_ingame_menu_card, parent, false);
        }
        TextView title = view.findViewById(R.id.igm_title);
        TextView subtitle = view.findViewById(R.id.igm_subtitle);
        TextView key = view.findViewById(R.id.igm_key);
        ImageView icon = view.findViewById(R.id.igm_icon);
        View well = view.findViewById(R.id.igm_icon_well);

        title.setText(getItem(position));
        subtitle.setText(position < mSubtitles.length ? mSubtitles[position] : "");
        key.setText(String.valueOf(position + 1));
        if (position < mIcons.length) icon.setImageResource(mIcons[position]);

        boolean danger = position == mDangerIndex;
        view.setBackgroundResource(danger ? R.drawable.igm_card_danger : R.drawable.igm_card);
        well.setBackgroundResource(danger ? R.drawable.igm_icon_well_danger : R.drawable.igm_icon_well);
        icon.setColorFilter(danger ? 0xFFF08A92 : 0xFFE6E9EF);
        title.setTextColor(danger ? 0xFFFFD9DC : 0xFFF4F6F9);
        subtitle.setTextColor(danger ? 0xFFB9868B : 0xFF8A909C);

        // Staggered slide-in from the right (drawer edge) for ~700ms after arming.
        if (mEntranceArmed && System.currentTimeMillis() - mArmedAt < 700) {
            Anime.in(view, Anime.Fx.FADE_LEFT, 60L + position * 45L, 460, Anime.OUT_EXPO); // FADE_LEFT = enters from the right edge
        } else {
            view.setAlpha(1f); view.setTranslationX(0f); view.setTranslationY(0f);
            view.setScaleX(1f); view.setScaleY(1f);
        }
        return view;
    }
}
