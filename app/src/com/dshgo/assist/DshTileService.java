package com.dshgo.assist;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * v22：快捷设置磁贴「DSH 小V」——免 root 的唤起入口。
 *
 * 用法：下拉通知栏 → 编辑/添加图块 → 把「DSH 小V」拖出来，之后点一下即起悬浮胶囊。
 * 点击 = startForegroundService(OverlayService, voice=true)，不启动任何 Activity。
 */
public class DshTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        BootTrace.mark("tile clicked");
        try {
            Intent intent = new Intent(this, OverlayService.class)
                    .setAction(OverlayService.ACTION_START)
                    .putExtra(OverlayService.EXTRA_VOICE, true);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            BootTrace.mark("tile: OverlayService started");
        } catch (Throwable t) {
            BootTrace.mark("tile start FAIL " + t);
        }
        updateTile();
    }

    private void updateTile() {
        try {
            Tile tile = getQsTile();
            if (tile == null) {
                return;
            }
            tile.setLabel("DSH 小V");
            tile.setState(Tile.STATE_INACTIVE);
            tile.updateTile();
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
