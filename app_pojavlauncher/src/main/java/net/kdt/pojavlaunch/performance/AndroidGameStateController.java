package net.kdt.pojavlaunch.performance;

import android.app.GameManager;
import android.app.GameState;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/** System-awareness only; never changes Minecraft quality, FPS, VSync, or heap. */
public final class AndroidGameStateController {
    private final GameManager manager;
    public AndroidGameStateController(Context context){manager=Build.VERSION.SDK_INT>=33?context.getSystemService(GameManager.class):null;}
    public void loading(){set(true,GameState.MODE_CONTENT);}
    public void playing(){set(false,GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE);}
    public void paused(){set(false,GameState.MODE_GAMEPLAY_INTERRUPTIBLE);}
    private void set(boolean loading,int mode){if(Build.VERSION.SDK_INT<33||manager==null)return;try{manager.setGameState(new GameState(loading,mode));}catch(Throwable e){Log.w("GameState","Game State API unavailable",e);}}
}
