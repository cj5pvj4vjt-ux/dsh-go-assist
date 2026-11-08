package com.dshgo.assist;

import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * v38：快捷设置磁贴「本地模型」——一键开关本地小模型（打游戏时随手关掉）。
 *   - 亮 = 先问本地 0.6B（省流量、离线快）
 *   - 灭 = 全部交给 DSH
 * 另外：前台有游戏时，App 会自动跳过本地模型（见 OverlayService.askLocalThenMaybeDsh）。
 */
public class LocalTileService extends TileService {

    static final String PREFS = "assist";

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        try {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean now = sp.getBoolean(RootOps.prefKeyLocalFirst(), true);
            sp.edit().putBoolean(RootOps.prefKeyLocalFirst(), !now).apply();
            OverlayLog.mark("tile local model -> " + (!now));
            if (now) {
                // 关掉时顺手让服务卸载模型，把内存让出来
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        LocalRouter.unload();
                    }
                }, "tile-unload").start();
            }
        } catch (Throwable t) {
            OverlayLog.mark("tile local FAIL " + t.getClass().getSimpleName());
        }
        updateTile();
    }

    private void updateTile() {
        try {
            Tile tile = getQsTile();
            if (tile == null) {
                return;
            }
            boolean on = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(RootOps.prefKeyLocalFirst(), true);
            tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(on ? "本地模型 开" : "本地模型 关");
            tile.updateTile();
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
