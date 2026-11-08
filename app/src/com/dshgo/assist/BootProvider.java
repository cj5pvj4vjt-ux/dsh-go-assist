package com.dshgo.assist;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * v6：我们自己的 ContentProvider，唯一作用是启动轨迹打点。
 *
 * ContentProvider 的 onCreate 在 Application.onCreate 之前执行，所以它的那条
 * 轨迹能告诉我们「进程启动到 provider 阶段了」——如果 boot-trace 里只有
 * attachBaseContext 而没到这一条，问题就在更早的地方。
 */
public class BootProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        BootTrace.mark("BootProvider.onCreate");
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}