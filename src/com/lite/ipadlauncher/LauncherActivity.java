package com.lite.ipadlauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

/** Головний екран (HOME). Увесь інтерфейс малюється одним View для мінімального навантаження. */
public class LauncherActivity extends Activity {

    private HomeView home;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (home != null) {
                home.reloadSoon();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        home = new HomeView(this);
        setContentView(home);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        home.reload();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Натискання кнопки Home повертає на першу сторінку.
        if (intent != null && Intent.ACTION_MAIN.equals(intent.getAction())) {
            home.goHome();
        }
    }

    @Override
    public void onBackPressed() {
        // Лаунчер не закривається кнопкою «Назад».
        home.goHome();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(pkgReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
