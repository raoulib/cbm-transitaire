package com.sawki.weierhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Écran d'accueil Android TV moderne : horloge, favoris en grandes tuiles, grille d'applications. */
public class HomeActivity extends Activity {

    private static final int TILE_BG = Color.parseColor("#33FFFFFF");
    private static final int TILE_BG_FOCUS = Color.parseColor("#FF3F51B5");

    private static final class AppInfo {
        String pkg, label;
        ComponentName cn;
        Drawable icon, banner;
        boolean leanback;
    }

    private SharedPreferences prefs;
    private LinearLayout content;
    private TextView greeting;
    private final Map<String, AppInfo> apps = new LinkedHashMap<>();

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("weierhome", MODE_PRIVATE);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
        refresh();
    }

    @Override
    public void onBackPressed() {
        // un écran d'accueil ne se quitte pas avec Retour
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof AppInfo) {
                showOptions((AppInfo) f.getTag());
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    // ------------------------------------------------------------------ interface

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#0B1230"), Color.parseColor("#1B1B4D"), Color.parseColor("#34205F")});
        root.setBackground(bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(28), dp(48), dp(16));
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- en-tête : salutation + date à gauche, heure à droite
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = new TextView(this);
        greeting.setTextColor(Color.WHITE);
        greeting.setTextSize(30);
        greeting.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        left.addView(greeting);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTextColor(Color.parseColor("#B0BEC5"));
        date.setTextSize(18);
        left.addView(date);
        header.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(54);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        header.addView(clock);
        col.addView(header);

        // --- contenu défilant
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setPadding(0, dp(8), 0, dp(32));
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.parseColor("#CFD8DC"));
        t.setTextSize(20);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(4), dp(22), 0, dp(10));
        return t;
    }

    private void refresh() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 6 ? "Bonne nuit" : h < 12 ? "Bonjour" : h < 18 ? "Bon après-midi" : "Bonsoir");

        content.removeAllViews();
        Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", new HashSet<String>()));
        List<String> favs = loadFavorites();

        // favoris
        content.addView(sectionTitle("Favoris"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout favRow = new LinearLayout(this);
        favRow.setOrientation(LinearLayout.HORIZONTAL);
        favRow.setClipChildren(false);
        favRow.setPadding(dp(8), dp(12), dp(8), dp(16));
        View first = null;
        for (String pkg : favs) {
            AppInfo a = apps.get(pkg);
            if (a == null || hidden.contains(pkg)) continue;
            View t = tile(a, true);
            favRow.addView(t);
            if (first == null) first = t;
        }
        hs.addView(favRow);
        content.addView(hs);

        // toutes les applications
        content.addView(sectionTitle("Mes applications"));
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int widthDp = (int) (dm.widthPixels / dm.density) - 96;
        int tileDp = 168;
        int cols = Math.max(3, widthDp / (tileDp + 14));
        List<AppInfo> list = new ArrayList<>();
        for (AppInfo a : apps.values()) if (!hidden.contains(a.pkg)) list.add(a);
        Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setPadding(dp(8), dp(6), dp(8), dp(6));
                content.addView(row);
            }
            View t = tile(list.get(i), false);
            row.addView(t);
            if (first == null) first = t;
        }

        // réglages
        content.addView(sectionTitle("Réglages"));
        LinearLayout set = new LinearLayout(this);
        set.setOrientation(LinearLayout.HORIZONTAL);
        set.setClipChildren(false);
        set.setPadding(dp(8), dp(6), dp(8), dp(6));
        set.addView(actionTile("Paramètres", v -> openSettings(Settings.ACTION_SETTINGS)));
        set.addView(actionTile("Wi‑Fi", v -> openSettings(Settings.ACTION_WIFI_SETTINGS)));
        set.addView(actionTile("Applications masquées", v -> showHidden()));
        content.addView(set);

        if (first != null) first.requestFocus();
    }

    private GradientDrawable roundBg(int color, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(16));
        d.setColor(color);
        if (focused) d.setStroke(dp(3), Color.WHITE);
        return d;
    }

    private void styleFocus(final View v) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(roundBg(TILE_BG, false));
        v.setOnFocusChangeListener((view, hasFocus) -> {
            view.setBackground(roundBg(hasFocus ? TILE_BG_FOCUS : TILE_BG, hasFocus));
            float s = hasFocus ? 1.1f : 1f;
            view.animate().scaleX(s).scaleY(s).setDuration(130).start();
            view.setElevation(hasFocus ? dp(12) : 0);
        });
    }

    private View tile(final AppInfo a, boolean big) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        int w = big ? 236 : 168;
        int h = big ? 138 : 108;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(w), dp(h));
        lp.setMargins(dp(7), dp(5), dp(7), dp(5));
        t.setLayoutParams(lp);
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        t.setTag(a);
        styleFocus(t);

        ImageView iv = new ImageView(this);
        Drawable d = a.icon;
        iv.setImageDrawable(d);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        t.addView(iv, new LinearLayout.LayoutParams(dp(big ? 64 : 48), dp(big ? 64 : 48)));

        TextView label = new TextView(this);
        label.setText(a.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(big ? 16 : 14);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(6), 0, 0);
        t.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        t.setOnClickListener(v -> launch(a));
        t.setOnLongClickListener(v -> {
            showOptions(a);
            return true;
        });
        return t;
    }

    private View actionTile(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), dp(70));
        lp.setMargins(dp(7), dp(5), dp(7), dp(5));
        t.setLayoutParams(lp);
        styleFocus(t);
        t.setOnClickListener(l);
        return t;
    }

    // ------------------------------------------------------------------ données

    private void loadApps() {
        apps.clear();
        PackageManager pm = getPackageManager();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || apps.containsKey(pkg)) continue;
                AppInfo a = new AppInfo();
                a.pkg = pkg;
                a.cn = new ComponentName(pkg, ri.activityInfo.name);
                a.label = String.valueOf(ri.loadLabel(pm));
                a.icon = ri.loadIcon(pm);
                a.leanback = Intent.CATEGORY_LEANBACK_LAUNCHER.equals(cat);
                apps.put(pkg, a);
            }
        }
    }

    private List<String> loadFavorites() {
        String saved = prefs.getString("favs", null);
        List<String> l = new ArrayList<>();
        if (saved == null) {
            // premier lancement : les applis courantes présentes sur la TV
            String[] keys = {"youtube", "tvplayer", "vlc", "canal", "chrome"};
            for (String k : keys) {
                for (String pkg : apps.keySet()) {
                    if (pkg.toLowerCase(Locale.ROOT).contains(k) && !l.contains(pkg)) {
                        l.add(pkg);
                        break;
                    }
                }
            }
            saveFavorites(l);
        } else {
            for (String x : saved.split(",")) if (!x.isEmpty()) l.add(x);
        }
        return l;
    }

    private void saveFavorites(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String x : l) sb.append(x).append(',');
        prefs.edit().putString("favs", sb.toString()).apply();
    }

    // ------------------------------------------------------------------ actions

    private void launch(AppInfo a) {
        try {
            Intent li = new Intent(Intent.ACTION_MAIN);
            li.setComponent(a.cn);
            li.addCategory(a.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER);
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(li);
        } catch (Exception e) {
            Toast.makeText(this, "Impossible d'ouvrir " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Indisponible sur cette TV", Toast.LENGTH_SHORT).show();
        }
    }

    private void showOptions(final AppInfo a) {
        final List<String> favs = loadFavorites();
        final boolean isFav = favs.contains(a.pkg);
        String[] items = {isFav ? "Retirer des favoris" : "Ajouter aux favoris", "Masquer cette application", "Annuler"};
        new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        if (isFav) favs.remove(a.pkg);
                        else favs.add(a.pkg);
                        saveFavorites(favs);
                        refresh();
                    } else if (w == 1) {
                        Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", new HashSet<String>()));
                        hidden.add(a.pkg);
                        prefs.edit().putStringSet("hidden", hidden).apply();
                        refresh();
                    }
                }).show();
    }

    private void showHidden() {
        final Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", new HashSet<String>()));
        if (hidden.isEmpty()) {
            Toast.makeText(this, "Aucune application masquée", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> pk = new ArrayList<>(hidden);
        final String[] labels = new String[pk.size()];
        for (int i = 0; i < labels.length; i++) {
            AppInfo a = apps.get(pk.get(i));
            labels[i] = a != null ? a.label : pk.get(i);
        }
        new AlertDialog.Builder(this)
                .setTitle("Réafficher une application")
                .setItems(labels, (d, w) -> {
                    hidden.remove(pk.get(w));
                    prefs.edit().putStringSet("hidden", hidden).apply();
                    refresh();
                }).show();
    }
}
