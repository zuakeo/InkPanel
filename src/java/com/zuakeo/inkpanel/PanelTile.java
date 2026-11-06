package com.zuakeo.inkpanel;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class PanelTile extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        update();
    }

    @Override
    public void onClick() {
        super.onClick();
        try {
            Intent intent = new Intent(Const.ACTION);
            intent.setPackage(Const.SYSTEMUI_PKG);
            sendBroadcast(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "InkPanel: " + t, Toast.LENGTH_LONG).show();
        }
    }

    private void update() {
        try {
            Tile tile = getQsTile();
            if (tile != null) {
                tile.setState(Tile.STATE_INACTIVE);
                tile.setLabel(getString(R.string.tile_label));
                tile.updateTile();
            }
        } catch (Throwable ignored) {
        }
    }
}
