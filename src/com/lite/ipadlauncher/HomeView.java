package com.lite.ipadlauncher;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextThemeWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Scroller;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Весь екран лаунчера в одному View: годинник, пошук, сторінки з іконками, крапки, док.
 * Ніяких ViewPager/GridView/адаптерів: лише Canvas, тому працює швидко на слабкому планшеті.
 * Використовує тільки API, доступні в Android 4.0 (API 14-15).
 */
public class HomeView extends View {

    private static final int M_NONE = 0;
    private static final int M_SCROLL = 1;
    private static final int M_DRAG = 2;
    private static final int M_DONE = 3;
    private static final int DOCK_MAX = 7;

    private final LauncherActivity act;
    private final PackageManager pm;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler();
    private final float dp;
    private final int iconSize;
    private final int slop;

    private final Paint iconPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint phPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint datePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final RectF tmp = new RectF();
    private final RectF dockRect = new RectF();
    private final RectF pillRect = new RectF();
    private final float[] dockX = new float[DOCK_MAX + 1];

    private final Map<String, Bitmap> iconCache =
            Collections.synchronizedMap(new HashMap<String, Bitmap>());

    private ArrayList<AppInfo> apps = new ArrayList<AppInfo>();
    private ArrayList<AppInfo> dock = new ArrayList<AppInfo>();

    // Розкладка
    private int w, h;
    private int cols = 5, rows = 4, perPage = 20, pageCount = 1, page = 0;
    private float pad, cellW, cellH, gridTop, gridBottom, dockTop;

    // Прокрутка
    private final Scroller scroller;
    private float offset;
    private float startOffset;
    private VelocityTracker vt;

    // Дотики
    private int mode = M_NONE;
    private float downX, downY, dragX, dragY;
    private int pressIdx = -1, pressDock = -1;
    private AppInfo dragApp;
    private int dragFrom;
    private boolean dragMoved;
    private boolean edgePosted;

    private String timeStr = "", dateStr = "";

    public HomeView(LauncherActivity activity) {
        super(activity);
        act = activity;
        pm = activity.getPackageManager();
        prefs = activity.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;
        iconSize = (int) (60 * dp);
        slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        scroller = new Scroller(activity);

        iconPaint.setAlpha(255);
        phPaint.setColor(0x44FFFFFF);
        dockPaint.setColor(0x55FFFFFF);
        pillPaint.setColor(0x40FFFFFF);
        dotPaint.setColor(0xFFFFFFFF);

        labelPaint.setColor(0xFFFFFFFF);
        labelPaint.setTextSize(12 * dp);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setShadowLayer(2 * dp, 0, dp, 0xAA000000);

        timePaint.setColor(0xFFFFFFFF);
        timePaint.setTextSize(46 * dp);
        timePaint.setTextAlign(Paint.Align.CENTER);
        timePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        timePaint.setShadowLayer(3 * dp, 0, dp, 0x88000000);

        datePaint.setColor(0xFFFFFFFF);
        datePaint.setTextSize(15 * dp);
        datePaint.setTextAlign(Paint.Align.CENTER);
        datePaint.setShadowLayer(2 * dp, 0, dp, 0x88000000);

        pillTextPaint.setColor(0xDDFFFFFF);
        pillTextPaint.setTextSize(14 * dp);
        pillTextPaint.setTextAlign(Paint.Align.CENTER);

        updateTime();
    }

