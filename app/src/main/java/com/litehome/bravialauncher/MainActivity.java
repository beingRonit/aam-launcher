package com.litehome.bravialauncher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int DEFAULT_TINT = Color.rgb(70, 110, 190);
    private static final int TILE_RADIUS_DP = 18;
    private static final int COLUMNS = 4;
    private static final float EDGE_FADE_FRACTION = 0.55f; // row is fully opaque while >= 55% visible, fades to 0 as it leaves
    private static final int PANEL_FADE_DISTANCE_DP = 120; // dark card is fully gone after this much scroll
    private static final Interpolator EASE_OUT = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private static final Interpolator EASE_IN_OUT = new PathInterpolator(0.65f, 0f, 0.35f, 1f);
    // Gentle start: a restarted scroll (focus moves while still scrolling) does not jump to full speed.
    private static final Interpolator EASE_SCROLL = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);
    private static final Object HEADER = new Object();
    private static final Object FOOTER = new Object();
    private static final Map<String, Integer> KNOWN_TINTS = new HashMap<>();
    private static final Map<String, String> DEFAULT_LABELS = new HashMap<>();
    private static final int MAX_PINNED = 12;
    private static final String PREFS = "launcher";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_HIDDEN = "hidden";

    static {
        DEFAULT_LABELS.put("io.gh.reisxd.tizentube.cobalt", "TizenTube");
        DEFAULT_LABELS.put("in.startv.hotstar", "JioHotstar");
        DEFAULT_LABELS.put("com.spotify.tv.android", "Spotify");
        DEFAULT_LABELS.put("com.sonyliv", "SonyLIV");
        DEFAULT_LABELS.put("com.internet.tvbrowser", "Browser");
        DEFAULT_LABELS.put("com.tcl.browser", "Browser");
        DEFAULT_LABELS.put("com.android.vending", "Play Store");
        DEFAULT_LABELS.put("org.localsend.localsend_app", "LocalSend");
        DEFAULT_LABELS.put("com.alphainventor.filemanager", "File Manager+");
        KNOWN_TINTS.put("com.netflix.ninja", Color.rgb(229, 9, 20));
        KNOWN_TINTS.put("com.spotify.tv.android", Color.rgb(29, 185, 84));
        KNOWN_TINTS.put("com.google.android.youtube.tv", Color.rgb(255, 0, 0));
        KNOWN_TINTS.put("io.gh.reisxd.tizentube.cobalt", Color.rgb(255, 43, 43));
        KNOWN_TINTS.put("in.startv.hotstar", Color.rgb(42, 123, 255));
        KNOWN_TINTS.put("com.sonyliv", Color.rgb(91, 91, 255));
        KNOWN_TINTS.put("com.amazon.amazonvideo.livingroom", Color.rgb(0, 168, 225));
        KNOWN_TINTS.put("com.internet.tvbrowser", Color.rgb(45, 156, 219));
        KNOWN_TINTS.put("com.tcl.browser", Color.rgb(45, 156, 219));
        KNOWN_TINTS.put("com.android.vending", Color.rgb(52, 168, 83));
        KNOWN_TINTS.put("org.localsend.localsend_app", Color.rgb(20, 184, 166));
        KNOWN_TINTS.put("com.alphainventor.filemanager", Color.rgb(245, 166, 35));
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayList<AppEntry> homeApps = new ArrayList<>();
    private final ArrayList<AppEntry> allApps = new ArrayList<>();
    private final ArrayList<Object> rows = new ArrayList<>();
    private final ArrayList<String> pinned = new ArrayList<>();
    private final HashSet<String> hidden = new HashSet<>();
    private ArrayList<AppEntry> rawApps = new ArrayList<>();
    private SharedPreferences prefs;

    private LinearLayout root;
    private RecyclerView grid;
    private HomeAdapter adapter;
    private GlowView glow;
    private IconView netIcon;
    private TextView greeting;
    private TextView clockText;
    private TextView ampmText;
    private TextView dateText;
    private TextView focusedTitle;
    private Typeface mono;
    private ValueAnimator glowAnim;
    private AppEntry focusedEntry;
    private int focusedPosition = 0;
    private boolean loading = false;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000L - (now % 60_000L) + 50L);
        }
    };

    private final Runnable netTick = new Runnable() {
        @Override public void run() {
            refreshNetwork();
            handler.postDelayed(this, 4_000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { mono = Typeface.createFromAsset(getAssets(), "fonts/JetBrainsMono-Regular.otf"); }
        catch (Exception ignored) { mono = Typeface.MONOSPACE; }
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadPrefs();
        buildHomeAppList();
        rebuildRows();
        buildScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(netTick);
        clockTick.run();
        netTick.run();
        loadAllApps();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(netTick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (glowAnim != null) glowAnim.cancel();
        executor.shutdownNow();
        super.onDestroy();
    }

    // Back on a launcher should never exit: jump back to the first tile instead.
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        focusPosition(0, 0);
    }

    // ---------------------------------------------------------------- data

    private void loadPrefs() {
        pinned.clear();
        hidden.clear();
        String p = prefs.getString(KEY_PINNED, null);
        if (p == null) {
            pinned.add("io.gh.reisxd.tizentube.cobalt");
            pinned.add("in.startv.hotstar");
            pinned.add("com.spotify.tv.android");
            pinned.add("com.sonyliv");
            pinned.add(isInstalled("com.internet.tvbrowser") ? "com.internet.tvbrowser"
                    : isInstalled("com.tcl.browser") ? "com.tcl.browser" : "com.internet.tvbrowser");
            pinned.add("com.android.vending");
            pinned.add("org.localsend.localsend_app");
            pinned.add("com.alphainventor.filemanager");
        } else {
            for (String part : p.split("\n")) if (!part.isEmpty()) pinned.add(part);
        }
        for (String part : prefs.getString(KEY_HIDDEN, "").split("\n")) if (!part.isEmpty()) hidden.add(part);
    }

    private void savePrefs() {
        prefs.edit().putString(KEY_PINNED, TextUtils.join("\n", pinned))
                .putString(KEY_HIDDEN, TextUtils.join("\n", hidden)).apply();
    }

    private void buildHomeAppList() {
        homeApps.clear();
        java.util.Iterator<String> it = pinned.iterator();
        while (it.hasNext()) {
            String pkg = it.next();
            if (!DEFAULT_LABELS.containsKey(pkg) && !isInstalled(pkg)) { it.remove(); continue; }
            homeApps.add(new AppEntry(labelFor(pkg), pkg));
        }
    }

    private String labelFor(String pkg) {
        String d = DEFAULT_LABELS.get(pkg);
        if (d != null) return d;
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return pkg; }
    }

    private boolean isInstalled(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        try { getPackageManager().getApplicationInfo(packageName, 0); return true; }
        catch (PackageManager.NameNotFoundException exception) { return false; }
    }

    private void rebuildRows() {
        rows.clear();
        rows.addAll(homeApps);
        if (!allApps.isEmpty()) {
            rows.add(HEADER);
            rows.addAll(allApps);
        }
        rows.add(FOOTER);
    }

    private void loadAllApps() {
        if (loading) return;
        loading = true;
        final ArrayList<AppEntry> current = rawApps;
        try {
            executor.execute(() -> {
                final ArrayList<AppEntry> list = getInstalledApps();
                // Banner/icon lookups and tint extraction are slow. Do them here, never in onBindViewHolder.
                if (!samePackages(list, current)) preloadVisuals(list);
                runOnUiThread(() -> {
                    loading = false;
                    if (isDestroyed() || isFinishing()) return;
                    applyAllApps(list);
                });
            });
        } catch (Exception ignored) { loading = false; }
    }

    // Runs on the background executor, before the list is handed to the UI thread.
    private void preloadVisuals(List<AppEntry> list) {
        for (AppEntry e : list) {
            if (Thread.currentThread().isInterrupted()) return;
            ensureVisual(e);
            tintFor(e);
        }
    }

    private void applyAllApps(ArrayList<AppEntry> list) {
        if (samePackages(list, rawApps)) return;
        rawApps = list;
        if (adapter == null) return;
        refreshAll(focusedPosition);
    }

    // Rebuilds both sections from prefs + the installed-app list, then restores focus.
    private void refreshAll(int focusPos) {
        buildHomeAppList();
        allApps.clear();
        for (AppEntry a : rawApps) if (!hidden.contains(a.packageName)) allApps.add(a);
        rebuildRows();
        focusedEntry = null;
        adapter.notifyDataSetChanged();
        final int target = focusPos;
        grid.post(() -> focusPosition(target, 0));
    }

    private void showMenu(final AppEntry e, final int position) {
        if (position == RecyclerView.NO_POSITION) return;
        final boolean isPinned = pinned.contains(e.packageName);
        final int idx = pinned.indexOf(e.packageName);
        final int homeCount = homeApps.size();
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        if (isPinned) {
            if (idx > 0) {
                labels.add("Move earlier");
                actions.add(() -> { Collections.swap(pinned, idx, idx - 1); savePrefs(); refreshAll(idx - 1); });
            }
            if (idx < pinned.size() - 1) {
                labels.add("Move later");
                actions.add(() -> { Collections.swap(pinned, idx, idx + 1); savePrefs(); refreshAll(idx + 1); });
            }
            labels.add("Unpin from go-to apps");
            actions.add(() -> {
                pinned.remove(idx);
                savePrefs();
                refreshAll(position < homeCount ? Math.min(idx, Math.max(0, pinned.size() - 1)) : position - 1);
            });
        } else if (pinned.size() < MAX_PINNED) {
            labels.add("Pin to go-to apps");
            actions.add(() -> {
                pinned.add(e.packageName);
                savePrefs();
                Toast.makeText(MainActivity.this, e.label + " pinned", Toast.LENGTH_SHORT).show();
                refreshAll(position + 1);
            });
        }
        labels.add("Hide from All Apps");
        actions.add(() -> { hidden.add(e.packageName); savePrefs(); refreshAll(position); });
        if (!hidden.isEmpty()) {
            labels.add("Restore " + hidden.size() + " hidden apps");
            actions.add(() -> { hidden.clear(); savePrefs(); refreshAll(position); });
        }
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    private boolean samePackages(List<AppEntry> a, List<AppEntry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).packageName.equals(b.get(i).packageName)) return false;
        }
        return true;
    }

    private ArrayList<AppEntry> getInstalledApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> unique = new HashMap<>();
        String[] categories = {Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER};
        for (String category : categories) {
            Intent query = new Intent(Intent.ACTION_MAIN);
            query.addCategory(category);
            List<ResolveInfo> results = pm.queryIntentActivities(query, 0);
            for (ResolveInfo info : results) {
                if (info.activityInfo == null) continue;
                String packageName = info.activityInfo.packageName;
                if (packageName.equals(getPackageName())) continue;
                if (!unique.containsKey(packageName)) {
                    String label;
                    try { label = info.loadLabel(pm).toString(); }
                    catch (Exception exception) { label = packageName; }
                    unique.put(packageName, new AppEntry(label, packageName));
                }
            }
        }
        ArrayList<AppEntry> apps = new ArrayList<>(unique.values());
        Collections.sort(apps, Comparator.comparing(a -> a.label.toLowerCase(Locale.getDefault())));
        return apps;
    }

    private void ensureVisual(AppEntry e) {
        if (e.visualLoaded) return;
        e.visualLoaded = true;
        if (e.packageName == null || e.packageName.isEmpty()) return;
        PackageManager pm = getPackageManager();
        try { e.banner = pm.getApplicationBanner(e.packageName); } catch (Exception ignored) { }
        if (e.banner == null) {
            try {
                Intent q = new Intent(Intent.ACTION_MAIN);
                q.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                q.setPackage(e.packageName);
                List<ResolveInfo> l = pm.queryIntentActivities(q, 0);
                if (!l.isEmpty() && l.get(0).activityInfo != null) e.banner = l.get(0).activityInfo.loadBanner(pm);
            } catch (Exception ignored) { }
        }
        try { e.icon = pm.getApplicationIcon(e.packageName); } catch (Exception ignored) { }
    }

    private int tintFor(AppEntry e) {
        if (e.tintReady) return e.tint;
        Integer known = KNOWN_TINTS.get(e.packageName);
        int c;
        if (known != null) c = known;
        else { ensureVisual(e); c = extractTint(e); }
        e.tint = c;
        e.tintReady = true;
        return c;
    }

    // Saturation-weighted average of the banner/icon: white backgrounds are ignored, logos win.
    private int extractTint(AppEntry e) {
        Drawable d = e.banner != null ? e.banner : e.icon;
        if (d == null) return hashTint(e.packageName);
        try {
            final int w = 32, h = 18;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Rect old = d.copyBounds();
            d.setBounds(0, 0, w, h);
            d.draw(c);
            d.setBounds(old);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, wt = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int px = bmp.getPixel(x, y);
                    if (Color.alpha(px) < 160) continue;
                    Color.colorToHSV(px, hsv);
                    if (hsv[2] < 0.2f) continue;
                    double weight = hsv[1] * hsv[1] * hsv[2];
                    r += Color.red(px) * weight;
                    g += Color.green(px) * weight;
                    b += Color.blue(px) * weight;
                    wt += weight;
                }
            }
            bmp.recycle();
            if (wt < 0.5) return hashTint(e.packageName);
            int avg = Color.rgb((int) (r / wt), (int) (g / wt), (int) (b / wt));
            Color.colorToHSV(avg, hsv);
            hsv[1] = Math.max(hsv[1], 0.6f);
            hsv[2] = 1f;
            return Color.HSVToColor(hsv);
        } catch (Exception ex) {
            return hashTint(e.packageName);
        }
    }

    // ---------------------------------------------------------------- screen

    private void buildScreen() {
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        setContentView(stage);

        glow = new GlowView(this);
        stage.addView(glow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);
        root.setPadding(dp(56), dp(24), dp(56), 0);
        stage.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Vertical stack, nothing overlaps: header, then title slot, then grid filling the rest.
        buildHeader();
        buildTitle();
        buildGrid();
    }

    private void buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setClipChildren(false);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Left: greeting, then big 12-hour clock + AM/PM + divider + date.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text("GOOD EVENING", 12, Color.rgb(176, 182, 196), false);
        greeting.setLetterSpacing(0.32f);
        left.addView(greeting);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(true);
        clockText = text("", 56, Color.WHITE, false);
        clockText.setLetterSpacing(-0.04f);
        row.addView(clockText);

        ampmText = text("", 16, Color.rgb(210, 214, 224), false);
        ampmText.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams ampmLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ampmLp.leftMargin = dp(8);
        row.addView(ampmText, ampmLp);

        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(70, 255, 255, 255));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(1), dp(28));
        divLp.leftMargin = dp(18);
        divLp.rightMargin = dp(18);
        divLp.gravity = Gravity.BOTTOM;
        divLp.bottomMargin = dp(3);
        row.addView(divider, divLp);

        dateText = text("", 14, Color.rgb(176, 182, 196), false);
        dateText.setLetterSpacing(0.22f);
        row.addView(dateText);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(4);
        left.addView(row, rowLp);
        header.addView(left, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // Right: network indicator (Wi-Fi bars or Ethernet symbol) + Settings.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setClipChildren(false);

        FrameLayout netPill = new FrameLayout(this);
        netPill.setBackground(pillBg(false));
        netIcon = new IconView(this, IconView.NONE);
        netIcon.setColor(Color.WHITE);
        netPill.addView(netIcon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        right.addView(netPill, new LinearLayout.LayoutParams(dp(60), dp(42)));

        final LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.HORIZONTAL);
        settings.setGravity(Gravity.CENTER);
        settings.setPadding(dp(18), 0, dp(20), 0);
        settings.setFocusable(true);
        settings.setClickable(true);
        settings.setBackground(pillBg(false));
        final IconView gear = new IconView(this, IconView.GEAR);
        gear.setColor(Color.WHITE);
        settings.addView(gear, new LinearLayout.LayoutParams(dp(22), dp(22)));
        final TextView settingsLabel = text("Settings", 14, Color.WHITE, false);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = dp(10);
        settings.addView(settingsLabel, labelLp);
        settings.setOnFocusChangeListener((v, focused) -> {
            settings.setBackground(pillBg(focused));
            int c = focused ? Color.rgb(16, 16, 20) : Color.WHITE;
            settingsLabel.setTextColor(c);
            gear.setColor(c);
            settings.animate().cancel();
            settings.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                    .setDuration(240).setInterpolator(EASE_OUT).start();
        });
        settings.setOnClickListener(v -> openSystemScreen(new Intent(Settings.ACTION_SETTINGS), "Settings"));
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        settingsLp.leftMargin = dp(12);
        right.addView(settings, settingsLp);

        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rightLp.topMargin = dp(22);
        header.addView(right, rightLp);
    }

    private GradientDrawable pillBg(boolean focused) {
        return roundRect(focused ? Color.argb(240, 255, 255, 255) : Color.argb(46, 255, 255, 255), dp(21),
                focused ? Color.WHITE : Color.argb(80, 255, 255, 255), dp(1));
    }

    private void buildTitle() {
        // This title is outside the RecyclerView so it remains fixed while All Apps scrolls.
        // A placeholder also prevents the title slot from looking broken before first focus sync.
        focusedTitle = text("SELECT AN APP", 34, Color.WHITE, false);
        focusedTitle.setLetterSpacing(-0.02f);
        focusedTitle.setSingleLine(true);
        focusedTitle.setEllipsize(TextUtils.TruncateAt.END);
        focusedTitle.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        focusedTitle.setMinHeight(dp(46));
        focusedTitle.setPadding(dp(12), 0, 0, 0);
        focusedTitle.setVisibility(View.VISIBLE);
        focusedTitle.setAlpha(1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(28);
        lp.bottomMargin = dp(4);
        root.addView(focusedTitle, lp);
    }

    private void buildGrid() {
        grid = new RecyclerView(this);
        FocusGridLayoutManager lm = new FocusGridLayoutManager(this, COLUMNS);
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                if (position < 0 || position >= rows.size()) return 1;
                Object o = rows.get(position);
                return (o == HEADER || o == FOOTER) ? COLUMNS : 1;
            }
        });
        grid.setLayoutManager(lm);
        adapter = new HomeAdapter();
        grid.setAdapter(adapter);
        grid.setItemAnimator(null);
        // Hard clip at the grid padding edge: nothing can ever paint over the title or header.
        // The cut is hidden by fadeEdgeChildren(): rows lose alpha as they leave, so no chopped tiles.
        // Top padding = headroom for the 1.08x focused tile and ring.
        grid.setClipToPadding(true);
        grid.setClipChildren(true);
        grid.setPadding(dp(4), dp(12), dp(4), 0);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setFocusable(false);
        grid.setItemViewCacheSize(8);
        grid.setHasFixedSize(true);
        final RecyclerView.RecycledViewPool pool = grid.getRecycledViewPool();
        pool.setMaxRecycledViews(HomeAdapter.T_TILE, 24);
        // Build tiles ahead of time so scrolling into All Apps never inflates views mid-animation.
        grid.post(() -> {
            for (int i = 0; i < 12; i++) pool.putRecycledView(adapter.createViewHolder(grid, HomeAdapter.T_TILE));
        });
        grid.addItemDecoration(new TileSpacingDecoration(dp(16), dp(16)));
        grid.addItemDecoration(new PanelDecoration());
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                // RecyclerView can restore focus without firing a fresh focus-change callback.
                // Re-sync the fixed title after scrolling settles so it never stays blank/stale.
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    recyclerView.post(() -> syncFocusedAppTitle());
                }
            }
        });
        // Runs right before every draw (scroll, layout, bind, animation), so alpha is never stale.
        grid.getViewTreeObserver().addOnPreDrawListener(() -> {
            fadeEdgeChildren();
            return true;
        });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        grid.post(() -> focusPosition(0, 0));
    }

    private int resolveFocusable(int pos) {
        if (rows.isEmpty()) return -1;
        pos = Math.max(0, Math.min(pos, rows.size() - 1));
        for (int i = pos; i < rows.size(); i++) if (rows.get(i) instanceof AppEntry) return i;
        for (int i = pos - 1; i >= 0; i--) if (rows.get(i) instanceof AppEntry) return i;
        return -1;
    }

    private void focusPosition(int pos, int attempt) {
        if (grid == null || adapter == null) return;
        final int target = resolveFocusable(pos);
        if (target < 0) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(target);
        if (holder != null) { holder.itemView.requestFocus(); return; }
        if (attempt == 0) grid.scrollToPosition(target);
        if (attempt < 10) grid.postDelayed(() -> focusPosition(target, attempt + 1), 60L);
    }

    // ---------------------------------------------------------------- focus, hero, glow

    private void syncFocusedAppTitle() {
        if (grid == null || focusedTitle == null) return;
        View focused = grid.findFocus();
        if (focused == null) return;
        int position = grid.getChildAdapterPosition(focused);
        if (position == RecyclerView.NO_POSITION || position < 0 || position >= rows.size()) return;
        Object item = rows.get(position);
        if (!(item instanceof AppEntry)) return;
        focusedPosition = position;
        updateHero((AppEntry) item);
    }

    // Rows leaving through the top clip edge fade out by how much of them is still visible.
    private void fadeEdgeChildren() {
        if (grid == null) return;
        final int edge = grid.getPaddingTop();
        for (int i = 0, n = grid.getChildCount(); i < n; i++) {
            View c = grid.getChildAt(i);
            int h = c.getHeight();
            float a = 1f;
            if (h > 0) {
                float t = (c.getBottom() - edge) / (h * EDGE_FADE_FRACTION);
                t = Math.max(0f, Math.min(1f, t));
                a = t * t * (3f - 2f * t); // smoothstep
            }
            if (c.getAlpha() != a) c.setAlpha(a);
        }
    }

    private void onTileFocused(int position, View tile) {
        if (position < 0 || position >= rows.size() || !(rows.get(position) instanceof AppEntry)) return;
        focusedPosition = position;
        updateHero((AppEntry) rows.get(position));
        // Smooth D-pad scroll. App name stays in the fixed title slot above the grid.
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        int dy = lm.getDecoratedTop(tile) - grid.getPaddingTop();
        if (position < homeApps.size()) {
            // Home panel: keep BOTH rows on screen. Scroll to panel top, not to the focused row.
            dy -= (position / COLUMNS) * lm.getDecoratedMeasuredHeight(tile);
        }
        int duration = Math.max(280, Math.min(520, 220 + Math.abs(dy) / 2));
        if (Math.abs(dy) > 2) grid.smoothScrollBy(0, dy, EASE_SCROLL, duration);
    }

    private void updateHero(AppEntry e) {
        if (e == null || focusedTitle == null) return;
        boolean appChanged = focusedEntry == null || !TextUtils.equals(focusedEntry.packageName, e.packageName);
        boolean titleNeedsRepair = !TextUtils.equals(focusedTitle.getText(), e.label)
                || focusedTitle.getVisibility() != View.VISIBLE
                || focusedTitle.getAlpha() < 0.05f; // only a truly invisible title needs repair
        focusedEntry = e;

        // Do not early-return just because the package matches: RecyclerView recycling/focus
        // restoration can leave the title text blank or invisible for the same focused app.
        if (appChanged || titleNeedsRepair) {
            focusedTitle.animate().cancel();
            focusedTitle.setText(e.label);
            focusedTitle.setVisibility(View.VISIBLE);
            // Soft swap, not a blink: starts at 35% so fast D-pad moves do not strobe the title.
            focusedTitle.setAlpha(0.35f);
            focusedTitle.setTranslationY(dp(4f));
            focusedTitle.animate().alpha(1f).translationY(0f)
                    .setDuration(200).setInterpolator(EASE_OUT).start();
        } else {
            focusedTitle.setVisibility(View.VISIBLE);
        }
        if (appChanged) animateGlow(tintFor(e));
    }

    private void animateGlow(int target) {
        if (glowAnim != null) glowAnim.cancel();
        int from = glow.getTint();
        if (from == target) return;
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), from, target);
        glowAnim.setDuration(450);
        glowAnim.setInterpolator(EASE_IN_OUT);
        glowAnim.addUpdateListener(a -> glow.setTint((Integer) a.getAnimatedValue()));
        glowAnim.start();
    }

    // ---------------------------------------------------------------- clock + network

    private void updateClock() {
        if (clockText == null) return;
        Date now = new Date();
        clockText.setText(new SimpleDateFormat("h:mm", Locale.ENGLISH).format(now));
        ampmText.setText(new SimpleDateFormat("a", Locale.ENGLISH).format(now).toUpperCase(Locale.ENGLISH));
        dateText.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.ENGLISH).format(now).toUpperCase(Locale.ENGLISH));
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g;
        if (hour >= 5 && hour < 12) g = "GOOD MORNING";
        else if (hour >= 12 && hour < 17) g = "GOOD AFTERNOON";
        else if (hour >= 17 && hour < 21) g = "GOOD EVENING";
        else g = "GOOD NIGHT";
        greeting.setText(g);
    }

    @SuppressWarnings("deprecation")
    private void refreshNetwork() {
        if (netIcon == null) return;
        int kind = IconView.NONE;
        int level = 0;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    kind = IconView.ETHERNET;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    kind = IconView.WIFI;
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    WifiInfo info = wm.getConnectionInfo();
                    int rssi = info == null ? -100 : info.getRssi();
                    level = Build.VERSION.SDK_INT >= 30
                            ? wm.calculateSignalLevel(rssi)
                            : WifiManager.calculateSignalLevel(rssi, 5);
                    level = Math.max(0, Math.min(4, level));
                }
            }
        } catch (Exception ignored) { }
        netIcon.set(kind, level);
    }

    // ---------------------------------------------------------------- launching

    private void openSystemScreen(Intent intent, String title) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception exception) {
            Toast.makeText(this, "Could not open " + title, Toast.LENGTH_SHORT).show();
        }
    }

    private void launchPackage(String packageName) {
        if (packageName == null || packageName.isEmpty() || !isInstalled(packageName)) {
            Toast.makeText(this, "App is not installed.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) {
                Intent leanback = new Intent(Intent.ACTION_MAIN);
                leanback.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                leanback.setPackage(packageName);
                List<ResolveInfo> matches = getPackageManager().queryIntentActivities(leanback, 0);
                if (!matches.isEmpty()) {
                    ResolveInfo match = matches.get(0);
                    launch = new Intent(Intent.ACTION_MAIN);
                    launch.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                    launch.setClassName(match.activityInfo.packageName, match.activityInfo.name);
                }
            }
            if (launch == null) {
                Toast.makeText(this, "No launch activity found for this app.", Toast.LENGTH_SHORT).show();
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        } catch (Exception exception) {
            Toast.makeText(this, "Could not open app: " + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int blend(int c1, int c2, float f) {
        return Color.rgb(
                Math.round(Color.red(c1) * (1f - f) + Color.red(c2) * f),
                Math.round(Color.green(c1) * (1f - f) + Color.green(c2) * f),
                Math.round(Color.blue(c1) * (1f - f) + Color.blue(c2) * f));
    }

    // Icon-only apps get a card filled with their own glow colour so they match the banner tiles.
    private GradientDrawable cardGradient(int tint) {
        int dark = Color.rgb(14, 16, 22);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{blend(dark, tint, 0.55f), blend(dark, tint, 0.14f)});
        g.setCornerRadius(dp(TILE_RADIUS_DP));
        return g;
    }

    // Stable, distinct colour per app when the icon has no usable colour.
    private int hashTint(String pkg) {
        float hue = (pkg == null ? 0 : (pkg.hashCode() & 0x7fffffff)) % 360;
        return Color.HSVToColor(new float[]{hue, 0.7f, 1f});
    }

    // ---------------------------------------------------------------- helpers

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sizeSp);
        view.setIncludeFontPadding(false);
        view.setTypeface(mono != null ? mono : Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private GradientDrawable roundRect(int fill, int radius, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(strokeWidth, stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    // ---------------------------------------------------------------- adapter

    private final class HomeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int T_TILE = 0;
        private static final int T_HEADER = 1;
        private static final int T_FOOTER = 2;

        @Override public int getItemViewType(int position) {
            Object o = rows.get(position);
            if (o == HEADER) return T_HEADER;
            if (o == FOOTER) return T_FOOTER;
            return T_TILE;
        }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == T_HEADER) {
                TextView label = text("ALL APPS", 12, Color.rgb(176, 182, 196), false);
                label.setLetterSpacing(0.32f);
                label.setPadding(dp(2), dp(14), 0, dp(4));
                label.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(label) { };
            }
            if (viewType == T_FOOTER) {
                View spacer = new View(MainActivity.this);
                spacer.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90)));
                return new RecyclerView.ViewHolder(spacer) { };
            }
            return createTile();
        }

        private TileHolder createTile() {
            final int radius = dp(TILE_RADIUS_DP);
            FrameLayout tile = new FrameLayout(MainActivity.this);
            tile.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)));
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setBackground(roundRect(Color.rgb(30, 33, 42), radius, Color.TRANSPARENT, 0));
            tile.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            tile.setClipToOutline(true);

            ImageView banner = new ImageView(MainActivity.this);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(banner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout fallback = new LinearLayout(MainActivity.this);
            fallback.setOrientation(LinearLayout.VERTICAL);
            fallback.setGravity(Gravity.CENTER);
            fallback.setPadding(dp(8), dp(6), dp(8), dp(6));
            ImageView icon = new ImageView(MainActivity.this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            fallback.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
            TextView label = text("", 11, Color.rgb(205, 214, 230), false);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = dp(8);
            fallback.addView(label, labelLp);
            tile.addView(fallback, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            View ring = new View(MainActivity.this);
            ring.setBackground(roundRect(Color.argb(24, 255, 255, 255), radius, Color.WHITE, dp(3)));
            ring.setAlpha(0f);
            tile.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            return new TileHolder(tile, banner, fallback, icon, label, ring);
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (!(holder instanceof TileHolder)) return;
            final TileHolder h = (TileHolder) holder;
            final AppEntry e = (AppEntry) rows.get(position);
            ensureVisual(e);
            int radius = dp(TILE_RADIUS_DP);
            if (e.banner != null) {
                h.itemView.setBackground(roundRect(Color.rgb(30, 33, 42), radius, Color.TRANSPARENT, 0));
                h.banner.setImageDrawable(e.banner);
                h.banner.setVisibility(View.VISIBLE);
                h.fallback.setVisibility(View.GONE);
            } else {
                h.itemView.setBackground(cardGradient(tintFor(e)));
                h.banner.setImageDrawable(null);
                h.banner.setVisibility(View.GONE);
                h.fallback.setVisibility(View.VISIBLE);
                h.icon.setImageDrawable(e.icon);
                h.icon.setVisibility(e.icon == null ? View.GONE : View.VISIBLE);
                h.label.setText(e.label);
                h.label.setTextColor(Color.WHITE);
            }
            h.itemView.setContentDescription(e.label);
            h.itemView.setOnClickListener(v -> launchPackage(e.packageName));
            h.itemView.setOnLongClickListener(v -> {
                showMenu(e, h.getBindingAdapterPosition());
                return true;
            });

            boolean focused = h.itemView.isFocused();
            h.itemView.animate().cancel();
            h.ring.animate().cancel();
            h.itemView.setScaleX(focused ? 1.08f : 1f);
            h.itemView.setScaleY(focused ? 1.08f : 1f);
            h.itemView.setTranslationZ(focused ? dp(12f) : 0f);
            h.ring.setAlpha(focused ? 1f : 0f);

            h.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().cancel();
                v.animate().scaleX(hasFocus ? 1.08f : 1f).scaleY(hasFocus ? 1.08f : 1f)
                        .translationZ(hasFocus ? dp(12f) : 0f)
                        .setDuration(hasFocus ? 220 : 180).setInterpolator(EASE_OUT).start();
                h.ring.animate().cancel();
                h.ring.animate().alpha(hasFocus ? 1f : 0f).setDuration(200).start();
                if (hasFocus) {
                    int pos = h.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) onTileFocused(pos, v);
                }
            });

            // A recycled holder can already be focused before its listener is installed.
            // Handle that case explicitly so the first app name is not omitted.
            if (focused) {
                h.itemView.post(() -> {
                    int pos = h.getBindingAdapterPosition();
                    if (h.itemView.isFocused() && pos != RecyclerView.NO_POSITION) {
                        onTileFocused(pos, h.itemView);
                    }
                });
            }
        }

        @Override public int getItemCount() { return rows.size(); }
    }

    private static final class TileHolder extends RecyclerView.ViewHolder {
        final ImageView banner;
        final LinearLayout fallback;
        final ImageView icon;
        final TextView label;
        final View ring;
        TileHolder(View itemView, ImageView banner, LinearLayout fallback, ImageView icon, TextView label, View ring) {
            super(itemView);
            this.banner = banner;
            this.fallback = fallback;
            this.icon = icon;
            this.label = label;
            this.ring = ring;
        }
    }

    // ---------------------------------------------------------------- layout manager + decorations

    // Focus-driven scrolling is handled by onTileFocused(), so the default "nudge into view" is disabled.
    private static final class FocusGridLayoutManager extends GridLayoutManager {
        FocusGridLayoutManager(Context context, int spanCount) { super(context, spanCount); }

        @SuppressWarnings("deprecation")
        @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate) {
            return false;
        }

        @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate, boolean focusedChildVisible) {
            return false;
        }
    }

    private final class TileSpacingDecoration extends RecyclerView.ItemDecoration {
        private final int horizontal;
        private final int vertical;
        TileSpacingDecoration(int horizontal, int vertical) {
            this.horizontal = horizontal;
            this.vertical = vertical;
        }
        @Override public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
            outRect.set(horizontal / 2, vertical / 2, horizontal / 2, vertical / 2);
        }
    }

    // Dark rounded panel behind the eight go-to apps; it moves with the scroll.
    private final class PanelDecoration extends RecyclerView.ItemDecoration {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect bounds = new Rect();
        private final RectF box = new RectF();
        PanelDecoration() {
            fill.setColor(Color.argb(224, 16, 16, 19));
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(Color.argb(46, 255, 255, 255));
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1f));
        }
        @Override public void onDraw(Canvas canvas, RecyclerView parent, RecyclerView.State state) {
            int n = homeApps.size();
            int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, r = Integer.MIN_VALUE, b = Integer.MIN_VALUE;
            int restTop = 0;
            boolean any = false;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                int pos = parent.getChildAdapterPosition(child);
                if (pos < 0 || pos >= n) continue;
                parent.getDecoratedBoundsWithMargins(child, bounds);
                l = Math.min(l, bounds.left);
                t = Math.min(t, bounds.top);
                r = Math.max(r, bounds.right);
                b = Math.max(b, bounds.bottom);
                // Panel top as if row 1 were still attached, so the fade is right even after row 1 is recycled.
                restTop = bounds.top - (pos / COLUMNS) * bounds.height();
                any = true;
            }
            if (!any) return;
            float scrolled = parent.getPaddingTop() - restTop;
            float k = 1f - Math.max(0f, Math.min(1f, scrolled / dp(PANEL_FADE_DISTANCE_DP)));
            if (k <= 0.01f) return;
            fill.setAlpha((int) (224 * k));
            stroke.setAlpha((int) (46 * k));
            box.set(l, t, r, b);
            canvas.drawRoundRect(box, dp(26f), dp(26f), fill);
            canvas.drawRoundRect(box, dp(26f), dp(26f), stroke);
        }
    }

    // ---------------------------------------------------------------- custom views

    // Full-screen radial glow. One white-to-transparent shader, tinted by a colour filter, so
    // changing colour per frame is just a filter swap.
    private final class GlowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int tint = DEFAULT_TINT;
        GlowView(Context context) {
            super(context);
            paint.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN));
        }
        int getTint() { return tint; }
        void setTint(int color) {
            tint = color;
            paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            invalidate();
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float radius = Math.max(w, h) * 0.78f;
            paint.setShader(new RadialGradient(w * 0.34f, h * 0.42f, radius,
                    new int[]{Color.argb(108, 255, 255, 255), Color.argb(56, 255, 255, 255), Color.argb(0, 255, 255, 255)},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }

    // Wi-Fi (with signal level), Ethernet and gear icons drawn on a 24-unit grid.
    private final class IconView extends View {
        static final int NONE = 0;
        static final int WIFI = 1;
        static final int ETHERNET = 2;
        static final int GEAR = 3;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private int kind;
        private int level;
        private int color = Color.WHITE;

        IconView(Context context, int kind) {
            super(context);
            this.kind = kind;
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        void set(int newKind, int newLevel) {
            if (newKind == kind && newLevel == level) return;
            kind = newKind;
            level = newLevel;
            invalidate();
        }
        void setColor(int c) { color = c; invalidate(); }

        private void ink(int alpha) {
            p.setColor(color);
            p.setAlpha(alpha);
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float u = Math.min(w, h) / 24f;
            canvas.save();
            canvas.translate((w - 24f * u) / 2f, (h - 24f * u) / 2f);
            canvas.scale(u, u);
            if (kind == GEAR) drawGear(canvas);
            else if (kind == ETHERNET) drawEthernet(canvas);
            else drawWifi(canvas);
            canvas.restore();
        }

        private void drawWifi(Canvas canvas) {
            boolean connected = kind == WIFI;
            float cx = 12f, cy = 19f;
            float[] radii = {5.5f, 10f, 14.5f};
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.3f);
            for (int i = 0; i < 3; i++) {
                boolean lit = connected && level >= i + 1;
                ink(lit ? 255 : 70);
                oval.set(cx - radii[i], cy - radii[i], cx + radii[i], cy + radii[i]);
                canvas.drawArc(oval, 225f, 90f, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            ink(connected ? 255 : 70);
            canvas.drawCircle(cx, cy, 1.9f, p);
            if (!connected) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f);
                ink(220);
                canvas.drawLine(4f, 3.5f, 20f, 21.5f, p);
            }
        }

        private void drawEthernet(Canvas canvas) {
            ink(255);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            oval.set(4f, 4.5f, 20f, 16f);
            canvas.drawRoundRect(oval, 2.2f, 2.2f, p);
            p.setStrokeWidth(1.6f);
            for (int i = 0; i < 4; i++) {
                float x = 8f + i * 2.67f;
                canvas.drawLine(x, 4.5f, x, 9.5f, p);
            }
            p.setStyle(Paint.Style.FILL);
            oval.set(9f, 16f, 15f, 20.5f);
            canvas.drawRect(oval, p);
        }

        private void drawGear(Canvas canvas) {
            ink(255);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.4f);
            canvas.drawCircle(12f, 12f, 6.2f, p);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStrokeWidth(3.4f);
            for (int i = 0; i < 8; i++) {
                canvas.save();
                canvas.rotate(i * 45f, 12f, 12f);
                canvas.drawLine(12f, 1.6f, 12f, 4.4f, p);
                canvas.restore();
            }
            p.setStrokeCap(Paint.Cap.ROUND);
        }
    }

    private static final class AppEntry {
        final String label;
        final String packageName;
        Drawable banner;
        Drawable icon;
        boolean visualLoaded;
        int tint;
        boolean tintReady;
        AppEntry(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
