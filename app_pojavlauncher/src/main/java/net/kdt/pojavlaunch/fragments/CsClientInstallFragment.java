package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.csclient.CsClientCatalog;
import net.kdt.pojavlaunch.csclient.CsClientInstallView;
import net.kdt.pojavlaunch.csclient.CsClientInstaller;
import net.kdt.pojavlaunch.csclient.CsClientSelection;

import java.util.ArrayList;

/** Exclusive full-screen installer for CS Client. It never publishes ProgressKeeper records. */
public class CsClientInstallFragment extends Fragment {
    public static final String TAG="CS_CLIENT_INSTALL";
    private CsClientCatalog.VersionEntry version;
    private CsClientInstallView visual;
    private TextView exit;
    private boolean started;
    private int previousSystemUi;
    private int lastPercent=1;
    private String lastStatus="Preparing isolated profile";

    public CsClientInstallFragment(){super(R.layout.fragment_cs_client_install);}

    @Override public void onCreate(@Nullable Bundle state){
        super.onCreate(state);setRetainInstance(true);
        version=(CsClientCatalog.VersionEntry)requireArguments().getSerializable("version");
        requireActivity().getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){@Override public void handleOnBackPressed(){/* installation owns the screen */}});
    }

    @Override public void onViewCreated(@NonNull View root,@Nullable Bundle state){
        View decor=requireActivity().getWindow().getDecorView();previousSystemUi=decor.getSystemUiVisibility();decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        visual=root.findViewById(R.id.csc_full_install_visual);exit=root.findViewById(R.id.csc_install_exit);
        visual.setInstallProgress(lastPercent,lastStatus);visual.start();exit.setVisibility(View.GONE);
        exit.setOnClickListener(v->requireActivity().getSupportFragmentManager().popBackStack());
        root.setAlpha(0f);root.animate().alpha(1f).setDuration(320).start();
        if(!started){started=true;startInstall();}
    }

    private void startInstall(){
        CsClientInstaller.install(requireContext().getApplicationContext(),version,
                new ArrayList<>(CsClientSelection.mods),CsClientSelection.modpack,
                new CsClientInstaller.Callback(){
                    @Override public void onStatus(String text,int percent){
                        lastStatus=text;lastPercent=percent;if(visual!=null)visual.setInstallProgress(percent,text);
                    }
                    @Override public void onDone(boolean ok,String message){
                        lastStatus=message;lastPercent=ok?100:0;
                        if(visual!=null){if(ok)visual.complete(message);else visual.fail(message);}
                        if(ok){CsClientSelection.clear();if(visual!=null)visual.postDelayed(()->{if(isAdded())Tools.backToMainMenu(requireActivity());},1450);}
                        else if(exit!=null){exit.setVisibility(View.VISIBLE);exit.setAlpha(0f);exit.animate().alpha(1f).setDuration(220).start();Toast.makeText(requireContext(),message,Toast.LENGTH_LONG).show();}
                    }
                });
    }

    @Override public void onDestroyView(){requireActivity().getWindow().getDecorView().setSystemUiVisibility(previousSystemUi);if(visual!=null)visual.stop();visual=null;exit=null;super.onDestroyView();}
}
