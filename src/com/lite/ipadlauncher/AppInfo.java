package com.lite.ipadlauncher;

import android.content.ComponentName;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;

/** Один встановлений додаток у списку лаунчера. */
final class AppInfo {
    final ComponentName cn;
    final String key;
    final String label;
    String shortLabel;
    volatile Bitmap icon;
    /** Тимчасово, поки іконка не завантажена у фоновому потоці. */
    ResolveInfo ri;

    AppInfo(ComponentName cn, String label) {
        this.cn = cn;
        this.key = cn.flattenToString();
        this.label = label;
    }
}
