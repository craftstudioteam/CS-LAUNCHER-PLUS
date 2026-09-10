package net.kdt.pojavlaunch.capes;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.kdt.mcgui.MineToast;

import net.kdt.pojavlaunch.R;

public class CapePreviewDialog extends Dialog {

    public interface CapeDialogListener {
        void onEquipRequested(CapeItem cape);
        void onUnequipRequested();
        void onCollectionToggled(CapeItem cape, boolean isNowSaved);
    }

    private final CapeItem mCape;
    private final boolean mIsCurrentlyEquipped;
    private final CapeDialogListener mListener;
    private final CapeRepository mRepository;

    public CapePreviewDialog(@NonNull Context context,
                              CapeItem cape,
                              boolean isCurrentlyEquipped,
                              CapeDialogListener listener) {
        super(context);
        this.mCape = cape;
        this.mIsCurrentlyEquipped = isCurrentlyEquipped;
        this.mListener = listener;
        this.mRepository = CapeRepository.getInstance();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_cape_preview);

        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView tvName = findViewById(R.id.dialog_cape_name);
        TextView tvAuthor = findViewById(R.id.dialog_cape_author);
        TextView tvCategory = findViewById(R.id.dialog_cape_category);
        TextView tvFormat = findViewById(R.id.dialog_cape_format);
        Cape2DPreviewView previewView = findViewById(R.id.dialog_cape_preview);
        ImageButton btnClose = findViewById(R.id.dialog_cape_btn_close);
        Button btnEquip = findViewById(R.id.dialog_cape_btn_equip);
        Button btnToggleCollection = findViewById(R.id.dialog_cape_btn_toggle_collection);
        Button btnRemoveActive = findViewById(R.id.dialog_cape_btn_remove_active);

        tvName.setText(mCape.getName());
        tvAuthor.setText(mCape.getAuthor() != null && !mCape.getAuthor().isEmpty()
                ? "By " + mCape.getAuthor()
                : "Category: " + mCape.getCategory());
        tvCategory.setText(mCape.getCategory().toUpperCase());

        if (mCape.isAnimated()) {
            tvFormat.setText("Animated Texture (64x32 Canvas)");
        } else {
            tvFormat.setText("64x32 PNG (Standard Mojang Spec)");
        }

        if (mIsCurrentlyEquipped) {
            btnEquip.setText("CURRENTLY EQUIPPED");
            btnEquip.setAlpha(0.6f);
            btnRemoveActive.setVisibility(android.view.View.VISIBLE);
        } else {
            btnEquip.setText("EQUIP THIS CAPE");
            btnEquip.setAlpha(1.0f);
            btnRemoveActive.setVisibility(android.view.View.GONE);
        }

        updateCollectionButtonState(btnToggleCollection);

        mRepository.loadCapeBitmapAsync(mCape, new CapeRepository.CapeBitmapCallback() {
            @Override
            public void onBitmapLoaded(Bitmap bitmap) {
                previewView.setCapeBitmap(bitmap, true); // dual side view
            }

            @Override
            public void onError(Exception e) {
                // Ignore
            }
        });

        btnClose.setOnClickListener(v -> dismiss());

        btnEquip.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onEquipRequested(mCape);
            }
            dismiss();
        });

        btnRemoveActive.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onUnequipRequested();
            }
            dismiss();
        });

        btnToggleCollection.setOnClickListener(v -> {
            boolean currentlySaved = mRepository.isCapeSaved(mCape);
            if (currentlySaved) {
                mRepository.removeFromCollection(mCape);
                updateCollectionButtonState(btnToggleCollection);
                MineToast.show(getContext(), "Removed from Collection", MineToast.TYPE_NORMAL);
                if (mListener != null) {
                    mListener.onCollectionToggled(mCape, false);
                }
            } else {
                mRepository.addToCollection(mCape, new CapeRepository.CapeActionCallback() {
                    @Override
                    public void onSuccess() {
                        updateCollectionButtonState(btnToggleCollection);
                        MineToast.show(getContext(), "Saved to Collection!", MineToast.TYPE_NORMAL);
                        if (mListener != null) {
                            mListener.onCollectionToggled(mCape, true);
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        MineToast.show(getContext(), "Error: " + e.getMessage(), MineToast.TYPE_ERROR);
                    }
                });
            }
        });
    }

    private void updateCollectionButtonState(Button btn) {
        if (mRepository.isCapeSaved(mCape)) {
            btn.setText("SAVED IN COLLECTION ✓");
            btn.setTextColor(0xFF5BD097);
        } else {
            btn.setText("SAVE TO COLLECTION");
            btn.setTextColor(0xFFC5C8D4);
        }
    }
}