    // ---------------------------------------------------------------- життєвий цикл

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateTime();
            invalidate();
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        act.registerReceiver(timeReceiver, f);
        updateTime();
    }

    @Override
    protected void onDetachedFromWindow() {
        try {
            act.unregisterReceiver(timeReceiver);
        } catch (Exception ignored) {
        }
        super.onDetachedFromWindow();
    }

    private void updateTime() {
        Date now = new Date();
        boolean h24 = DateFormat.is24HourFormat(act);
        timeStr = new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now);
        String d = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now);
        if (d.length() > 0) {
            d = Character.toUpperCase(d.charAt(0)) + d.substring(1);
        }
        dateStr = d;
    }

    // ---------------------------------------------------------------- завантаження додатків

    private final Runnable reloadRunnable = new Runnable() {
        @Override
        public void run() {
            reload();
        }
    };

    public void reloadSoon() {
        handler.removeCallbacks(reloadRunnable);
        handler.postDelayed(reloadRunnable, 600);
    }

    public void reload() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Intent main = new Intent(Intent.ACTION_MAIN, null);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
                String self = act.getPackageName();
                final ArrayList<AppInfo> list = new ArrayList<AppInfo>();
                for (ResolveInfo ri : ris) {
                    ActivityInfo ai = ri.activityInfo;
                    if (ai == null || self.equals(ai.packageName)) {
                        continue;
                    }
                    AppInfo a = new AppInfo(new ComponentName(ai.packageName, ai.name),
                            String.valueOf(ri.loadLabel(pm)));
                    a.icon = iconCache.get(a.key);
                    a.ri = ri;
                    list.add(a);
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        applyApps(list);
                    }
                });
                // Іконки завантажуються після показу списку, щоб екран з'явився швидко.
                int count = 0;
                for (AppInfo a : list) {
                    if (a.icon == null && a.ri != null) {
                        Bitmap b = makeIcon(a.ri.loadIcon(pm));
                        a.icon = b;
                        iconCache.put(a.key, b);
                        if (++count % 6 == 0) {
                            postInvalidate();
                        }
                    }
                    a.ri = null;
                }
                postInvalidate();
            }
        }).start();
    }

    private Bitmap makeIcon(Drawable d) {
        Bitmap b = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, iconSize, iconSize);
        d.draw(c);
        return b;
    }

    private void applyApps(ArrayList<AppInfo> list) {
        // Порядок: збережений користувачем, решта за алфавітом.
        final HashMap<String, Integer> pos = new HashMap<String, Integer>();
        ArrayList<String> saved = readKeys("order");
        for (int i = 0; i < saved.size(); i++) {
            pos.put(saved.get(i), i);
        }
        Collections.sort(list, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                int ia = pos.containsKey(a.key) ? pos.get(a.key) : Integer.MAX_VALUE;
                int ib = pos.containsKey(b.key) ? pos.get(b.key) : Integer.MAX_VALUE;
                if (ia != ib) {
                    return ia < ib ? -1 : 1;
                }
                return String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
            }
        });

        if (mode == M_DRAG) {
            mode = M_NONE;
            dragApp = null;
        }
        apps = list;

        // Док
        dock = new ArrayList<AppInfo>();
        String ds = prefs.getString("dock", null);
        if (ds == null) {
            addDefaultDock();
            saveList("dock", dock);
        } else {
            for (String k : readKeys("dock")) {
                AppInfo a = findByKey(k);
                if (a != null && !dock.contains(a)) {
                    dock.add(a);
                }
            }
        }

        computeLayout();
        invalidate();
    }

    private AppInfo findByKey(String key) {
        for (AppInfo a : apps) {
            if (a.key.equals(key)) {
                return a;
            }
        }
        return null;
    }

    private void addDefaultDock() {
        Intent gallery = new Intent(Intent.ACTION_VIEW);
        gallery.setDataAndType(Uri.parse("content://media/external/images/media"), "image/*");
        Intent[] probes = new Intent[] {
                new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.google.com")),
                new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
                new Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                gallery,
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent probe : probes) {
            List<ResolveInfo> l = pm.queryIntentActivities(probe, 0);
            if (l.isEmpty() || l.get(0).activityInfo == null) {
                continue;
            }
            String pkg = l.get(0).activityInfo.packageName;
            for (AppInfo a : apps) {
                if (a.cn.getPackageName().equals(pkg) && !dock.contains(a)) {
                    dock.add(a);
                    break;
                }
            }
        }
    }

    private ArrayList<String> readKeys(String name) {
        ArrayList<String> out = new ArrayList<String>();
        String s = prefs.getString(name, null);
        if (s != null) {
            for (String k : s.split("\n")) {
                if (k.length() > 0) {
                    out.add(k);
                }
            }
        }
        return out;
    }

    private void saveList(String name, ArrayList<AppInfo> l) {
        StringBuilder sb = new StringBuilder();
        for (AppInfo a : l) {
            sb.append(a.key).append('\n');
        }
        prefs.edit().putString(name, sb.toString()).apply();
    }

    // ---------------------------------------------------------------- розкладка

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        super.onSizeChanged(nw, nh, ow, oh);
        w = nw;
        h = nh;
        computeLayout();
    }

    private void computeLayout() {
        if (w <= 0 || h <= 0) {
            return;
        }
        pad = 24 * dp;
        gridTop = 140 * dp;
        float dockH = 84 * dp;
        dockTop = h - 10 * dp - dockH;
        gridBottom = dockTop - 34 * dp;
        float gridH = Math.max(gridBottom - gridTop, 100 * dp);

        cols = clamp((int) ((w - 2 * pad) / (150 * dp)), 4, 6);
        rows = clamp((int) (gridH / (112 * dp)), 2, 6);
        perPage = cols * rows;
        cellW = (w - 2 * pad) / cols;
        cellH = gridH / rows;
        pageCount = Math.max(1, (apps.size() + perPage - 1) / perPage);
        page = clamp(page, 0, pageCount - 1);
        scroller.forceFinished(true);
        offset = page * (float) w;

        for (AppInfo a : apps) {
            a.shortLabel = TextUtils.ellipsize(a.label, labelPaint, cellW - 8 * dp,
                    TextUtils.TruncateAt.END).toString();
        }

        int n = dock.size();
        float dw = n * 76 * dp + 24 * dp;
        float left = (w - dw) / 2f;
        dockRect.set(left, dockTop, left + dw, dockTop + dockH);
        for (int i = 0; i < n; i++) {
            dockX[i] = left + 12 * dp + 38 * dp + i * 76 * dp;
        }

        float pw = 220 * dp;
        pillRect.set((w - pw) / 2f, 94 * dp, (w + pw) / 2f, 124 * dp);
    }

    // ---------------------------------------------------------------- малювання

    @Override
    protected void onDraw(Canvas c) {
        if (w <= 0) {
            return;
        }
        // Годинник і пошук
        c.drawText(timeStr, w / 2f, 56 * dp, timePaint);
        c.drawText(dateStr, w / 2f, 80 * dp, datePaint);
        c.drawRoundRect(pillRect, 15 * dp, 15 * dp, pillPaint);
        c.drawText("Пошук", w / 2f, pillRect.centerY() + 5 * dp, pillTextPaint);

        // Сторінки (малюємо лише видимі)
        int first = clamp((int) Math.floor(offset / w), 0, pageCount - 1);
        int last = clamp(first + 1, 0, pageCount - 1);
        int n = apps.size();
        for (int p = first; p <= last; p++) {
            for (int s = 0; s < perPage; s++) {
                int i = p * perPage + s;
                if (i >= n) {
                    break;
                }
                AppInfo a = apps.get(i);
                if (mode == M_DRAG && a == dragApp) {
                    continue;
                }
                float cx = p * w - offset + pad + (s % cols) * cellW + cellW / 2f;
                float cy = gridTop + (s / cols) * cellH + cellH / 2f;
                drawApp(c, a, cx, cy, true);
            }
        }

        // Крапки сторінок
        if (pageCount > 1) {
            float step = 14 * dp;
            float x0 = (w - (pageCount - 1) * step) / 2f;
            float y = dockTop - 16 * dp;
            int cur = clamp(Math.round(offset / w), 0, pageCount - 1);
            for (int i = 0; i < pageCount; i++) {
                dotPaint.setAlpha(i == cur ? 255 : 90);
                c.drawCircle(x0 + i * step, y, 3.5f * dp, dotPaint);
            }
        }

        // Док
        if (!dock.isEmpty()) {
            c.drawRoundRect(dockRect, 22 * dp, 22 * dp, dockPaint);
            for (int i = 0; i < dock.size(); i++) {
                drawApp(c, dock.get(i), dockX[i], dockRect.centerY() + 10 * dp, false);
            }
        }

        // Іконка, яку перетягують
        if (mode == M_DRAG && dragApp != null) {
            float half = iconSize * 0.6f;
            tmp.set(dragX - half, dragY - half, dragX + half, dragY + half);
            if (dragApp.icon != null) {
                c.drawBitmap(dragApp.icon, null, tmp, iconPaint);
            } else {
                c.drawRoundRect(tmp, 12 * dp, 12 * dp, phPaint);
            }
        }
    }

    private void drawApp(Canvas c, AppInfo a, float cx, float cy, boolean label) {
        float top = cy - iconSize / 2f - 10 * dp;
        float left = cx - iconSize / 2f;
        Bitmap b = a.icon;
        if (b != null) {
            c.drawBitmap(b, left, top, iconPaint);
        } else {
            tmp.set(left, top, left + iconSize, top + iconSize);
            c.drawRoundRect(tmp, 12 * dp, 12 * dp, phPaint);
        }
        if (label) {
            String t = a.shortLabel != null ? a.shortLabel : a.label;
            c.drawText(t, cx, top + iconSize + 17 * dp, labelPaint);
        }
    }

    // ---------------------------------------------------------------- прокрутка сторінок

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            offset = scroller.getCurrX();
            invalidate();
        }
    }

    private void goToPage(int target) {
        target = clamp(target, 0, pageCount - 1);
        page = target;
        int from = (int) offset;
        int to = target * w;
        scroller.startScroll(from, 0, to - from, 0, 280);
        invalidate();
    }

    public void goHome() {
        if (page != 0 || offset != 0) {
            goToPage(0);
        }
    }

    private float clampOffset(float v) {
        return Math.max(0, Math.min(v, (pageCount - 1) * (float) w));
    }

    private void snap(float vx) {
        float base = offset / w;
        int target;
        if (vx < -600) {
            target = (int) Math.ceil(base);
        } else if (vx > 600) {
            target = (int) Math.floor(base);
        } else {
            target = Math.round(base);
        }
        goToPage(target);
    }

    // ---------------------------------------------------------------- дотики

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (mode != M_NONE) {
                return;
            }
            if (pressIdx >= 0 && pressIdx < apps.size()) {
                mode = M_DRAG;
                dragApp = apps.get(pressIdx);
                dragFrom = pressIdx;
                dragX = downX;
                dragY = downY;
                dragMoved = false;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                invalidate();
            } else if (pressDock >= 0 && pressDock < dock.size()) {
                mode = M_DONE;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showMenu(dock.get(pressDock));
            } else if (downY > gridTop && downY < gridBottom) {
                mode = M_DONE;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                try {
                    act.startActivity(Intent.createChooser(
                            new Intent(Intent.ACTION_SET_WALLPAPER), "Шпалери"));
                } catch (Exception e) {
                    Toast.makeText(act, "Не вдалося відкрити вибір шпалер",
                            Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    private final Runnable edgeRunnable = new Runnable() {
        @Override
        public void run() {
            edgePosted = false;
            if (mode != M_DRAG) {
                return;
            }
            float edge = 40 * dp;
            if (dragX < edge && page > 0) {
                goToPage(page - 1);
            } else if (dragX > w - edge && page < pageCount - 1) {
                goToPage(page + 1);
            } else {
                return;
            }
            edgePosted = true;
            handler.postDelayed(this, 800);
        }
    };

    private int hitApp(float x, float y) {
        if (Math.abs(offset - page * (float) w) > 2) {
            return -1;
        }
        if (y < gridTop || y > gridBottom || x < pad || x > w - pad) {
            return -1;
        }
        int col = (int) ((x - pad) / cellW);
        int row = (int) ((y - gridTop) / cellH);
        if (col < 0 || col >= cols || row < 0 || row >= rows) {
            return -1;
        }
        int idx = page * perPage + row * cols + col;
        return idx < apps.size() ? idx : -1;
    }

    private int hitDock(float x, float y) {
        if (dock.isEmpty() || !dockRect.contains(x, y)) {
            return -1;
        }
        for (int i = 0; i < dock.size(); i++) {
            if (Math.abs(x - dockX[i]) <= 38 * dp) {
                return i;
            }
        }
        return -1;
    }

    private int slotAt(float x, float y) {
        int col = clamp((int) ((x - pad) / cellW), 0, cols - 1);
        int row = clamp((int) ((y - gridTop) / cellH), 0, rows - 1);
        return page * perPage + row * cols + col;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) {
            vt = VelocityTracker.obtain();
        }
        vt.addMovement(e);
        float x = e.getX();
        float y = e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                mode = M_NONE;
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                    offset = scroller.getCurrX();
                    page = clamp(Math.round(offset / w), 0, pageCount - 1);
                    mode = M_SCROLL;
                    startOffset = offset;
                }
                pressIdx = hitApp(x, y);
                pressDock = hitDock(x, y);
                if (mode == M_NONE) {
                    handler.postDelayed(longPress, 450);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (mode == M_DRAG) {
                    dragX = x;
                    dragY = y;
                    if (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop) {
                        dragMoved = true;
                    }
                    float edge = 40 * dp;
                    boolean atEdge = x < edge || x > w - edge;
                    if (atEdge && !edgePosted) {
                        edgePosted = true;
                        handler.postDelayed(edgeRunnable, 600);
                    } else if (!atEdge && edgePosted) {
                        edgePosted = false;
                        handler.removeCallbacks(edgeRunnable);
                    }
                    invalidate();
                } else if (mode == M_SCROLL) {
                    offset = clampOffset(startOffset - (x - downX));
                    invalidate();
                } else if (mode == M_NONE
                        && (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop)) {
                    handler.removeCallbacks(longPress);
                    mode = M_SCROLL;
                    startOffset = offset;
                    downX = x;
                }
                return true;

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                if (mode == M_DRAG) {
                    finishDrag(x, y);
                } else if (mode == M_SCROLL) {
                    vt.computeCurrentVelocity(1000);
                    snap(vt.getXVelocity());
                } else if (mode == M_NONE) {
                    handleTap(x, y);
                }
                mode = (mode == M_DRAG) ? M_NONE : mode;
                releaseTracker();
                return true;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                handler.removeCallbacks(edgeRunnable);
                edgePosted = false;
                if (mode == M_SCROLL) {
                    snap(0);
                }
                mode = M_NONE;
                dragApp = null;
                releaseTracker();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void releaseTracker() {
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
    }

    private void handleTap(float x, float y) {
        if (pressDock >= 0 && pressDock < dock.size() && hitDock(x, y) == pressDock) {
            launch(dock.get(pressDock));
        } else if (pillRect.contains(x, y) && pillRect.contains(downX, downY)) {
            showSearch();
        } else if (pressIdx >= 0 && pressIdx < apps.size() && hitApp(x, y) == pressIdx) {
            launch(apps.get(pressIdx));
        }
    }

    private void finishDrag(float x, float y) {
        handler.removeCallbacks(edgeRunnable);
        edgePosted = false;
        AppInfo a = dragApp;
        int from = dragFrom;
        mode = M_NONE;
        dragApp = null;

        if (a != null) {
            if (y > dockTop) {
                addToDock(a);
            } else {
                int to = clamp(slotAt(x, y), 0, apps.size() - 1);
                if (to != from && from >= 0 && from < apps.size() && apps.get(from) == a) {
                    apps.remove(from);
                    apps.add(to, a);
                    saveList("order", apps);
                } else if (!dragMoved) {
                    showMenu(a);
                }
            }
        }
        invalidate();
    }

    // ---------------------------------------------------------------- дії

    private void launch(AppInfo a) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(a.cn);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            act.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(act, "Не вдалося запустити", Toast.LENGTH_SHORT).show();
        }
    }

    private void addToDock(AppInfo a) {
        if (dock.contains(a)) {
            return;
        }
        if (dock.size() >= DOCK_MAX) {
            dock.remove(0);
        }
        dock.add(a);
        saveList("dock", dock);
        computeLayout();
        invalidate();
    }

    private void removeFromDock(AppInfo a) {
        dock.remove(a);
        saveList("dock", dock);
        computeLayout();
        invalidate();
    }

    private void showMenu(final AppInfo a) {
        final boolean inDock = dock.contains(a);
        CharSequence[] items = new CharSequence[] {
                "Відкрити",
                "Про додаток",
                "Видалити",
                inDock ? "Прибрати з дока" : "Додати в док"
        };
        Context hc = new ContextThemeWrapper(act, android.R.style.Theme_Holo);
        new AlertDialog.Builder(hc)
                .setTitle(a.label)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pkg = a.cn.getPackageName();
                        try {
                            if (which == 0) {
                                launch(a);
                            } else if (which == 1) {
                                act.startActivity(new Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", pkg, null)));
                            } else if (which == 2) {
                                act.startActivity(new Intent(Intent.ACTION_DELETE,
                                        Uri.fromParts("package", pkg, null)));
                            } else if (inDock) {
                                removeFromDock(a);
                            } else {
                                addToDock(a);
                            }
                        } catch (Exception e) {
                            Toast.makeText(act, "Дію не вдалося виконати",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void showSearch() {
        Context hc = new ContextThemeWrapper(act, android.R.style.Theme_Holo);
        LinearLayout root = new LinearLayout(hc);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * dp);
        root.setPadding(p, p, p, p);

        final EditText et = new EditText(hc);
        et.setHint("Пошук додатків");
        et.setSingleLine(true);
        final ListView lv = new ListView(hc);
        final ArrayList<AppInfo> shown = new ArrayList<AppInfo>();
        final ArrayAdapter<String> ad = new ArrayAdapter<String>(hc,
                android.R.layout.simple_list_item_1, new ArrayList<String>());
        lv.setAdapter(ad);

        root.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(lv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (300 * dp)));

        final AlertDialog dlg = new AlertDialog.Builder(hc).setView(root).create();

        final TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String q = s.toString().trim().toLowerCase();
                shown.clear();
                ad.clear();
                for (AppInfo a : apps) {
                    if (q.length() == 0 || a.label.toLowerCase().contains(q)) {
                        shown.add(a);
                        ad.add(a.label);
                    }
                }
            }
        };
        et.addTextChangedListener(watcher);
        watcher.afterTextChanged(et.getText());

        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                if (pos >= 0 && pos < shown.size()) {
                    launch(shown.get(pos));
                }
                dlg.dismiss();
            }
        });

        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
    }
}
