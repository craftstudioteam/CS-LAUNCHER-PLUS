package com.kdt.mcgui;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.NetworkInfo;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.core.content.res.ResourcesCompat;


import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.authenticator.listener.DoneListener;
import net.kdt.pojavlaunch.authenticator.listener.ErrorListener;
import net.kdt.pojavlaunch.authenticator.listener.ProgressListener;
import net.kdt.pojavlaunch.authenticator.microsoft.PresentedException;
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.extra.ExtraListener;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import android.util.TypedValue;

import fr.spse.extended_view.ExtendedTextView;

public class mcAccountSpinner extends AppCompatSpinner implements AdapterView.OnItemSelectedListener {
    public mcAccountSpinner(@NonNull Context context) {
        this(context, null);
    }
    public mcAccountSpinner(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private final List<String> mAccountList = new ArrayList<>(2);
    private MinecraftAccount mSelectecAccount = null;
    private android.app.Dialog mAccountDialog;

    /* Display the head of the current profile, here just to allow bitmap recycling */
    private BitmapDrawable mHeadDrawable;

    /* Current animator to for the login bar, is swapped when changing step */
    private ObjectAnimator mLoginBarAnimator;
    private float mLoginBarWidth = -1;

    /* Paint used to display the bottom bar, to show the login progress. */
    private final Paint mLoginBarPaint = new Paint();

    /* When a login is performed in the background, we need to know where we are */
    private final static int MAX_LOGIN_STEP = 5;
    private int mLoginStep = 0;

    /* Login listeners */
    private final ProgressListener mProgressListener = step -> {
        // Animate the login bar, cosmetic purposes only
        mLoginStep = step;
        if(mLoginBarAnimator != null){
            mLoginBarAnimator.cancel();
            mLoginBarAnimator.setFloatValues( mLoginBarWidth, (getWidth()/MAX_LOGIN_STEP * mLoginStep));
        }else{
            mLoginBarAnimator = ObjectAnimator.ofFloat(this, "LoginBarWidth", mLoginBarWidth, (getWidth()/MAX_LOGIN_STEP * mLoginStep));
        }
        mLoginBarAnimator.start();
    };

    private final DoneListener mDoneListener = account -> {
        Toast.makeText(getContext(), R.string.main_login_done, Toast.LENGTH_SHORT).show();

        // Fetch skin face in background
        if (!account.isLocal()) {
            PojavApplication.sExecutorService.execute(() -> {
                account.updateSkinFace();
                Tools.runOnUiThread(() -> reloadAccounts(false, mAccountList.indexOf(account.username)));
            });
        }

        // Check if the account being added is not one that is already existing
        // Like login twice on the same mc account...
        for(String mcAccountName : mAccountList){
            if(mcAccountName.equals(account.username)) return;
        }

        mSelectecAccount = account;
        invalidate();
        mAccountList.add(account.username);
        reloadAccounts(false, mAccountList.size() -1);
    };

    private final ErrorListener mErrorListener = errorMessage -> {
        mLoginBarPaint.setColor(Color.RED);
        Context context = getContext();
        if(errorMessage instanceof PresentedException) {
            PresentedException exception = (PresentedException) errorMessage;
            Throwable cause = exception.getCause();
            if(cause == null) {
                Tools.dialog(context, context.getString(R.string.global_error), exception.toString(context));
            }else {
                Tools.showError(context, exception.toString(context), exception.getCause());
            }
        }else {
            Tools.showError(getContext(), errorMessage);
        }
        invalidate();
    };

    /* Triggered when we need to do microsoft login */
    private final ExtraListener<Uri> mMicrosoftLoginListener = (key, value) -> {
        mLoginBarPaint.setColor(getThemeColor(net.kdt.pojavlaunch.R.attr.themeAccent));
        new MicrosoftBackgroundLogin(false, value.getQueryParameter("code")).performLogin(
                mProgressListener, mDoneListener, mErrorListener);
        return false;
    };

    /* Triggered when we need to perform mojang login */
    private final ExtraListener<String[]> mMojangLoginListener = (key, value) -> {
        if(value[1].isEmpty()){ // Test mode
            MinecraftAccount account = new MinecraftAccount();
            account.username = value[0];
            try {
                account.save();
            }catch (IOException e){
                Log.e("McAccountSpinner", "Failed to save the account : " + e);
            }

            mDoneListener.onLoginDone(account);
        }
        return false;
    };


    @SuppressLint("ClickableViewAccessibility")
    private void init(){
        // Set visual properties
        setBackgroundColor(getThemeColor(net.kdt.pojavlaunch.R.attr.colorBgStatusBar));
        mLoginBarPaint.setColor(getThemeColor(net.kdt.pojavlaunch.R.attr.themeAccent));
        mLoginBarPaint.setStrokeWidth(getResources().getDimensionPixelOffset(R.dimen._2sdp));

        // Set behavior
        reloadAccounts(true, 0);
        setOnItemSelectedListener(this);

        ExtraCore.addExtraListener(ExtraConstants.MOJANG_LOGIN_TODO, mMojangLoginListener);
        ExtraCore.addExtraListener(ExtraConstants.MICROSOFT_LOGIN_TODO, mMicrosoftLoginListener);
    }


    @Override
    public final void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if(position == 0){  // Add account button
            if(mAccountList.size() > 1){
                ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            }
            return;
        }

        pickAccount(position);
        if(mSelectecAccount != null)
            performLogin(mSelectecAccount);
    }

