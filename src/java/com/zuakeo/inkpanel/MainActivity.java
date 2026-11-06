package com.zuakeo.inkpanel;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean warmOnly = getIntent() != null
                && getIntent().getBooleanExtra("warm_only", false);

        try {
            Intent warm = new Intent(Const.ACTION_WARM);
            warm.setPackage(Const.SYSTEMUI_PKG);
            sendBroadcast(warm);
        } catch (Throwable ignored) {
        }

        try {
            TileService.requestListeningState(this,
                    new ComponentName(this, PanelTile.class));
        } catch (Throwable t) {
            Toast.makeText(this, "InkPanel: " + t, Toast.LENGTH_LONG).show();
        }

        if (warmOnly) {
            finish();
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent(Const.ACTION);
                    intent.setPackage(Const.SYSTEMUI_PKG);
                    sendBroadcast(intent);
                } catch (Throwable ignored) {
                }
                finish();
            }
        }, 1200);
    }
}
