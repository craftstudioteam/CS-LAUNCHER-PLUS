package net.kdt.pojavlaunch.capes;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.MineToast;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

public class CapeAdapter extends RecyclerView.Adapter<CapeAdapter.ViewHolder> {

    public interface CapeActionListener {
        void onCapeSelected(CapeItem cape);
        void onCapeEquipped(CapeItem cape);
        void onCapeCollectionChanged(CapeItem cape, boolean added);
    }

    private final Context mContext;
    private final List<CapeItem> mCapeList = new ArrayList<>();
    private final CapeRepository mRepository;
    private CapeActionListener mListener;
    private String mEquippedCapeId = null;

    public CapeAdapter(Context context, CapeActionListener listener) {
        this.mContext = context;
        this.mListener = listener;
        this.mRepository = CapeRepository.getInstance();
    }

    public void setCapes(List<CapeItem> capes) {
        mCapeList.clear();
        if (capes != null) {
            mCapeList.addAll(capes);
        }
        notifyDataSetChanged();
    }

    public void setEquippedCapeId(String capeId) {
        this.mEquippedCapeId = capeId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_cape_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CapeItem item = mCapeList.get(position);
        holder.bind(item);
    }

    @Override
    public int getItemCount() {
        return mCapeList.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvBadge;
        private final TextView tvStatus;
        private final Cape2DPreviewView previewView;
        private final TextView tvName;
        private final TextView tvAuthor;
        private final TextView btnEquip;
        private final FrameLayout btnSave;
        private final ImageView iconSave;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvBadge = itemView.findViewById(R.id.cape_card_badge);
            tvStatus = itemView.findViewById(R.id.cape_card_status);
            previewView = itemView.findViewById(R.id.cape_card_preview);
            tvName = itemView.findViewById(R.id.cape_card_name);
            tvAuthor = itemView.findViewById(R.id.cape_card_author);
            btnEquip = itemView.findViewById(R.id.cape_card_btn_equip);
            btnSave = itemView.findViewById(R.id.cape_card_btn_save);
            iconSave = itemView.findViewById(R.id.cape_card_save_icon);
        }

        public void bind(CapeItem item) {
            tvName.setText(item.getName());
            tvAuthor.setText(item.getAuthor() != null && !item.getAuthor().isEmpty() 
                    ? item.getAuthor() 
                    : item.getCategory());

            if (CapeItem.CATEGORY_MINECON.equalsIgnoreCase(item.getCategory())) {
                tvBadge.setText("MINECON");
            } else if (CapeItem.CATEGORY_OFFICIAL.equalsIgnoreCase(item.getCategory())) {
                tvBadge.setText("OFFICIAL");
            } else if (CapeItem.CATEGORY_COMMUNITY.equalsIgnoreCase(item.getCategory())) {
                tvBadge.setText("GALLERY");
            } else if (CapeItem.CATEGORY_ANIMATED.equalsIgnoreCase(item.getCategory())) {
                tvBadge.setText("ANIMATED");
            } else {
                tvBadge.setText(item.getCategory().toUpperCase());
            }

            boolean isEquipped = mEquippedCapeId != null && mEquippedCapeId.equals(item.getId());
            tvStatus.setVisibility(isEquipped ? View.VISIBLE : View.GONE);
            if (isEquipped) {
                btnEquip.setText("EQUIPPED");
                btnEquip.setAlpha(0.6f);
            } else {
                btnEquip.setText("EQUIP");
                btnEquip.setAlpha(1.0f);
            }

            boolean isSaved = mRepository.isCapeSaved(item);
            if (isSaved) {
                iconSave.setImageResource(R.drawable.ic_check);
                iconSave.setColorFilter(0xFF5BD097);
            } else {
                iconSave.setImageResource(R.drawable.ic_download);
                iconSave.setColorFilter(0xFFC5C8D4);
            }

            // Reset preview
            previewView.setCapeBitmap(null);

            // Async load cape bitmap
            mRepository.loadCapeBitmapAsync(item, new CapeRepository.CapeBitmapCallback() {
                @Override
                public void onBitmapLoaded(Bitmap bitmap) {
                    if (getAdapterPosition() != RecyclerView.NO_POSITION &&
                            mCapeList.get(getAdapterPosition()).getId().equals(item.getId())) {
                        previewView.setCapeBitmap(bitmap);
                    }
                }

                @Override
                public void onError(Exception e) {
                    // Placeholder
                }
            });

            // Card Click -> Open Detail Dialog
            itemView.setOnClickListener(v -> {
                if (mListener != null) {
                    mListener.onCapeSelected(item);
                }
            });

            // Equip Button Click
            btnEquip.setOnClickListener(v -> {
                if (mListener != null) {
                    mListener.onCapeEquipped(item);
                }
            });

            // Save / Bookmark Button Click
            btnSave.setOnClickListener(v -> {
                boolean currentlySaved = mRepository.isCapeSaved(item);
                if (currentlySaved) {
                    mRepository.removeFromCollection(item);
                    iconSave.setImageResource(R.drawable.ic_download);
                    iconSave.setColorFilter(0xFFC5C8D4);
                    MineToast.show(mContext, "Removed from Collection", MineToast.TYPE_NORMAL);
                    if (mListener != null) {
                        mListener.onCapeCollectionChanged(item, false);
                    }
                } else {
                    mRepository.addToCollection(item, new CapeRepository.CapeActionCallback() {
                        @Override
                        public void onSuccess() {
                            iconSave.setImageResource(R.drawable.ic_check);
                            iconSave.setColorFilter(0xFF5BD097);
                            MineToast.show(mContext, "Saved to Collection!", MineToast.TYPE_NORMAL);
                            if (mListener != null) {
                                mListener.onCapeCollectionChanged(item, true);
                            }
                        }

                        @Override
                        public void onError(Exception e) {
                            MineToast.show(mContext, "Error saving cape: " + e.getMessage(), MineToast.TYPE_ERROR);
                        }
                    });
                }
            });
        }
    }
}