    @Override public boolean performClick(){
        if(mAccountList.size()<=1){ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD,true);return true;}
        if(mAccountDialog!=null&&mAccountDialog.isShowing())return true;
        final AccountAdapter source=getAdapter() instanceof AccountAdapter?(AccountAdapter)getAdapter():null;
        if(source==null)return super.performClick();
        showAccountPicker(source);
        return true;
    }

    /**
     * Account picker — graphite sheet, rows built from item_account_pick.xml and
     * revealed with an anime-style timeline (card outBack scale-in → rows
     * stagger(55) fade-up → active check pops). Same behaviour as before:
     * row 0 = add / sign in, other rows = switch + login, bin = remove.
     */
    private void showAccountPicker(final AccountAdapter source){
        final android.app.Dialog dialog=new android.app.Dialog(getContext());
        mAccountDialog=dialog;
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_account_picker);
        dialog.setCanceledOnTouchOutside(true);
        final View sheet=dialog.findViewById(R.id.acc_sheet);
        final LinearLayout list=dialog.findViewById(R.id.acc_list);
        final View scroll=dialog.findViewById(R.id.acc_scroll);
        final TextView count=dialog.findViewById(R.id.acc_count);
        final View close=dialog.findViewById(R.id.acc_close);
        final View footer=dialog.findViewById(R.id.acc_footer);
        final int n=source.getCount();
        count.setText(String.valueOf(Math.max(0,n-1)));

        // Rows: position 0 is "add account" (dashed), the rest are real accounts.
        final java.util.List<View> rows=new java.util.ArrayList<>(n);
        for(int p=0;p<n;p++){
            final int pos=p;
            View row=LayoutInflater.from(getContext()).inflate(R.layout.item_account_pick,list,false);
            source.bindAccount(row,pos,false);
            if(pos==0){
                row.setBackgroundResource(R.drawable.acc_row_add);
                ImageView head=row.findViewById(R.id.account_head);
                head.setColorFilter(0xFFC9CED8);
            }else{
                MinecraftAccount acc=source.accountCache.get(source.getItem(pos));
                boolean active=source.selected(source.getItem(pos));
                row.setBackgroundResource(active?R.drawable.acc_row_active:R.drawable.acc_row);
                View check=row.findViewById(R.id.account_check);
                check.setVisibility(active?View.VISIBLE:View.GONE);
                ImageView del=row.findViewById(R.id.delete_account_button);
                del.setOnClickListener(v->{
                    net.kdt.pojavlaunch.Anime.shake(row);
                    // Phase 8: the launcher's own confirm popup instead of the system dialog.
                    String who=acc!=null&&acc.username!=null?acc.username:source.getItem(pos);
                    String kind=acc==null?"":acc.isMicrosoft?"MICROSOFT":acc.isElyByAccount()?"ELY.BY":"LOCAL";
                    new net.kdt.pojavlaunch.ui.CsConfirmDialog(getContext())
                            .kicker("REMOVE ACCOUNT")
                            .title(getContext().getString(R.string.warning_remove_account))
                            .message(who+" will be signed out and removed from this launcher. Worlds, mods and settings stay on the device.")
                            .detail(kind.isEmpty()?who:who+"  \u00b7  "+kind)
                            .danger(getContext().getString(R.string.global_delete),()->{dialog.dismiss();onDetachedFromWindow();removeAccount(pos);})
                            .show();
                });
            }
            net.kdt.pojavlaunch.UiMotion.pressFeedback(row);
            row.setOnClickListener(v->{
                // acknowledge, then leave: the tapped row pulses, the others recede
                for(View o:rows){ if(o!=row){ o.animate().cancel(); o.animate().alpha(0.35f).scaleX(0.97f).scaleY(0.97f).setDuration(160).setInterpolator(net.kdt.pojavlaunch.Anime.OUT_QUART).start(); } }
                View check=row.findViewById(R.id.account_check);
                if(pos!=0&&check!=null){check.setVisibility(View.VISIBLE);net.kdt.pojavlaunch.Anime.pop(check);}
                net.kdt.pojavlaunch.Anime.pulse(row);
                row.postDelayed(()->{
                    closeAccountPicker(dialog,sheet,()->{
                        if(pos==0){ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD,true);}
                        else{pickAccount(pos);if(mSelectecAccount!=null)performLogin(mSelectecAccount);}
                    });
                },200);
            });
            list.addView(row);
            rows.add(row);
        }
        // Cap the list height so long account lists scroll inside the sheet.
        int maxH=Math.min(dp(300),dp(75)*n);
        scroll.getLayoutParams().height=maxH;
        scroll.setLayoutParams(scroll.getLayoutParams());

        net.kdt.pojavlaunch.UiMotion.pressFeedback(close);
        close.setOnClickListener(v->closeAccountPicker(dialog,sheet,null));
        dialog.setOnDismissListener(d->mAccountDialog=null);

        android.view.Window w=dialog.getWindow();
        if(w!=null){
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams lp=w.getAttributes();
            lp.gravity=android.view.Gravity.CENTER;lp.dimAmount=.62f;w.setAttributes(lp);
            w.setWindowAnimations(0); // motion driven below
        }
        dialog.show();
        if(w!=null){int screen=getResources().getDisplayMetrics().widthPixels;w.setLayout(Math.min(dp(430),screen-dp(32)),android.view.WindowManager.LayoutParams.WRAP_CONTENT);}

        // ── timeline ──
        net.kdt.pojavlaunch.Anime.in(sheet,net.kdt.pojavlaunch.Anime.Fx.SCALE_IN,0,380,net.kdt.pojavlaunch.Anime.OUT_BACK);
        net.kdt.pojavlaunch.Anime.stagger(list,120,55,net.kdt.pojavlaunch.Anime.Fx.FADE_UP);
        for(View row:rows){
            View check=row.findViewById(R.id.account_check);
            if(check!=null&&check.getVisibility()==View.VISIBLE) check.postDelayed(()->net.kdt.pojavlaunch.Anime.pop(check),420);
        }
        net.kdt.pojavlaunch.Anime.in(footer,net.kdt.pojavlaunch.Anime.Fx.FADE_UP,120+55L*n+80,420,net.kdt.pojavlaunch.Anime.OUT_EXPO);
        net.kdt.pojavlaunch.Anime.in(close,net.kdt.pojavlaunch.Anime.Fx.POP,260,420,net.kdt.pojavlaunch.Anime.OUT_BACK);
    }

    private void closeAccountPicker(final android.app.Dialog dialog,View sheet,final Runnable after){
        if(sheet==null||sheet.getTag()!=null){ try{dialog.dismiss();}catch(Throwable ignored){} if(after!=null)after.run(); return; }
        sheet.setTag("closing");
        net.kdt.pojavlaunch.Anime.out(sheet,net.kdt.pojavlaunch.Anime.Fx.SCALE_IN,200,()->{
            try{dialog.dismiss();}catch(Throwable ignored){}
            if(after!=null)after.run();
        });
    }

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}

    @Override
    public final void onNothingSelected(AdapterView<?> parent) {}


    @Override
    protected void onDraw(Canvas canvas) {
        if(mLoginBarWidth == -1) mLoginBarWidth = getWidth(); // Initial draw

        float bottom = getHeight() - mLoginBarPaint.getStrokeWidth()/2;
        canvas.drawLine(0, bottom, mLoginBarWidth, bottom, mLoginBarPaint);
    }

    public void removeCurrentAccount(){
        removeAccount(getSelectedItemPosition());
    }

    private void removeAccount(int position) {
        if(position == 0) return;
        File accountFile = new File(Tools.DIR_ACCOUNT_NEW, mAccountList.get(position)+".json");
        if(accountFile.exists()) accountFile.delete();
        mAccountList.remove(position);

        reloadAccounts(false, 0);
    }

    @Keep
    public void setLoginBarWidth(float value){
        mLoginBarWidth = value;
        invalidate(); // Need to redraw each time this is changed
    }

    /** Allows checking whether we have an online account */
    public boolean isAccountOnline(){
        return mSelectecAccount != null && !mSelectecAccount.accessToken.equals("0");
    }

    public MinecraftAccount getSelectedAccount(){
        return mSelectecAccount;
    }

    public int getLoginState(){
        return mLoginStep;
    }

    public boolean isLoginDone(){
        return mLoginStep >= MAX_LOGIN_STEP;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setNoAccountBehavior(){
        // Set custom behavior when no account are present, to make it act as a button
        if(mAccountList.size() != 1){
            // Remove any touch listener
            setOnTouchListener(null);
            return;
        }

        // Make the spinner act like a button, since there is no item to really select
        setOnTouchListener((v, event) -> {
            if(event.getAction() != MotionEvent.ACTION_UP) return false;
            // The activity should intercept this and spawn another fragment
            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            return true;
        });
    }

    /**
     * Reload the spinner, from memory or from scratch. A default account can be selected
     * @param fromFiles Whether we use files as the source of truth
     * @param overridePosition Force the spinner to be at this position, if not 0
     */
    public void reloadAccounts(boolean fromFiles, int overridePosition){
        if(fromFiles){
            mAccountList.clear();

            mAccountList.add(getContext().getString(R.string.main_add_account));
            File accountFolder = new File(Tools.DIR_ACCOUNT_NEW);
            if(accountFolder.exists()){
                for (String fileName : accountFolder.list()) {
                    mAccountList.add(fileName.substring(0, fileName.length() - 5));
                }
            }
        }

        String[] accountArray = mAccountList.toArray(new String[0]);
        AccountAdapter accountAdapter = new AccountAdapter(getContext(), R.layout.item_minecraft_account, accountArray);
        accountAdapter.setDropDownViewResource(R.layout.item_minecraft_account);
        setAdapter(accountAdapter);

        // Pick what's available, might just be the the add account "button"
        pickAccount(overridePosition == 0 ? -1 : overridePosition);
        if(mSelectecAccount != null)
            performLogin(mSelectecAccount);

        // Remove or add the behavior if needed
        setNoAccountBehavior();

    }

    private void performLogin(MinecraftAccount minecraftAccount){
        // Logging in when there's no internet is useless. This should really be turned into a network callback though.
        if(!Tools.isOnline(getContext())){
            return;
        }
        if(minecraftAccount.isLocal()) return;

        mLoginBarPaint.setColor(getThemeColor(net.kdt.pojavlaunch.R.attr.themeAccent));
        if(minecraftAccount.isMicrosoft){
            if(System.currentTimeMillis() > minecraftAccount.expiresAt){
                // Perform login only if needed
                new MicrosoftBackgroundLogin(true, minecraftAccount.msaRefreshToken)
                        .performLogin(mProgressListener, mDoneListener, mErrorListener);
            }
            return;
        }
    }

    /** Pick the selected account, the one in settings if 0 is passed */
    private void pickAccount(int position){
        MinecraftAccount selectedAccount;
        if(position != -1){
            PojavProfile.setCurrentProfile(getContext(), mAccountList.get(position));
            selectedAccount = PojavProfile.getCurrentProfileContent(getContext(), mAccountList.get(position));

            // WORKAROUND
            // Account file corrupted due to previous versions having improper encoding
            if (selectedAccount == null){
                Context ctx = Objects.requireNonNull(getContext());

                new AlertDialog.Builder(ctx)
                        .setCancelable(false)
                        .setTitle(R.string.account_corrupted)
                        .setMessage(R.string.login_again)
                        .setPositiveButton(R.string.delete_account_and_login, (dialog, which) -> {
                            removeCurrentAccount();
                            pickAccount(-1);
                            setSelection(0);
                        })
                        .show();


            }
            setSelection(position);
        }else {
            // Get the current profile, or the first available profile if the wanted one is unavailable
            selectedAccount = PojavProfile.getCurrentProfileContent(getContext(), null);
            int spinnerPosition = selectedAccount == null
                    ? mAccountList.size() <= 1 ? 0 : 1
                    : mAccountList.indexOf(selectedAccount.username);
            setSelection(spinnerPosition, false);
        }

        mSelectecAccount = selectedAccount;
        setImageFromSelectedAccount();
        if (getContext() instanceof LauncherActivity) {
            ((LauncherActivity) getContext()).updateNavSkinIcon();
        }
        // Real state change → notify every listener (Home rebinds name/avatar/skin/3D player).
        if (selectedAccount != null) {
            ExtraCore.setValue(ExtraConstants.ACCOUNT_CHANGED, selectedAccount.username);
        }
    }

    private void setImageFromSelectedAccount(){
        android.widget.SpinnerAdapter adapter=getAdapter();
        if(adapter instanceof android.widget.BaseAdapter)((android.widget.BaseAdapter)adapter).notifyDataSetChanged();
        post(()->{View selected=getSelectedView();if(selected==null)return;View head=selected.findViewById(R.id.account_head);if(head!=null){head.animate().cancel();head.setAlpha(0f);head.setScaleX(.86f);head.setScaleY(.86f);head.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();}});
        invalidate();
    }

    private String accountSubtitle(MinecraftAccount account){
        if(account==null)return "ADD OR SELECT ACCOUNT";
        if(account.isMicrosoft)return "PREMIUM • OFFICIAL SKIN";
        if(account.isElyByAccount())return "ELY.BY • ONLINE SKIN";
        try{File meta=new File(Tools.DIR_DATA+"/skins/"+account.username+"_metadata.json");if(meta.isFile()){org.json.JSONObject o=new org.json.JSONObject(Tools.read(meta.getAbsolutePath()));int slot=o.optInt("slot",0);if(slot>0)return "LOCAL • ACTIVE SKIN SLOT "+slot;}}catch(Throwable ignored){}
        return "LOCAL ACCOUNT";
    }

    private class AccountAdapter extends ArrayAdapter<String> {
        private final HashMap<String,MinecraftAccount> accountCache=new HashMap<>();
        AccountAdapter(@NonNull Context context,int resource,@NonNull String[]objects){super(context,resource,objects);for(int i=1;i<objects.length;i++){MinecraftAccount a=PojavProfile.getCurrentProfileContent(context,objects[i]);if(a!=null)accountCache.put(objects[i],a);}}
        private boolean selected(String username){return mSelectecAccount!=null&&username!=null&&username.equals(mSelectecAccount.username);}
        /** Accounts whose premium skin has already been (re)validated this session. */
        private final java.util.HashSet<String> mPremiumChecked=new java.util.HashSet<>();
        /**
         * Phase 10: the row head is the exact 3D render of the account's skin.
         * Microsoft accounts get their sheet validated against Mojang once per
         * session (SkinResolver refuses a stale/local sheet for a premium
         * account now) — the head is drawn from whatever is on disk right away
         * and swapped for the real one the moment the fetch lands.
         */
        private void bindHead(ImageView head,@Nullable MinecraftAccount account,String username){
            final String name=account!=null&&account.username!=null?account.username:username;
            head.setTag(R.id.account_head,name);
            Bitmap now=net.kdt.pojavlaunch.ui.SkinHead3DRenderer.resolve(getResources(),name);
            if(now!=null)head.setImageBitmap(now);
            if(account==null||!account.isMicrosoft)return;
            final java.io.File sheet=net.kdt.pojavlaunch.skins.SkinResolver.skinFile(name);
            if(mPremiumChecked.contains(name)&&net.kdt.pojavlaunch.skins.SkinResolver.isUsableSkin(sheet)
                    &&net.kdt.pojavlaunch.skins.SkinResolver.isPremiumCache(sheet,account))return;
            mPremiumChecked.add(name);
            final MinecraftAccount acc=account;
            PojavApplication.sExecutorService.execute(()->{
                try{
                    java.io.File got=net.kdt.pojavlaunch.skins.SkinResolver.resolve(acc);
                    if(got==null)return;
                    net.kdt.pojavlaunch.ui.SkinHead3DRenderer.invalidate(name);
                    final Bitmap fresh=net.kdt.pojavlaunch.ui.SkinHead3DRenderer.resolve(getResources(),name);
                    if(fresh==null)return;
                    Tools.runOnUiThread(()->{
                        if(!name.equals(head.getTag(R.id.account_head)))return; // row recycled
                        head.setImageBitmap(fresh);
                        head.animate().cancel();head.setAlpha(0.4f);head.animate().alpha(1f).setDuration(220).start();
                        if(selected(name)){
                            Context c=getContext();
                            if(c instanceof LauncherActivity)((LauncherActivity)c).updateNavSkinIcon();
                        }
                    });
                }catch(Throwable ignored){}
            });
        }
        private void bindAccount(View row,int position,boolean compact){ImageView head=row.findViewById(R.id.account_head);TextView name=row.findViewById(R.id.account_name);TextView subtitle=row.findViewById(R.id.account_subtitle);String username=getItem(position);name.setText(username);
            if(position==0){head.setImageResource(R.drawable.ic_add);subtitle.setText("CREATE OR SIGN IN");if(!compact){View badge=row.findViewById(R.id.account_badge);if(badge!=null)badge.setVisibility(View.GONE);View check=row.findViewById(R.id.account_check);if(check!=null)check.setVisibility(View.GONE);View del=row.findViewById(R.id.delete_account_button);if(del!=null)del.setVisibility(View.GONE);}return;}
            MinecraftAccount account=accountCache.get(username);bindHead(head,account,username);subtitle.setText(accountSubtitle(account));
            if(!compact){TextView badge=row.findViewById(R.id.account_badge);badge.setVisibility(View.VISIBLE);badge.setText(account!=null&&account.isMicrosoft?"MICROSOFT":account!=null&&account.isElyByAccount()?"ELY.BY":"LOCAL");View root=row.findViewById(R.id.account_row);root.setBackgroundResource(account!=null&&account.isMicrosoft?R.drawable.bg_account_row_microsoft:selected(username)?R.drawable.bg_account_row_active:R.drawable.bg_account_row);View check=row.findViewById(R.id.account_check);check.setVisibility(selected(username)?View.VISIBLE:View.GONE);ImageView del=row.findViewById(R.id.delete_account_button);del.setVisibility(View.VISIBLE);del.setOnClickListener(v->showDeleteDialog(getContext(),position));}
        }
        @Override public View getDropDownView(int position,@Nullable View convertView,@NonNull ViewGroup parent){if(convertView==null||!"drop".equals(convertView.getTag())){convertView=LayoutInflater.from(parent.getContext()).inflate(R.layout.item_minecraft_account,parent,false);convertView.setTag("drop");}bindAccount(convertView,position,false);return convertView;}
        @NonNull @Override public View getView(int position,@Nullable View convertView,@NonNull ViewGroup parent){if(convertView==null||!"selected".equals(convertView.getTag())){convertView=LayoutInflater.from(parent.getContext()).inflate(R.layout.item_minecraft_account_selected,parent,false);convertView.setTag("selected");}bindAccount(convertView,position,true);return convertView;}
        private void showDeleteDialog(Context context,int position){new AlertDialog.Builder(context).setMessage(R.string.warning_remove_account).setPositiveButton(android.R.string.cancel,null).setNeutralButton(R.string.global_delete,(dialog,which)->{onDetachedFromWindow();removeAccount(position);}).show();}
    }

    /** Resolve a theme colour attribute (e.g. R.attr.themeAccent) to an int colour. */
    private int getThemeColor(int attr) {
        TypedValue tv = new TypedValue();
        getContext().getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }
}