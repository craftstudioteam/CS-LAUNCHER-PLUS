package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.modloaders.modpacks.models.Constants.SOURCE_MODRINTH;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ModIconCache;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ModInstallFragment extends Fragment {

    public static final String TAG = "ModInstallFragment";
    private static final String ARG_MOD_ITEM = "mod_item";
    private static final String ARG_MOD_DETAIL = "mod_detail";
    private static final String ARG_VERSION_INDEX = "version_index";
    private static final String ARG_PROFILE_KEY = "profile_key";
    private static final String ARG_CONTENT_TYPE = "content_type";

    private ModItem mModItem;
    private ModDetail mModDetail;
    private int mVersionIndex;
    private String mProfileKey;
    private String mContentType;
    /** Optional absolute dir override (World Manager → datapacks installs). */
    private String mTargetDirPath;
    /** Optional tracking-slot override (world folder name for datapacks). */
    private String mInstallKey;
    public static final String ARG_TARGET_DIR = "dp_target_dir";
    public static final String ARG_INSTALL_KEY = "dp_install_key";

    private ImageView mBackButton;
    private ImageView mModIcon;
    private TextView mModTitle;
    private TextView mModAuthor;
    private TextView mVersionBadge;
    private TextView mFullDescription;
    private TextView mInstallButton;

    // Stats row
    private View mStatsRow;
    private View mStatDownloads;
    private View mStatFollowers;
    private View mStatLicense;
    private TextView mStatDownloadsValue;
    private TextView mStatFollowersValue;
    private TextView mStatLicenseValue;

    // Gallery
    private View mGallerySection;
    private android.widget.LinearLayout mGalleryContainer;

    // ── Auto-sliding hero backdrop (every artwork image, crossfaded) ──
    private ImageView mHeroBackdropA;
    private ImageView mHeroBackdropB;
    private final java.util.List<android.graphics.Bitmap> mBackdrops = new java.util.ArrayList<>();
    private int mBackdropIndex = 0;
    private boolean mBackdropFrontA = true;
    private android.os.Handler mBackdropHandler;
    private final Runnable mBackdropTick = new Runnable() {
        @Override public void run() { advanceBackdrop(); }
    };

    // Details
    private TextView mDetailVersion;
    private TextView mDetailUpdated;
    private View mDetailUpdatedRow;
    private TextView mDetailSource;
    private TextView mLoadersLabel;
    private TextView mMcVersionsLabel;
    private TextView mCategoriesLabel;
    private android.view.ViewGroup mLoadersContainer;
    private android.view.ViewGroup mMcVersionsContainer;
    private android.view.ViewGroup mCategoriesContainer;

    // Dependencies + changelog + links
    private View mDepsSection;
    private TextView mDepsText;
    private View mChangelogSection;
    private TextView mChangelog;
    private View mLinksSection;
    private TextView mLinkWebsite;
    private TextView mLinkSource;
    private TextView mLinkIssues;
    private TextView mLinkWiki;
    private TextView mLinkDiscord;

    // View references for animations
    private View mTopBar;
    private View mBottomBar;
    private View mScrollContent;
    private View mHeroCard;

    public static ModInstallFragment newInstance(ModItem item, ModDetail detail,
                                                  int versionIndex, String profileKey,
                                                  String contentType) {
        ModInstallFragment f = new ModInstallFragment();
        Bundle args = new Bundle();
        args.putSerializable(ARG_MOD_ITEM, item);
        args.putSerializable(ARG_MOD_DETAIL, detail);
        args.putInt(ARG_VERSION_INDEX, versionIndex);
        args.putString(ARG_PROFILE_KEY, profileKey);
        args.putString(ARG_CONTENT_TYPE, contentType != null ? contentType : "mod");
        f.setArguments(args);
        return f;
    }

    public ModInstallFragment() {
        super(R.layout.fragment_mod_install);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mModItem = (ModItem) getArguments().getSerializable(ARG_MOD_ITEM);
            mModDetail = (ModDetail) getArguments().getSerializable(ARG_MOD_DETAIL);
            mVersionIndex = getArguments().getInt(ARG_VERSION_INDEX);
            mProfileKey = getArguments().getString(ARG_PROFILE_KEY);
            mContentType = getArguments().getString(ARG_CONTENT_TYPE, "mod");
            mTargetDirPath = getArguments().getString(ARG_TARGET_DIR);
            mInstallKey = getArguments().getString(ARG_INSTALL_KEY);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Bind premium ID references
        mTopBar = view.findViewById(R.id.install_top_bar);
        mBackButton = view.findViewById(R.id.install_back_button);
        mHeroCard = view.findViewById(R.id.install_hero_card);
        mModIcon = view.findViewById(R.id.install_mod_icon);
        mModTitle = view.findViewById(R.id.install_mod_title);
        mModAuthor = view.findViewById(R.id.install_mod_author);
        mVersionBadge = view.findViewById(R.id.install_selected_version_badge);
        mFullDescription = view.findViewById(R.id.install_full_description);
        mBottomBar = view.findViewById(R.id.install_bottom_bar);
        mInstallButton = view.findViewById(R.id.install_button);
        mScrollContent = view.findViewById(R.id.install_scroll_content);

        mStatsRow = view.findViewById(R.id.install_stats_row);
        mStatDownloads = view.findViewById(R.id.install_stat_downloads);
        mStatFollowers = view.findViewById(R.id.install_stat_followers);
        mStatLicense = view.findViewById(R.id.install_stat_license);
        mStatDownloadsValue = view.findViewById(R.id.install_stat_downloads_value);
        mStatFollowersValue = view.findViewById(R.id.install_stat_followers_value);
        mStatLicenseValue = view.findViewById(R.id.install_stat_license_value);

        mGallerySection = view.findViewById(R.id.install_gallery_section);
        mGalleryContainer = view.findViewById(R.id.install_gallery_container);
        mHeroBackdropA = view.findViewById(R.id.install_hero_backdrop_a);
        mHeroBackdropB = view.findViewById(R.id.install_hero_backdrop_b);

        mDetailVersion = view.findViewById(R.id.install_detail_version);
        mDetailUpdated = view.findViewById(R.id.install_detail_updated);
        mDetailUpdatedRow = view.findViewById(R.id.install_detail_updated_row);
        mDetailSource = view.findViewById(R.id.install_detail_source);
        mLoadersLabel = view.findViewById(R.id.install_loaders_label);
        mLoadersContainer = view.findViewById(R.id.install_loaders_container);
        mMcVersionsLabel = view.findViewById(R.id.install_mcversions_label);
        mMcVersionsContainer = view.findViewById(R.id.install_mcversions_container);
        mCategoriesLabel = view.findViewById(R.id.install_categories_label);
        mCategoriesContainer = view.findViewById(R.id.install_categories_container);

        mDepsSection = view.findViewById(R.id.install_deps_section);
        mDepsText = view.findViewById(R.id.install_deps_text);
        mChangelogSection = view.findViewById(R.id.install_changelog_section);
        mChangelog = view.findViewById(R.id.install_changelog);
        mLinksSection = view.findViewById(R.id.install_links_section);
        mLinkWebsite = view.findViewById(R.id.install_link_website);
        mLinkSource = view.findViewById(R.id.install_link_source);
        mLinkIssues = view.findViewById(R.id.install_link_issues);
        mLinkWiki = view.findViewById(R.id.install_link_wiki);
        mLinkDiscord = view.findViewById(R.id.install_link_discord);

        // Populate immediate UI from the search-hit data
        if (mModItem != null) {
            mModTitle.setText(mModItem.title);
            if (mModItem.author != null && !mModItem.author.isEmpty()) {
                mModAuthor.setText(getString(R.string.mod_install_by_author, mModItem.author));
                mModAuthor.setVisibility(View.VISIBLE);
            }

            // Load icon asynchronously
            ModIconCache iconCache = ModIconCache.getInstance();
            iconCache.getImage(
                    bitmap -> {
                        if (bitmap != null && isAdded()) {
                            mModIcon.setImageBitmap(bitmap);
                            // Phase 10 dossier: until real screenshots arrive the
                            // poster shows a soft, zoomed wash of the icon so the
                            // left column is never an empty slab.
                            if (mBackdrops.isEmpty() && mHeroBackdropA != null && mHeroBackdropA.getDrawable() == null) {
                                try {
                                    android.graphics.Bitmap wash = softWash(bitmap);
                                    mHeroBackdropA.setImageBitmap(wash);
                                    mHeroBackdropA.setAlpha(0f);
                                    mHeroBackdropA.animate().alpha(0.55f).setDuration(420).start();
                                } catch (Throwable ignored) {}
                            }
                        }
                    },
                    mModItem.getIconCacheTag(),
                    mModItem.imageUrl
            );
        }

        mDetailSource.setText(mModItem != null && mModItem.apiSource == Constants.SOURCE_MODRINTH
                ? "Modrinth" : "CurseForge");

        if (mModDetail != null) {
            // Show full description (search-hit text until rich info lands)
            if (mModDetail.description != null && !mModDetail.description.isEmpty()) {
                mFullDescription.setText(mModDetail.description);
            }

            // Show selected version badge + details row
            if (mVersionIndex >= 0 && mModDetail.versionNames != null
                    && mVersionIndex < mModDetail.versionNames.length) {
                String versionText = mModDetail.versionNames[mVersionIndex];
                mVersionBadge.setText(versionText);
                mDetailVersion.setText(versionText);
            }

            // Dependencies summary (data already on hand)
            bindDependencies();

            // Changelog of the selected version (captured by the API layer)
            bindChangelog();

            // Rich project info (gallery, stats, links, full body) — async
            loadProjectInfo();

            // Determine the file name from version URL
            String versionUrl = (mModDetail.versionUrls != null
                    && mVersionIndex >= 0 && mVersionIndex < mModDetail.versionUrls.length)
                    ? mModDetail.versionUrls[mVersionIndex] : null;

            final String fileName;
            if (versionUrl != null && !versionUrl.isEmpty()) {
                String raw = versionUrl.substring(versionUrl.lastIndexOf('/') + 1);
                if (raw.contains("?")) raw = raw.substring(0, raw.indexOf('?'));
                fileName = raw;
            } else {
                fileName = (mModItem != null ? mModItem.title : "mod") + ".jar";
            }

            final String finalUrl = versionUrl;

            boolean alreadyInstalled = isFileAlreadyInstalled(fileName);
            if (alreadyInstalled) {
                mInstallButton.setText(R.string.mod_detail_installed);
                mInstallButton.setEnabled(false);
                mInstallButton.setAlpha(0.55f);
            } else if (mModItem != null && mModItem.isModpack) {
                mInstallButton.setText(R.string.mod_detail_install_modpack);
            } else switch (mContentType) {
                case "resourcepack":
                    mInstallButton.setText(R.string.mod_detail_install_resourcepack);
                    break;
                case "shader":
                    mInstallButton.setText(R.string.mod_detail_install_shader);
                    break;
                case "world":
                    mInstallButton.setText(R.string.mod_detail_install_datapack);
                    break;
                default:
                    mInstallButton.setText(R.string.mod_detail_install_mod);
                    break;
            }

            mInstallButton.setOnClickListener(v -> {
                if (finalUrl == null || finalUrl.isEmpty()) {
                    Toast.makeText(getContext(),
                            R.string.modpack_install_download_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                // Profile-Based Download: install directly into the currently opened
                // profile's content folder. The profile key is resolved inside
                // getContentDir(), so no profile selection dialog is shown.
                startDownload(finalUrl, fileName);
            });
        }

        // Back button — pop back to the mod detail / list
        mBackButton.setOnClickListener(v ->
                getParentFragmentManager().popBackStack());

        // Use the view parameter directly (avoids NullPointerException if fragment is detached)
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        view.post(() -> {
            setupInstallAnimations();
            // Restore software layer after animation completes
            view.postDelayed(() -> { if (isAdded()) view.setLayerType(View.LAYER_TYPE_NONE, null); }, 500);
        });

        setupDetailTabs(view);
    }

    /** CS Premium: scroll-spy tabs — tap a tab to glide to its section card. */
    private void setupDetailTabs(@NonNull View view) {
        TextView topTitle = view.findViewById(R.id.install_topbar_title);
        if (topTitle != null && mModItem != null) topTitle.setText(mModItem.title);

        final int[] tabs = { R.id.install_tab_overview, R.id.install_tab_details,
                R.id.install_tab_gallery, R.id.install_tab_deps, R.id.install_tab_changelog };
        final int[] sections = { R.id.install_about_section, R.id.install_details_section,
                R.id.install_gallery_section, R.id.install_deps_section, R.id.install_changelog_section };
        final View scroll = view.findViewById(R.id.install_scroll_content);

        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            View tab = view.findViewById(tabs[i]);
            if (tab == null) continue;
            tab.setOnClickListener(v -> {
                for (int tid : tabs) {
                    View t = view.findViewById(tid);
                    if (t instanceof TextView) {
                        t.setBackgroundResource(R.drawable.md_tab_off);
                        ((TextView) t).setTextColor(0xFF8A909C);
                    }
                }
                v.setBackgroundResource(R.drawable.md_tab_on);
                ((TextView) v).setTextColor(0xFFF4F6F9);
                net.kdt.pojavlaunch.Anime.pulse(v);

                View target = view.findViewById(sections[idx]);
                // Sections stay hidden until their async content lands; fall
                // back to Overview so the tap always does something useful.
                if (target == null || target.getVisibility() != View.VISIBLE) {
                    target = view.findViewById(sections[0]);
                }
                if (target != null && scroll instanceof androidx.core.widget.NestedScrollView) {
                    final View t = target;
                    v.postDelayed(() -> {
                        if (!isAdded()) return;
                        ((androidx.core.widget.NestedScrollView) scroll)
                                .smoothScrollTo(0, Math.max(0, t.getTop() - 10));
                    }, 40);
                }
            });
        }

        View first = view.findViewById(tabs[0]);
        if (first instanceof TextView) {
            first.setBackgroundResource(R.drawable.md_tab_on);
            ((TextView) first).setTextColor(0xFFF4F6F9);
        }
    }


    // ─── Rich project info (gallery • stats • links • body) ─────────

    private void loadProjectInfo() {
        if (mModItem == null || mModItem.id == null) {
            bindFallbackInfo();
            return;
        }
        PojavApplication.sExecutorService.execute(() -> {
            net.kdt.pojavlaunch.modloaders.modpacks.models.ModProjectInfo info = null;
            try {
                if (mModItem.apiSource == Constants.SOURCE_MODRINTH) {
                    info = new ModrinthApi().fetchProjectInfo(mModItem.id);
                } else {
                    Context c = getContext();
                    String key = c != null ? c.getString(R.string.curseforge_api_key) : "";
                    info = new net.kdt.pojavlaunch.modloaders.modpacks.api.CurseforgeApi(key)
                            .fetchProjectInfo(mModItem.id);
                }
            } catch (Exception ignored) {}
            final net.kdt.pojavlaunch.modloaders.modpacks.models.ModProjectInfo fInfo = info;
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (fInfo != null) bindProjectInfo(fInfo);
                else bindFallbackInfo();
            });
        });
    }

    private void bindProjectInfo(net.kdt.pojavlaunch.modloaders.modpacks.models.ModProjectInfo info) {
        // Author from the richer source
        if (info.author != null && !info.author.isEmpty()) {
            mModAuthor.setText(getString(R.string.mod_install_by_author, info.author));
            mModAuthor.setVisibility(View.VISIBLE);
        }

        // Full markdown body
        String body = info.body != null && !info.body.isEmpty()
                ? info.body : (info.tagline != null ? info.tagline : null);
        if (body != null && !body.isEmpty()) {
            mFullDescription.setText(stripMarkdown(body));
        }

        // Stats
        if (info.downloads >= 0) mStatDownloadsValue.setText(formatCount(info.downloads));
        else if (mModItem != null && mModItem.downloads != null) {
            mStatDownloadsValue.setText(formatCount(parseCount(mModItem.downloads)));
        }
        if (info.followers >= 0) mStatFollowersValue.setText(formatCount(info.followers));
        else mStatFollowers.setVisibility(View.GONE);
        if (info.license != null && !info.license.isEmpty()
                && !"LicenseRef".equalsIgnoreCase(info.license))  {
            mStatLicenseValue.setText(info.license);
        } else mStatLicense.setVisibility(View.GONE);
        animateSectionIn(mStatsRow);

        // Last updated
        String updated = formatIsoDate(info.updatedIso);
        if (updated != null) mDetailUpdated.setText(updated);
        else mDetailUpdatedRow.setVisibility(View.GONE);

        // Gallery
        if (info.galleryUrls != null && info.galleryUrls.length > 0) {
            bindGallery(info.galleryUrls);
            // keep the detail model in sync for any other consumer
            if (mModDetail != null) mModDetail.setScreenshotUrls(info.galleryUrls);
        }

        // Chips
        bindChips(mLoadersLabel, mLoadersContainer, capitalizeAll(info.loaders));
        bindMcVersionChips(info.gameVersions);
        bindChips(mCategoriesLabel, mCategoriesContainer, capitalizeAll(info.categories));

        // Links
        boolean anyLink = false;
        anyLink |= bindLink(mLinkWebsite, info.websiteUrl);
        anyLink |= bindLink(mLinkSource, info.sourceUrl);
        anyLink |= bindLink(mLinkIssues, info.issuesUrl);
        anyLink |= bindLink(mLinkWiki, info.wikiUrl);
        anyLink |= bindLink(mLinkDiscord, info.discordUrl);
        if (anyLink) {
            mLinksSection.setVisibility(View.VISIBLE);
            mLinksSection.setAlpha(0f);
            animateSectionIn(mLinksSection);
        }
    }

    /** No rich info available — build what we can from the search hit + detail model. */
    private void bindFallbackInfo() {
        if (mModItem != null && mModItem.downloads != null) {
            mStatDownloadsValue.setText(formatCount(parseCount(mModItem.downloads)));
        } else mStatDownloads.setVisibility(View.GONE);
        mStatFollowers.setVisibility(View.GONE);
        mStatLicense.setVisibility(View.GONE);
        mDetailUpdatedRow.setVisibility(View.GONE);
        animateSectionIn(mStatsRow);

        // Loaders / MC versions of the selected version row (data on hand)
        if (mModDetail != null && mVersionIndex >= 0) {
            if (mModDetail.versionLoaders != null && mVersionIndex < mModDetail.versionLoaders.length
                    && mModDetail.versionLoaders[mVersionIndex] != null) {
                bindChips(mLoadersLabel, mLoadersContainer,
                        capitalizeAll(mModDetail.versionLoaders[mVersionIndex]));
            }
            if (mModDetail.mcVersionLists != null && mVersionIndex < mModDetail.mcVersionLists.length
                    && mModDetail.mcVersionLists[mVersionIndex] != null) {
                bindMcVersionChips(mModDetail.mcVersionLists[mVersionIndex]);
            } else if (mModDetail.mcVersionNames != null && mVersionIndex < mModDetail.mcVersionNames.length
                    && mModDetail.mcVersionNames[mVersionIndex] != null) {
                bindChips(mMcVersionsLabel, mMcVersionsContainer,
                        new String[]{mModDetail.mcVersionNames[mVersionIndex]});
            }
        }

        if (mModItem != null && bindLink(mLinkWebsite, mModItem.websiteUrl)) {
            mLinksSection.setVisibility(View.VISIBLE);
            mLinksSection.setAlpha(0f);
            animateSectionIn(mLinksSection);
        }
    }

    private void bindGallery(String[] urls) {
        startHeroSlideshow(urls);
        float d = getResources().getDisplayMetrics().density;
        int h = (int) (d * 132);
        int w = (int) (d * 200);
        int margin = (int) (d * 10);
        ModIconCache cache = ModIconCache.getInstance();
        int shown = 0;
        for (String url : urls) {
            if (url == null || url.isEmpty() || shown >= 8) break;
            ImageView iv = new ImageView(requireContext());
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(w, h);
            if (shown > 0) lp.setMarginStart(margin);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.md_gallery_tile);
            iv.setClipToOutline(true);
            iv.setAlpha(0f);
            mGalleryContainer.addView(iv);
            cache.getImage(bitmap -> {
                if (!isAdded()) return;
                if (bitmap != null) {
                    iv.setImageBitmap(bitmap);
                    iv.animate().alpha(1f).setDuration(280).start();
                }
            }, "screenshot_" + url, url);
            shown++;
        }
        if (shown > 0) {
            mGallerySection.setVisibility(View.VISIBLE);
            animateSectionIn(mGallerySection);
        }
    }

    private boolean bindLink(TextView linkView, String url) {
        if (url == null || url.isEmpty()) {
            linkView.setVisibility(View.GONE);
            return false;
        }
        linkView.setVisibility(View.VISIBLE);
        linkView.setOnClickListener(v -> {
            android.app.Activity act = getActivity();
            if (act != null) Tools.openURL(act, url);
        });
        return true;
    }

    private void bindChips(TextView label, android.view.ViewGroup container, String[] values) {
        container.removeAllViews();
        if (values == null || values.length == 0) {
            label.setVisibility(View.GONE);
            container.setVisibility(View.GONE);
            return;
        }
        label.setVisibility(View.VISIBLE);
        container.setVisibility(View.VISIBLE);
        for (String v : values) {
            if (v == null || v.isEmpty()) continue;
            container.addView(makeChip(v));
        }
    }

    /** Condense a long MC version list: newest 6 + "+N more". */
    private void bindMcVersionChips(String[] gameVersions) {
        if (gameVersions == null || gameVersions.length == 0) {
            bindChips(mMcVersionsLabel, mMcVersionsContainer, (String[]) null);
            return;
        }
        // Filter out snapshots / pre-releases for a clean list
        List<String> releases = new ArrayList<>();
        for (String v : gameVersions) {
            if (v != null && v.matches("\\d+\\.\\d+(\\.\\d+)?")) releases.add(v);
        }
        String[] src = releases.isEmpty() ? gameVersions : releases.toArray(new String[0]);
        int max = 6;
        int count = Math.min(src.length, max);
        String[] display = new String[count + (src.length > max ? 1 : 0)];
        // game_versions arrive oldest→newest; show the NEWEST entries
        for (int i = 0; i < count; i++) display[i] = src[src.length - 1 - i];
        if (src.length > max) {
            display[count] = getString(R.string.mod_install_more_chip, src.length - max);
        }
        bindChips(mMcVersionsLabel, mMcVersionsContainer, display);
    }

    private TextView makeChip(String text) {
        TextView chip = new TextView(requireContext());
        chip.setText(text);
        chip.setTextColor(0xFFD2D6DE);
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
        chip.setIncludeFontPadding(false);
        chip.setMaxLines(1);
        chip.setBackgroundResource(R.drawable.md_chip);
        float d = getResources().getDisplayMetrics().density;
        chip.setPadding((int) (9 * d), (int) (5 * d), (int) (9 * d), (int) (5 * d));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd((int) (6 * d));
        chip.setLayoutParams(lp);
        return chip;
    }

    private void bindDependencies() {
        if (mModDetail.versionDependencyIds == null
                || mVersionIndex >= mModDetail.versionDependencyIds.length) return;
        String[] ids = mModDetail.versionDependencyIds[mVersionIndex];
        if (ids == null || ids.length == 0) return;
        int required = 0, optional = 0;
        String[] types = mModDetail.versionDependencyTypes != null
                && mVersionIndex < mModDetail.versionDependencyTypes.length
                ? mModDetail.versionDependencyTypes[mVersionIndex] : null;
        for (int i = 0; i < ids.length; i++) {
            String t = types != null && i < types.length ? types[i] : "required";
            if ("optional".equalsIgnoreCase(t)) optional++;
            else required++;
        }
        mDepsText.setText(optional > 0
                ? getString(R.string.mod_install_dep_message, required, optional)
                : getString(R.string.mod_install_dep_message_simple, required));
        mDepsSection.setVisibility(View.VISIBLE);
    }

    private void bindChangelog() {
        if (mModDetail.versionChangelogs == null
                || mVersionIndex >= mModDetail.versionChangelogs.length) return;
        String cl = mModDetail.versionChangelogs[mVersionIndex];
        if (cl == null || cl.trim().isEmpty()) return;
        mChangelog.setText(stripMarkdown(cl));
        mChangelogSection.setVisibility(View.VISIBLE);
    }

    private void animateSectionIn(View v) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(getResources().getDisplayMetrics().density * 14);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320)
                .setStartDelay(60)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
    }

    // ─── Text / number formatting helpers ─────────────────────────────

    /** Strip Modrinth markdown into readable plain text. */
    private static String stripMarkdown(String md) {
        if (md == null) return "";
        String s = md;
        s = s.replaceAll("(?s)```.*?```", " ");              // fenced code
        s = s.replaceAll("!\\[[^\\]]*]\\([^)]*\\)", "");     // images
        s = s.replaceAll("<img[^>]*>", "");                  // html images
        s = s.replaceAll("<br\\s*/?>", "\n");                // html breaks
        s = s.replaceAll("</?[^>]+>", "");                   // other html tags
        s = s.replaceAll("\\[([^\\]]+)]\\(([^)]*)\\)", "$1"); // links → text
        s = s.replaceAll("(?m)^#{1,6}\\s*", "");             // headings
        s = s.replaceAll("(?m)^\\s*>\\s?", "");              // quotes
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");       // bold
        s = s.replaceAll("__([^_]+)__", "$1");
        s = s.replaceAll("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)", "$1"); // italics
        s = s.replaceAll("`([^`\\n]*)`", "$1");              // inline code
        s = s.replaceAll("(?m)^\\s*[-*+]\\s+", "• ");        // bullets
        s = s.replaceAll("(?m)^\\s*[-_=]{3,}\\s*$", "");     // hr rules
        s = s.replaceAll("\n{3,}", "\n\n");
        s = s.trim();
        if (s.length() > 6000) s = s.substring(0, 6000).trim() + "…";
        return s;
    }

    private static String formatCount(long n) {
        if (n < 0) return "—";
        if (n >= 1_000_000_000L) return String.format(java.util.Locale.US, "%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000L) return String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000L) return String.format(java.util.Locale.US, "%.1fK", n / 1_000.0);
        return Long.toString(n);
    }

    private static long parseCount(String s) {
        if (s == null) return -1;
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return -1;
        try {
            return Long.parseLong(digits);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String formatIsoDate(String iso) {
        if (iso == null || iso.length() < 10) return null;
        try {
            java.text.SimpleDateFormat in =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Date date = in.parse(iso.substring(0, 10));
            if (date == null) return null;
            return new java.text.SimpleDateFormat("dd MMM yyyy",
                    java.util.Locale.US).format(date);
        } catch (Exception e) {
            return null;
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String[] capitalizeAll(String[] arr) {
        if (arr == null) return null;
        String[] out = new String[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = capitalize(arr[i]);
        return out;
    }

    // ─── Premium Entry Animations ──────────────────────────────────────

    private void setupInstallAnimations() {
        // Phase 10 dossier: the poster column slides in from the start edge,
        // the reader column's cards rise in sequence (anime.js-style timeline).
        View poster = getView() != null ? getView().findViewById(R.id.install_poster) : null;
        if (poster != null) net.kdt.pojavlaunch.Anime.in(poster, net.kdt.pojavlaunch.Anime.Fx.FADE_RIGHT, 0, 560, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        if (mScrollContent instanceof ViewGroup && ((ViewGroup) mScrollContent).getChildCount() > 0) {
            View col = ((ViewGroup) mScrollContent).getChildAt(0);
            if (col instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) col;
                int k = 0;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View c = vg.getChildAt(i);
                    if (c.getVisibility() != View.VISIBLE) continue;
                    net.kdt.pojavlaunch.Anime.in(c, net.kdt.pojavlaunch.Anime.Fx.FADE_UP, 120 + k * 70, 520, net.kdt.pojavlaunch.Anime.OUT_EXPO);
                    k++;
                }
            }
        }
        if (mTopBar != null) {
            mTopBar.setAlpha(0f);
            mTopBar.animate().alpha(1f).setStartDelay(200).setDuration(260).start();
        }

        if (mBottomBar != null) {
            // Keep the CTA inside its safe deck at every animation frame.
            // The old 80px slide + overshoot could leave it clipped on short
            // landscape screens when a transition was interrupted.
            mBottomBar.setTranslationY(12f);
            mBottomBar.setAlpha(0f);
            mBottomBar.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(220)
                    .setStartDelay(40)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }

        // Start staggered content layout animation
        if (mScrollContent != null) {
            View content = mScrollContent;
            if (content instanceof ViewGroup) {
                ((ViewGroup) content).startLayoutAnimation();
            }
        }

        // Restrained CTA reveal: alpha only, so it never grows outside the deck.
        if (mInstallButton != null) {
            mInstallButton.setScaleX(1f);
            mInstallButton.setScaleY(1f);
            mInstallButton.setAlpha(0f);
            mInstallButton.animate().alpha(1f).setStartDelay(120).setDuration(180).start();
        }

        // Premium button press scale effect
        if (mInstallButton != null) {
            mInstallButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().alpha(0.82f).setDuration(70).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().alpha(1f).setDuration(110).start();
                        break;
                }
                return false;
            });
        }

        // Premium back button press scale effect
        if (mBackButton != null) {
            mBackButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(70).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                        break;
                }
                return false;
            });
        }
    }

    // ─── Download & Dependency Logic ──────────────────────────────────

    private void startDownload(String url, String fileName) {
        Context ctx = getContext();
        if (ctx == null) return;

        // Modpack: use handleInstallation which creates a full instance
        if (mModItem != null && mModItem.isModpack) {
            mInstallButton.setEnabled(false);
            mInstallButton.setText("Installing modpack...");
            ModpackApi api;
            if (mModItem.apiSource == Constants.SOURCE_MODRINTH) {
                api = new ModrinthApi();
            } else {
                api = new CommonApi(requireContext().getString(R.string.curseforge_api_key));
            }
            api.handleInstallation(ctx, mModDetail, mVersionIndex);
            return;
        }

        // Individual mod: check for dependencies
        if (mModDetail != null && mModDetail.versionDependencyIds != null
                && mVersionIndex >= 0 && mVersionIndex < mModDetail.versionDependencyIds.length) {
            showDependencyDialog(ctx, url, fileName);
        } else {
            downloadMod(ctx, url, fileName,
                    new String[0], new String[0]);
        }
    }


    private void showDependencyDialog(Context ctx, String url, String fileName) {
        String[] depIds = mModDetail.versionDependencyIds[mVersionIndex];
        String[] depNames = new String[depIds != null ? depIds.length : 0];
        if (depIds != null) {
            for (int i = 0; i < depIds.length; i++) {
                depNames[i] = "Dependency: " + depIds[i];
            }
        }
        String[] depTypes = mModDetail.versionDependencyTypes[mVersionIndex];
        if (depIds == null || depIds.length == 0) {
            downloadMod(ctx, url, fileName, new String[0], new String[0]);
            return;
        }

        boolean[] selected = new boolean[depIds.length];
        for (int i = 0; i < depIds.length; i++) {
            selected[i] = true;
        }

        new androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.mod_deps_title)
                .setMultiChoiceItems(depNames, selected,
                        (dialog, which, isChecked) -> selected[which] = isChecked)
                .setPositiveButton(R.string.mod_deps_install_selected, (d, w) -> {
                    List<String> list = new ArrayList<>();
                    for (int i = 0; i < depIds.length; i++) {
                        if (selected[i]) list.add(depIds[i]);
                    }
                    downloadMod(ctx, url, fileName,
                            list.toArray(new String[0]), depTypes);
                })
                .setNeutralButton(R.string.mod_deps_install_without,
                        (d, w) -> downloadMod(ctx, url, fileName,
                                new String[0], new String[0]))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void downloadMod(Context ctx, String url, String fileName,
                              String[] depIds, String[] depTypes) {
        File targetDir = getContentDir();
        if (!targetDir.exists()) {
            boolean created = targetDir.mkdirs();
            if (created) Log.d("CS_LAUNCHER", "Created directory: " + targetDir.getAbsolutePath());
        }

        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        mInstallButton.setEnabled(false);
        mInstallButton.setText("Downloading...");

        PojavApplication.sExecutorService.execute(() -> {
            try {
                File targetFile = new File(targetDir, fileName);
                
                String title = mModItem != null ? mModItem.title : "Mod";
                String ver = (mModDetail != null && mModDetail.versionNames != null && mVersionIndex >= 0 && mVersionIndex < mModDetail.versionNames.length) ? mModDetail.versionNames[mVersionIndex] : "";
                String imgUrl = mModItem != null ? mModItem.imageUrl : null;
                
                net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper monitor = 
                    new net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper(
                        R.string.modpack_download_downloading_mods, 
                        ProgressLayout.INSTALL_MODPACK, 
                        title, ver, imgUrl, mContentType
                    );

                DownloadUtils.downloadFileMonitored(url, targetFile, null, monitor);

                // For worlds, extract ZIP and delete archive
                if ("world".equals(mContentType)) {
                    boolean ok = extractZip(targetFile, targetDir);
                    if (ok) targetFile.delete();
                }

                for (String depId : depIds) {
                    if (depId == null || depId.isEmpty()) continue;
                    downloadDependency(depId, targetDir);
                }

                // Safe update-replace: if this profile already had this store
                // project installed, drop the superseded jar so old + new
                // versions never coexist. Runs only after the new file is fully
                // on disk, so a failed/cancelled update never loses the working
                // old version.
                try {
                    if (mModItem != null && mModItem.id != null) {
                        String pk = (mInstallKey != null && !mInstallKey.isEmpty())
                                ? mInstallKey : mProfileKey;
                        if (pk == null || pk.isEmpty()) {
                            pk = net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF.getString(
                                    net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
                        }
                        removeSupersededFile(ctx, targetDir, pk, mContentType, mModItem.id, fileName);
                    }
                } catch (Throwable t) {
                    Log.w("ModInstall", "Superseded-file cleanup skipped", t);
                }
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    String msg = ctx.getString(R.string.mod_install_success, fileName);
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                    // Record the install so store cards show ✓ Installed / Update states
                    try {
                        if (mModItem != null && mModItem.id != null) {
                            String key = mInstallKey != null && !mInstallKey.isEmpty()
                                    ? mInstallKey : mProfileKey;
                            if (key == null || key.isEmpty()) {
                                key = net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF.getString(
                                        net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
                            }
                            net.kdt.pojavlaunch.modloaders.modpacks.InstalledContentTracker.markInstalled(
                                    ctx, key, mContentType, mModItem.id, ver, fileName);
                        }
                    } catch (Exception ignored) {}
                    // Pop back stack to mod list
                    getParentFragmentManager().popBackStack(
                            ModsSearchFragment.TAG,
                            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
                    );
                });
            } catch (Exception e) {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    mInstallButton.setEnabled(true);
                    mInstallButton.setText(R.string.mod_install_now);
                    if (net.kdt.pojavlaunch.utils.DownloadControl.isCancellation(e)) {
                        // User pressed STOP on the download console — no error dialog.
                        Toast.makeText(ctx, R.string.download_console_stopped, Toast.LENGTH_SHORT).show();
                    } else {
                        Tools.showErrorRemote(ctx, R.string.modpack_install_download_failed, e);
                    }
                });
            }
        });
    }

    private boolean extractZip(File zipFile, File destDir) {
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(new java.io.FileInputStream(zipFile))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract " + zipFile.getName(), e);
            return false;
        }
    }

    /**
     * Update safety (no duplicate jars): delete the previously recorded file for
     * this (profile, type, store project) when the freshly downloaded file has
     * a different name. Identity comes from the store project id, and the
     * recorded value must be a plain local file name — unrelated files in the
     * same folder are never touched.
     */
    private void removeSupersededFile(Context ctx, File dir, String profileKey,
                                      String contentType, String modId, String newFileName) {
        String old = net.kdt.pojavlaunch.modloaders.modpacks.InstalledContentTracker
                .getRecordedFileName(ctx, profileKey, contentType, modId);
        if (old == null || old.isEmpty() || old.equals(newFileName)) return;
        if (old.contains("/") || old.contains("\\") || old.contains("..")) return;
        File stale = new File(dir, old);
        if (stale.exists() && !stale.delete()) {
            Log.w("ModInstall", "Could not delete superseded file: " + old);
        }
    }

    private void downloadDependency(String projectId, File depDir) {
        try {
            ModrinthApi api = new ModrinthApi();
            ModItem depItem = new ModItem(SOURCE_MODRINTH, false,
                    projectId, projectId, "", "");
            ModDetail depDetail = api.getModDetails(depItem);
            if (depDetail == null || depDetail.versionUrls == null
                    || depDetail.versionUrls.length == 0) return;
            String depUrl = depDetail.versionUrls[0];
            String depName = depUrl.substring(depUrl.lastIndexOf('/') + 1);
            if (depName.contains("?")) depName = depName.substring(0, depName.indexOf('?'));
            if (!depName.endsWith(".jar")) depName += ".jar";
            
            String depTitle = depItem.title != null && !depItem.title.isEmpty() ? depItem.title : projectId;
            net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper depMonitor = 
                new net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper(
                    R.string.modpack_download_downloading_mods, 
                    ProgressLayout.INSTALL_MODPACK, 
                    depTitle, "", depItem.imageUrl, mContentType
                );
            DownloadUtils.downloadFileMonitored(depUrl, new File(depDir, depName), null, depMonitor);
        } catch (Exception e) {
            Log.w(TAG, "Failed to download dependency " + projectId);
        }
    }

    private File getContentDir() {
        // Per-world datapack installs bypass profile resolution entirely.
        if (mTargetDirPath != null && !mTargetDirPath.isEmpty()) {
            File direct = new File(mTargetDirPath);
            //noinspection ResultOfMethodCallIgnored
            direct.mkdirs();
            return direct;
        }
        try {
            String key = mProfileKey != null ? mProfileKey
                    : LauncherPreferences.DEFAULT_PREF.getString(
                            LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
            File profileDir = null;
            if (key != null && !key.isEmpty()) {
                LauncherProfiles.load();
                MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(key);
                if (profile != null) profileDir = Tools.getGameDirPath(profile);
            }
            File baseDir = profileDir != null ? profileDir : new File(Tools.DIR_GAME_NEW);
            return ModDownloadHelper.getDestinationDir(baseDir, mContentType);
        } catch (Exception ignored) {}
        return ModDownloadHelper.getDestinationDir(new File(Tools.DIR_GAME_NEW), mContentType);
    }

    /** Tiny downscale → upscale = cheap blur-like wash of the icon for the poster. */
    private static android.graphics.Bitmap softWash(android.graphics.Bitmap src) {
        int w = Math.max(1, src.getWidth()), h = Math.max(1, src.getHeight());
        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(src, Math.max(4, w / 24), Math.max(4, h / 24), true);
        android.graphics.Bitmap out = android.graphics.Bitmap.createScaledBitmap(small, 96, 96, true);
        if (small != out) small.recycle();
        return out;
    }

    // ══ Auto-sliding hero backdrop ═══════════════════════════════════════════
    /**
     * Loads every one of the mod's artwork images and crossfades them behind the
     * hero card, so the page shows the project's real screenshots moving.
     */
    private void startHeroSlideshow(String[] urls) {
        if (urls == null || urls.length == 0 || mHeroBackdropA == null) return;
        ModIconCache cache = ModIconCache.getInstance();
        for (String url : urls) {
            if (url == null || url.isEmpty()) continue;
            cache.getImage(bitmap -> {
                if (!isAdded() || bitmap == null) return;
                if (mBackdrops.contains(bitmap)) return;
                mBackdrops.add(bitmap);
                if (mBackdrops.size() == 1) {
                    showBackdrop(bitmap, true);
                    scheduleBackdrop();
                }
            }, "hero_" + url, url);
        }
    }

    private void showBackdrop(android.graphics.Bitmap bitmap, boolean instant) {
        ImageView front = mBackdropFrontA ? mHeroBackdropA : mHeroBackdropB;
        ImageView back  = mBackdropFrontA ? mHeroBackdropB : mHeroBackdropA;
        if (front == null) return;
        front.setImageBitmap(bitmap);
        front.setScaleX(1f);
        front.setScaleY(1f);
        front.animate().cancel();
        front.setAlpha(0f);
        front.animate()
                .alpha(1f)
                .setDuration(instant ? 420 : 700)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        // Slow, gentle Ken Burns drift — premium but restrained.
        front.animate().scaleX(1.06f).scaleY(1.06f)
                .setDuration(6000)
                .setInterpolator(new android.view.animation.LinearInterpolator())
                .start();
        if (back != null) {
            back.animate().cancel();
            back.animate().alpha(0f)
                    .setDuration(instant ? 0 : 700)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    private void scheduleBackdrop() {
        if (mBackdropHandler == null) {
            mBackdropHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        mBackdropHandler.removeCallbacks(mBackdropTick);
        mBackdropHandler.postDelayed(mBackdropTick, 4600);
    }

    private void advanceBackdrop() {
        if (!isAdded() || mBackdrops.isEmpty()) return;
        if (mBackdrops.size() == 1) { scheduleBackdrop(); return; }
        mBackdropIndex = (mBackdropIndex + 1) % mBackdrops.size();
        mBackdropFrontA = !mBackdropFrontA;
        showBackdrop(mBackdrops.get(mBackdropIndex), false);
        scheduleBackdrop();
    }

    private void stopBackdrop() {
        if (mBackdropHandler != null) mBackdropHandler.removeCallbacks(mBackdropTick);
        for (android.graphics.Bitmap b : mBackdrops) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        mBackdrops.clear();
    }

    /** True when this exact file already sits in the profile's folder. */
    private boolean isFileAlreadyInstalled(String fileName) {
        if (fileName == null || fileName.isEmpty()) return false;
        try {
            File dir = getContentDir();
            return new File(dir, fileName).isFile()
                    || new File(dir, fileName + ".disabled").isFile();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override public void onDestroyView() {
        stopBackdrop();
        super.onDestroyView();
    }
}
