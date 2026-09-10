package net.kdt.pojavlaunch.capes;

import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.MineToast;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class CapeBrowserFragment extends Fragment implements CapeAdapter.CapeActionListener {

    public static final String TAG = "CapeBrowserFragment";

    private static final int TAB_CURATED = 0;
    private static final int TAB_ONLINE = 1;
    private static final int TAB_COLLECTION = 2;

    private int mCurrentTab = TAB_CURATED;
    private String mCurrentCategory = CapeItem.CATEGORY_ALL;
    private int mCurrentPage = 1;
    private String mSearchQuery = "";

    private CapeRepository mRepository;
    private CapeAdapter mAdapter;
    private MinecraftAccount mActiveAccount;

    private RecyclerView mRecyclerView;
    private ProgressBar mProgressBar;
    private View mEmptyState;
    private TextView mTvEmptyTitle;
    private TextView mTvEmptyDesc;
    private TextView mTvActiveStatus;
    private View mPaginationBar;
    private TextView mTvPageIndicator;
    private Button mBtnPrevPage;
    private Button mBtnNextPage;
    private View mCategoryScroll;
    private EditText mEtSearch;
    private ImageButton mBtnClearSearch;

    private TextView mTabCurated;
    private TextView mTabOnline;
    private TextView mTabCollection;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> mCapePickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mCapePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                this::handleSelectedCapeUri
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_cape_browser, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mRepository = CapeRepository.getInstance();
        mActiveAccount = PojavProfile.getCurrentProfileContent(requireContext(), null);

        initViews(view);
        setupRecyclerView();
        setupTabs();
        setupCategories(view);
        setupSearch();
        updateActiveStatusBanner();

        // Initial tab load
        selectTab(TAB_CURATED);
    }

    private void initViews(View view) {
        mRecyclerView = view.findViewById(R.id.cape_recycler_view);
        mProgressBar = view.findViewById(R.id.cape_progress_bar);
        mEmptyState = view.findViewById(R.id.cape_empty_state);
        mTvEmptyTitle = view.findViewById(R.id.cape_tv_empty_title);
        mTvEmptyDesc = view.findViewById(R.id.cape_tv_empty_desc);
        mTvActiveStatus = view.findViewById(R.id.cape_tv_active_status);
        mPaginationBar = view.findViewById(R.id.cape_pagination_bar);
        mTvPageIndicator = view.findViewById(R.id.cape_tv_page_indicator);
        mBtnPrevPage = view.findViewById(R.id.cape_btn_prev_page);
        mBtnNextPage = view.findViewById(R.id.cape_btn_next_page);
        mCategoryScroll = view.findViewById(R.id.cape_category_scroll);
        mEtSearch = view.findViewById(R.id.cape_et_search);
        mBtnClearSearch = view.findViewById(R.id.cape_btn_clear_search);

        mTabCurated = view.findViewById(R.id.tab_curated);
        mTabOnline = view.findViewById(R.id.tab_online);
        mTabCollection = view.findViewById(R.id.tab_collection);

        ImageButton btnBack = view.findViewById(R.id.cape_btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                if (getActivity() != null) {
                    getParentFragmentManager().popBackStack();
                }
            });
        }

        Button btnUnequipTop = view.findViewById(R.id.cape_btn_unequip_top);
        if (btnUnequipTop != null) {
            btnUnequipTop.setOnClickListener(v -> unequipCape());
        }

        Button btnImport = view.findViewById(R.id.cape_btn_import_top);
        if (btnImport != null) {
            btnImport.setOnClickListener(v -> promptImportCustomCape());
        }

        Button btnOptifine = view.findViewById(R.id.cape_btn_optifine_top);
        if (btnOptifine != null) {
            btnOptifine.setOnClickListener(v -> promptOptifineUsername());
        }

        if (mBtnPrevPage != null) {
            mBtnPrevPage.setOnClickListener(v -> {
                if (mCurrentPage > 1) {
                    mCurrentPage--;
                    loadOnlineCapes();
                }
            });
        }

        if (mBtnNextPage != null) {
            mBtnNextPage.setOnClickListener(v -> {
                mCurrentPage++;
                loadOnlineCapes();
            });
        }
    }

    private void setupRecyclerView() {
        int spanCount = 3;
        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            spanCount = 4;
        } else {
            spanCount = 2;
        }

        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), spanCount);
        mRecyclerView.setLayoutManager(layoutManager);
        mAdapter = new CapeAdapter(requireContext(), this);
        mRecyclerView.setAdapter(mAdapter);
    }

    private void setupTabs() {
        mTabCurated.setOnClickListener(v -> selectTab(TAB_CURATED));
        mTabOnline.setOnClickListener(v -> selectTab(TAB_ONLINE));
        mTabCollection.setOnClickListener(v -> selectTab(TAB_COLLECTION));
    }

    private void selectTab(int tab) {
        mCurrentTab = tab;

        mTabCurated.setBackgroundResource(tab == TAB_CURATED ? R.drawable.bg_skinv2_pill_on : android.R.color.transparent);
        mTabCurated.setTextColor(tab == TAB_CURATED ? 0xFF0E1017 : 0xFF8890A0);

        mTabOnline.setBackgroundResource(tab == TAB_ONLINE ? R.drawable.bg_skinv2_pill_on : android.R.color.transparent);
        mTabOnline.setTextColor(tab == TAB_ONLINE ? 0xFF0E1017 : 0xFF8890A0);

        mTabCollection.setBackgroundResource(tab == TAB_COLLECTION ? R.drawable.bg_skinv2_pill_on : android.R.color.transparent);
        mTabCollection.setTextColor(tab == TAB_COLLECTION ? 0xFF0E1017 : 0xFF8890A0);

        if (mCategoryScroll != null) {
            mCategoryScroll.setVisibility(tab == TAB_ONLINE ? View.VISIBLE : View.GONE);
        }

        if (mPaginationBar != null) {
            mPaginationBar.setVisibility(tab == TAB_ONLINE ? View.VISIBLE : View.GONE);
        }

        loadCurrentTabContent();
    }

    private void setupCategories(View root) {
        TextView chipAll = root.findViewById(R.id.chip_all);
        TextView chipMinecon = root.findViewById(R.id.chip_minecon);
        TextView chipOfficial = root.findViewById(R.id.chip_official);
        TextView chipAnimated = root.findViewById(R.id.chip_animated);
        TextView chipPopular = root.findViewById(R.id.chip_popular);

        TextView[] chips = new TextView[]{chipAll, chipMinecon, chipOfficial, chipAnimated, chipPopular};
        String[] categories = new String[]{CapeItem.CATEGORY_ALL, CapeItem.CATEGORY_MINECON, CapeItem.CATEGORY_OFFICIAL, CapeItem.CATEGORY_ANIMATED, CapeItem.CATEGORY_POPULAR};

        for (int i = 0; i < chips.length; i++) {
            final int index = i;
            if (chips[i] != null) {
                chips[i].setOnClickListener(v -> {
                    mCurrentCategory = categories[index];
                    mCurrentPage = 1;
                    for (int j = 0; j < chips.length; j++) {
                        if (chips[j] != null) {
                            boolean selected = (j == index);
                            chips[j].setBackgroundResource(selected ? R.drawable.bg_skinv2_pill_on : R.drawable.st_key_cap);
                            chips[j].setTextColor(selected ? 0xFF0E1017 : 0xFF8890A0);
                        }
                    }
                    loadOnlineCapes();
                });
            }
        }
    }

    private void setupSearch() {
        if (mEtSearch != null) {
            mEtSearch.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                        (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    performSearch();
                    return true;
                }
                return false;
            });
        }

        View btnSearch = getView() != null ? getView().findViewById(R.id.cape_btn_search) : null;
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> performSearch());
        }

        if (mBtnClearSearch != null) {
            mBtnClearSearch.setOnClickListener(v -> {
                mEtSearch.setText("");
                mSearchQuery = "";
                mBtnClearSearch.setVisibility(View.GONE);
                loadCurrentTabContent();
            });
        }
    }

    private void performSearch() {
        if (mEtSearch == null) return;
        mSearchQuery = mEtSearch.getText().toString().trim();
        if (mBtnClearSearch != null) {
            mBtnClearSearch.setVisibility(mSearchQuery.isEmpty() ? View.GONE : View.VISIBLE);
        }
        mCurrentPage = 1;
        loadCurrentTabContent();
    }

    private void loadCurrentTabContent() {
        if (mCurrentTab == TAB_CURATED) {
            loadCuratedCapes();
        } else if (mCurrentTab == TAB_ONLINE) {
            loadOnlineCapes();
        } else if (mCurrentTab == TAB_COLLECTION) {
            loadCollectionCapes();
        }
    }

    private void loadCuratedCapes() {
        showLoading(false);
        List<CapeItem> allCurated = mRepository.getCuratedCapes();
        List<CapeItem> filtered = new ArrayList<>();

        if (mSearchQuery.isEmpty()) {
            filtered.addAll(allCurated);
        } else {
            String query = mSearchQuery.toLowerCase();
            for (CapeItem item : allCurated) {
                if (item.getName().toLowerCase().contains(query) ||
                        item.getCategory().toLowerCase().contains(query)) {
                    filtered.add(item);
                }
            }
        }

        mAdapter.setCapes(filtered);
        checkEmpty(filtered.isEmpty(), "No official capes match your filter", "Try a different search keyword.");
    }

    private void loadCollectionCapes() {
        showLoading(false);
        List<CapeItem> collection = mRepository.getCollectionCapes();
        List<CapeItem> filtered = new ArrayList<>();

        if (mSearchQuery.isEmpty()) {
            filtered.addAll(collection);
        } else {
            String query = mSearchQuery.toLowerCase();
            for (CapeItem item : collection) {
                if (item.getName().toLowerCase().contains(query) ||
                        item.getCategory().toLowerCase().contains(query)) {
                    filtered.add(item);
                }
            }
        }

        mAdapter.setCapes(filtered);
        checkEmpty(filtered.isEmpty(), "Your Cape Collection is empty", "Save capes from the Official catalog or import a custom PNG!");
    }

    private void loadOnlineCapes() {
        showLoading(true);
        if (mTvPageIndicator != null) {
            mTvPageIndicator.setText("PAGE " + mCurrentPage);
        }

        MinecraftCapesService.getInstance().searchGalleryAsync(mSearchQuery, mCurrentCategory, mCurrentPage,
                new MinecraftCapesService.GalleryCallback() {
                    @Override
                    public void onSuccess(List<CapeItem> capes, int totalPages) {
                        mMainHandler.post(() -> {
                            showLoading(false);
                            mAdapter.setCapes(capes);
                            checkEmpty(capes.isEmpty(), "No capes found in gallery", "Try a different search term or category.");
                            if (mBtnPrevPage != null) mBtnPrevPage.setEnabled(mCurrentPage > 1);
                            if (mBtnNextPage != null) mBtnNextPage.setEnabled(mCurrentPage < totalPages || capes.size() >= 12);
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        mMainHandler.post(() -> {
                            showLoading(false);
                            checkEmpty(true, "Could not load online gallery", "Check your internet connection: " + e.getMessage());
                        });
                    }
                });
    }

    private void promptImportCustomCape() {
        if (mCapePickerLauncher != null) {
            try {
                mCapePickerLauncher.launch("image/png");
            } catch (Exception e) {
                MineToast.show(requireContext(), "Could not open file picker: " + e.getMessage(), MineToast.TYPE_ERROR);
            }
        }
    }

    private void handleSelectedCapeUri(Uri uri) {
        if (uri == null || getContext() == null) return;
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
            Bitmap bmp = BitmapFactory.decodeStream(is);
            if (bmp == null) {
                MineToast.show(requireContext(), "Invalid image file", MineToast.TYPE_ERROR);
                return;
            }

            String customName = "Custom Cape " + (System.currentTimeMillis() % 10000);
            mRepository.importCustomCape(customName, bmp, new CapeRepository.CapeActionCallback() {
                @Override
                public void onSuccess() {
                    MineToast.show(requireContext(), "Custom Cape imported to Collection!", MineToast.TYPE_NORMAL);
                    selectTab(TAB_COLLECTION);
                }

                @Override
                public void onError(Exception e) {
                    MineToast.show(requireContext(), "Import error: " + e.getMessage(), MineToast.TYPE_ERROR);
                }
            });
        } catch (Exception e) {
            MineToast.show(requireContext(), "Failed to read cape: " + e.getMessage(), MineToast.TYPE_ERROR);
        }
    }

    private void promptOptifineUsername() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("OptiFine Cape Lookup");
        final EditText input = new EditText(requireContext());
        input.setHint("Enter Minecraft Username");
        input.setTextColor(Color.WHITE);
        input.setPadding(32, 24, 32, 24);
        builder.setView(input);

        builder.setPositiveButton("Fetch & Equip", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                fetchOptifineCape(name);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void fetchOptifineCape(String username) {
        MineToast.show(requireContext(), "Fetching OptiFine cape for " + username + "...", MineToast.TYPE_NORMAL);
        new Thread(() -> {
            try {
                String urlStr = "http://s.optifine.net/capes/" + username + ".png";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", MinecraftCapesService.USER_AGENT);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        if (bmp != null) {
                            mMainHandler.post(() -> {
                                mRepository.importCustomCape(username + "'s OptiFine Cape", bmp, new CapeRepository.CapeActionCallback() {
                                    @Override
                                    public void onSuccess() {
                                        MineToast.show(requireContext(), "OptiFine Cape saved!", MineToast.TYPE_NORMAL);
                                        selectTab(TAB_COLLECTION);
                                    }

                                    @Override
                                    public void onError(Exception e) {
                                        MineToast.show(requireContext(), "Error saving cape: " + e.getMessage(), MineToast.TYPE_ERROR);
                                    }
                                });
                            });
                            return;
                        }
                    }
                }
                mMainHandler.post(() -> MineToast.show(requireContext(), "No OptiFine cape found for " + username, MineToast.TYPE_WARNING));
            } catch (Exception e) {
                mMainHandler.post(() -> MineToast.show(requireContext(), "OptiFine query error: " + e.getMessage(), MineToast.TYPE_ERROR));
            }
        }).start();
    }

    private void showLoading(boolean loading) {
        if (mProgressBar != null) mProgressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (mRecyclerView != null) mRecyclerView.setVisibility(loading ? View.GONE : View.VISIBLE);
        if (mEmptyState != null && loading) mEmptyState.setVisibility(View.GONE);
    }

    private void checkEmpty(boolean empty, String title, String desc) {
        if (mEmptyState != null) {
            mEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            if (mTvEmptyTitle != null) mTvEmptyTitle.setText(title);
            if (mTvEmptyDesc != null) mTvEmptyDesc.setText(desc);
        }
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private void updateActiveStatusBanner() {
        if (mActiveAccount == null) return;
        File localCape = new File(Tools.DIR_DATA + "/capes/" + mActiveAccount.username + "_cape.png");
        if (localCape.exists()) {
            mTvActiveStatus.setText("Equipped for: " + mActiveAccount.username + " (" + (localCape.length() / 1024) + " KB)");
            mTvActiveStatus.setTextColor(0xFF5BD097);
        } else {
            mTvActiveStatus.setText("Equipped: None");
            mTvActiveStatus.setTextColor(0xFF7C8494);
        }
    }

    @Override
    public void onCapeSelected(CapeItem cape) {
        boolean isEquipped = isCapeActive(cape);
        CapePreviewDialog dialog = new CapePreviewDialog(requireContext(), cape, isEquipped,
                new CapePreviewDialog.CapeDialogListener() {
                    @Override
                    public void onEquipRequested(CapeItem c) {
                        equipCape(c);
                    }

                    @Override
                    public void onUnequipRequested() {
                        unequipCape();
                    }

                    @Override
                    public void onCollectionToggled(CapeItem c, boolean isNowSaved) {
                        if (mCurrentTab == TAB_COLLECTION) {
                            loadCollectionCapes();
                        } else {
                            mAdapter.notifyDataSetChanged();
                        }
                    }
                });
        dialog.show();
    }

    @Override
    public void onCapeEquipped(CapeItem cape) {
        equipCape(cape);
    }

    @Override
    public void onCapeCollectionChanged(CapeItem cape, boolean added) {
        if (mCurrentTab == TAB_COLLECTION) {
            loadCollectionCapes();
        }
    }

    private void equipCape(CapeItem cape) {
        if (mActiveAccount == null) {
            MineToast.show(requireContext(), "Please select an account first", MineToast.TYPE_ERROR);
            return;
        }

        mRepository.equipCape(mActiveAccount, cape, new CapeRepository.CapeActionCallback() {
            @Override
            public void onSuccess() {
                mAdapter.setEquippedCapeId(cape.getId());
                updateActiveStatusBanner();
                MineToast.show(requireContext(), "Cape equipped: " + cape.getName(), MineToast.TYPE_NORMAL);
            }

            @Override
            public void onError(Exception e) {
                MineToast.show(requireContext(), "Failed to equip cape: " + e.getMessage(), MineToast.TYPE_ERROR);
            }
        });
    }

    private void unequipCape() {
        if (mActiveAccount == null) return;
        mRepository.removeEquippedCape(mActiveAccount);
        mAdapter.setEquippedCapeId(null);
        updateActiveStatusBanner();
        MineToast.show(requireContext(), "Cape removed", MineToast.TYPE_NORMAL);
    }

    private boolean isCapeActive(CapeItem cape) {
        if (mActiveAccount == null) return false;
        File activeCape = new File(Tools.DIR_DATA + "/capes/" + mActiveAccount.username + "_cape.png");
        return activeCape.exists();
    }
}
