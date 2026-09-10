package net.kdt.pojavlaunch.fragments;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.csclient.CsClientCatalog;
import net.kdt.pojavlaunch.csclient.CsClientSelection;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class CsClientPickerFragment extends Fragment {
    public static final String TAG="CS_CLIENT_PICKER";
    private static final LruCache<String,Bitmap> ICONS=new LruCache<String,Bitmap>(8*1024){
        @Override protected int sizeOf(String key,Bitmap value){return Math.max(1,value.getByteCount()/1024);}
    };
    private String mc;private boolean pack;private LinearLayout list;private ProgressBar loading;private EditText search;
    private View dock;private TextView dockCount;private TextView countPill;
    private final List<RowBinding> bindings=new ArrayList<>();

    public CsClientPickerFragment(){super(R.layout.fragment_cs_client_picker);}
    @Override public void onCreate(@Nullable Bundle state){super.onCreate(state);mc=requireArguments().getString("mc");pack=requireArguments().getBoolean("pack");}

    @Override public void onViewCreated(@NonNull View root,@Nullable Bundle state){
        list=root.findViewById(R.id.csc_picker_list);loading=root.findViewById(R.id.csc_picker_loading);search=root.findViewById(R.id.csc_picker_search);
        dock=root.findViewById(R.id.csc_picker_dock);
        dockCount=root.findViewById(R.id.csc_picker_dock_count);
        countPill=root.findViewById(R.id.csc_picker_count);
        ((TextView)root.findViewById(R.id.csc_picker_title)).setText(pack?"Choose one modpack":"Choose mods");
        ((TextView)root.findViewById(R.id.csc_picker_subtitle)).setText("Minecraft "+mc+" • Fabric compatible only");
        root.findViewById(R.id.csc_picker_back).setOnClickListener(v->back());root.findViewById(R.id.csc_picker_done).setOnClickListener(v->back());
        root.findViewById(R.id.csc_picker_search_go).setOnClickListener(v->load(search.getText().toString()));
        search.setOnEditorActionListener((v,a,e)->{load(search.getText().toString());return true;});
        UiMotion.pressFeedback(root.findViewById(R.id.csc_picker_back),root.findViewById(R.id.csc_picker_done),root.findViewById(R.id.csc_picker_search_go));
        LinearLayout page=(LinearLayout)root;enter(page.getChildAt(0),0,-8);enter(page.getChildAt(1),55,-6);
        search.postDelayed(()->{if(isAdded())search.animate().alpha(1f).setDuration(200).start();},90);
        refreshDock();
        load("");
    }

    /** Count of currently selected items (1 for a chosen modpack, n for mods). */
    private int selectionCount(){
        if(pack) return CsClientSelection.modpack!=null?1:0;
        return CsClientSelection.mods==null?0:CsClientSelection.mods.size();
    }

    /** Show/hide the floating dock + refresh both count labels with a pop. */
    private void refreshDock(){
        int n=selectionCount();
        String label = pack ? (n>0?"1 modpack selected":"No modpack selected")
                            : (n+" module"+(n==1?"":"s")+" selected");
        if(dockCount!=null) dockCount.setText(label);
        if(countPill!=null){
            countPill.setText(String.valueOf(n));
            if(n>0){
                countPill.setScaleX(0.6f); countPill.setScaleY(0.6f);
                countPill.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
            }
        }
        if(dock==null) return;
        boolean show=n>0;
        if(show && dock.getVisibility()!=View.VISIBLE){
            dock.setVisibility(View.VISIBLE);
            dock.setAlpha(0f);
            dock.setTranslationY(dp(34));
            dock.animate().cancel();
            dock.animate().alpha(1f).translationY(0f).setDuration(300)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f)).start();
        }else if(!show && dock.getVisibility()==View.VISIBLE){
            dock.animate().cancel();
            dock.animate().alpha(0f).translationY(dp(34)).setDuration(180)
                    .withEndAction(()->dock.setVisibility(View.GONE)).start();
        }
    }

    private void load(String query){
        loading.setVisibility(View.VISIBLE);list.removeAllViews();bindings.clear();
        PojavApplication.sExecutorService.execute(()->{
            try{
                List<CsClientCatalog.ProjectEntry> results=CsClientCatalog.search(query,mc,pack);
                Tools.runOnUiThread(()->{
                    if(!isAdded())return;loading.setVisibility(View.GONE);long delay=0;
                    for(CsClientCatalog.ProjectEntry entry:results){
                        RowBinding binding=row(entry);bindings.add(binding);list.addView(binding.root);
                        binding.root.setAlpha(0f);binding.root.setTranslationY(dp(8));
                        binding.root.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(210).start();delay+=22;
                    }
                });
            }catch(Exception ignored){Tools.runOnUiThread(()->{if(isAdded()){loading.setVisibility(View.GONE);Toast.makeText(requireContext(),"Search failed",Toast.LENGTH_SHORT).show();}});}
        });
    }

    private RowBinding row(CsClientCatalog.ProjectEntry entry){
        LinearLayout root=new LinearLayout(requireContext());root.setOrientation(LinearLayout.HORIZONTAL);root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(14),dp(10),dp(14),dp(10));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(80));lp.bottomMargin=dp(10);root.setLayoutParams(lp);
        root.setBackgroundResource(R.drawable.aur_row);

        ImageView icon=new ImageView(requireContext());icon.setScaleType(ImageView.ScaleType.CENTER_CROP);icon.setClipToOutline(true);icon.setBackgroundResource(R.drawable.csc_medallion);
        icon.setPadding(dp(6),dp(6),dp(6),dp(6));LinearLayout.LayoutParams ilp=new LinearLayout.LayoutParams(dp(54),dp(54));root.addView(icon,ilp);loadIcon(icon,entry.iconUrl,entry.modpack);

        LinearLayout textBox=new LinearLayout(requireContext());textBox.setOrientation(LinearLayout.VERTICAL);textBox.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textLp=new LinearLayout.LayoutParams(0,-1,1);textLp.leftMargin=dp(13);textLp.rightMargin=dp(8);root.addView(textBox,textLp);
        TextView title=new TextView(requireContext());title.setTextColor(0xFFF4F6F9);title.setTextSize(13);title.setTypeface(null,1);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setIncludeFontPadding(false);
        TextView desc=new TextView(requireContext());desc.setText(entry.description);desc.setTextColor(0xFF9AA0AC);desc.setTextSize(10);desc.setMaxLines(2);desc.setEllipsize(android.text.TextUtils.TruncateAt.END);desc.setIncludeFontPadding(false);desc.setLineSpacing(0,1.05f);
        textBox.addView(title);textBox.addView(desc);

        TextView check=new TextView(requireContext());check.setGravity(Gravity.CENTER);check.setTextSize(14);check.setTypeface(null,1);LinearLayout.LayoutParams clp=new LinearLayout.LayoutParams(dp(30),dp(30));root.addView(check,clp);
        RowBinding binding=new RowBinding(root,title,check,entry);binding.render(false);
        root.setOnClickListener(v->{
            if(pack)CsClientSelection.modpack=selected(entry)?null:entry;else CsClientSelection.toggleMod(entry);
            for(RowBinding b:bindings)b.render(true);
            refreshDock();
        });
        UiMotion.pressFeedback(root);return binding;
    }

    private void loadIcon(ImageView view,String url,boolean isPack){
        view.setImageResource(isPack?R.drawable.ic_cs_modpack:R.drawable.ic_cs_mods);
        if(url==null||url.isEmpty())return;view.setTag(url);Bitmap cached=ICONS.get(url);if(cached!=null){view.setPadding(0,0,0,0);view.setImageBitmap(cached);return;}
        PojavApplication.sExecutorService.execute(()->{
            HttpURLConnection connection=null;
            try{connection=(HttpURLConnection)new URL(url).openConnection();connection.setConnectTimeout(7000);connection.setReadTimeout(9000);connection.setRequestProperty("User-Agent","CS-Launcher-Plus/1.0");
                try(InputStream input=connection.getInputStream()){Bitmap bitmap=BitmapFactory.decodeStream(input);if(bitmap!=null){ICONS.put(url,bitmap);Tools.runOnUiThread(()->{if(isAdded()&&url.equals(view.getTag())){view.setPadding(0,0,0,0);view.setImageBitmap(bitmap);view.setAlpha(0f);view.animate().alpha(1f).setDuration(180).start();}});}}
            }catch(Exception ignored){}finally{if(connection!=null)connection.disconnect();}
        });
    }

    private final class RowBinding{
        final LinearLayout root;final TextView title,check;final CsClientCatalog.ProjectEntry entry;
        RowBinding(LinearLayout root,TextView title,TextView check,CsClientCatalog.ProjectEntry entry){this.root=root;this.title=title;this.check=check;this.entry=entry;}
        void render(boolean animate){boolean yes=selected(entry);root.setBackgroundResource(yes?R.drawable.aur_row_selected:R.drawable.aur_row);
            title.setText(entry.title);check.setText(yes?"✓":"+");check.setTextColor(yes?0xff141519:0xff8A8F99);
            check.setBackgroundResource(yes?R.drawable.aur_mark:R.drawable.aur_mark_idle);
            if(animate){check.setScaleX(.75f);check.setScaleY(.75f);check.setAlpha(.4f);check.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).start();}}
    }

    private boolean selected(CsClientCatalog.ProjectEntry e){return pack?CsClientSelection.modpack!=null&&CsClientSelection.modpack.id.equals(e.id):CsClientSelection.contains(e.id);}
    private void back(){requireActivity().getSupportFragmentManager().popBackStack();}
    private void enter(View v,long delay,float y){v.setAlpha(0f);v.setTranslationY(dp((int)y));v.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(240).start();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
