package com.sawki.weierremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import android.view.GestureDetector;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import java.util.LinkedHashSet;
import java.util.Set;
import android.content.Context;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputMethodManager;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final int ADB_PORT = 5555;
    private static final int BG = Color.parseColor("#121212");
    private static final int KEY_BG = Color.parseColor("#2C2C2C");
    private static final int GREEN = Color.parseColor("#66BB6A");
    private static final int ORANGE = Color.parseColor("#FFA726");
    private static final int RED = Color.parseColor("#EF5350");

    private enum Mode { AUTO, WIFI, IR }

    private SharedPreferences prefs;
    private AdbClient adb;
    private IrSender ir;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Button> keyButtons = new HashMap<>();
    private final List<Button> modeButtons = new ArrayList<>();
    private Mode mode = Mode.AUTO;
    private TextView status;
    private EditText ipField;
    private RKey repeatKey;
    private View remoteView, mouseView;
    private TextView padView;
    private Button padModeButton;
    private LinearLayout shortcutBar;
    private LinearLayout headerBox;
    private TextView miniText;
    private Button gearButton;
    private Button sensButton;
    private int sens = 0;
    private final Object keyLock = new Object();
    private final List<Integer> pendingKeys = new ArrayList<>();
    private boolean drainScheduled = false;
    private boolean mouseOn = false;
    private int padMode = 1; // 0 pointeur, 1 flèches, 2 curseur TV
    private int tvW = 1920, tvH = 1080;
    private final Map<LinearLayout, List<String>> rowKeys = new HashMap<>();
    private final Runnable repeater = new Runnable() {
        @Override public void run() {
            if (repeatKey != null) {
                press(repeatKey);
                ui.postDelayed(this, 130);
            }
        }
    };

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("weier", MODE_PRIVATE);
        ir = new IrSender(this);
        adb = new AdbClient(loadOrCreateKey());
        try {
            mode = Mode.valueOf(prefs.getString("mode", "AUTO"));
        } catch (IllegalArgumentException ignored) { }
        padMode = Math.max(0, Math.min(2, prefs.getInt("padmode", 1)));
        sens = Math.max(0, Math.min(2, prefs.getInt("sens", 0)));
        buildUi();
        refreshStates();
        if (!prefs.getString("ip", "").isEmpty()) connectTv();
        else setStatus("Entrez l'IP de la TV ou touchez « Chercher ».", ORANGE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        exec.execute(() -> adb.close());
        exec.shutdown();
    }

    private KeyPair loadOrCreateKey() {
        try {
            String priv = prefs.getString("adb_priv", null);
            String pub = prefs.getString("adb_pub", null);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv != null && pub != null) {
                PrivateKey pr = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP)));
                PublicKey pu = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP)));
                return new KeyPair(pu, pr);
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            prefs.edit()
                    .putString("adb_priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                    .putString("adb_pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP))
                    .apply();
            return kp;
        } catch (Exception e) {
            throw new IllegalStateException("Clé RSA indisponible", e);
        }
    }

    // ------------------------------------------------------------------ interface

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(14), dp(10), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        remoteView = scroll;
        mouseView = buildMouseView();
        mouseView.setVisibility(View.GONE);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(mouseView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);

        // --- barre fine (toujours visible) + entête repliable
        LinearLayout miniBar = new LinearLayout(this);
        miniBar.setOrientation(LinearLayout.HORIZONTAL);
        miniBar.setGravity(Gravity.CENTER_VERTICAL);
        miniText = new TextView(this);
        miniText.setTextSize(12);
        miniText.setTextColor(Color.LTGRAY);
        miniText.setSingleLine(true);
        miniText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        miniBar.addView(miniText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        gearButton = textButton("▲ Masquer", v -> setHeaderCollapsed(headerBox.getVisibility() == View.VISIBLE));
        gearButton.setPadding(dp(12), 0, dp(12), 0);
        miniBar.addView(gearButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        root.addView(miniBar);
        headerBox = new LinearLayout(this);
        headerBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(headerBox);

        // --- connexion
        ipField = new EditText(this);
        ipField.setHint("IP de la TV (ex. 192.168.1.20)");
        ipField.setHintTextColor(Color.GRAY);
        ipField.setTextColor(Color.WHITE);
        ipField.setSingleLine(true);
        ipField.setText(prefs.getString("ip", ""));
        ipField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        Button connect = textButton("Connecter", v -> connectTv());
        Button find = textButton("Chercher", v -> discover());
        LinearLayout conn = new LinearLayout(this);
        conn.setOrientation(LinearLayout.HORIZONTAL);
        conn.setGravity(Gravity.CENTER_VERTICAL);
        conn.addView(ipField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        conn.addView(connect, new LinearLayout.LayoutParams(0, dp(44), 1f));
        conn.addView(find, new LinearLayout.LayoutParams(0, dp(44), 1f));
        headerBox.addView(conn);

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(13);
        status.setPadding(dp(4), dp(4), dp(4), dp(8));
        headerBox.addView(status);

        // --- mode
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        for (Mode m : Mode.values()) {
            Button mb = textButton(m == Mode.AUTO ? "Auto" : m == Mode.WIFI ? "WiFi" : "Infrarouge", v -> {
                mode = m;
                prefs.edit().putString("mode", m.name()).apply();
                refreshStates();
            });
            mb.setTag(m);
            modeButtons.add(mb);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            modes.addView(mb, lp);
        }
        headerBox.addView(modes);
        headerBox.addView(spacerView(dp(8)));

        // --- raccourcis d'applications
        shortcutBar = new LinearLayout(this);
        shortcutBar.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(shortcutBar);
        root.addView(hs);
        root.addView(spacerView(dp(6)));
        rebuildShortcuts();

        // --- télécommande (même disposition que l'originale)
        root.addView(row(key("power"), spacer(), spacer(), key("mute")));
        root.addView(row(key("n1"), key("n2"), key("n3")));
        root.addView(row(key("n4"), key("n5"), key("n6")));
        root.addView(row(key("n7"), key("n8"), key("n9")));
        root.addView(row(key("freeze"), key("n0"), key("recall")));
        root.addView(row(key("pmode"), key("smode"), key("sleep")));

        LinearLayout vc = new LinearLayout(this);
        vc.setOrientation(LinearLayout.HORIZONTAL);
        vc.addView(column(key("volup"), key("voldown")), weight());
        vc.addView(column(key("ttx"), key("nicam")), weight());
        vc.addView(column(key("chup"), key("chdown")), weight());
        root.addView(vc);

        root.addView(row(key("fav"), key("home"), key("aspect")));
        root.addView(row(key("display"), key("up"), key("source")));
        root.addView(row(key("left"), key("enter"), key("right")));
        root.addView(row(key("menu"), key("down"), key("exit")));
        root.addView(row(key("red"), key("green"), key("yellow"), key("blue")));
        root.addView(row(key("rew"), key("play"), key("ff")));
        root.addView(row(key("prev"), key("stop"), key("next")));
        root.addView(row(key("mouse"), key("browser"), key("index")));
        root.addView(row(key("hold"), key("cancel"), key("size")));

        // --- outils
        root.addView(spacerView(dp(10)));
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        addTool(tools, "Applications", v -> showApps());
        addTool(tools, "Plus ⋯", v -> showMore());
        root.addView(tools);
    }

    private void addTool(LinearLayout parent, String label, View.OnClickListener l) {
        Button t = textButton(label, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(t, lp);
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.GRAY);
        t.setTextSize(12);
        t.setPadding(dp(4), dp(16), 0, dp(4));
        return t;
    }

    private View spacerView(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private View spacer() {
        return new View(this);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        List<String> ids = new ArrayList<>();
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            r.addView(v, lp);
            for (Map.Entry<String, Button> en : keyButtons.entrySet()) {
                if (en.getValue() == v) ids.add(en.getKey());
            }
        }
        rowKeys.put(r, ids);
        return r;
    }

    private LinearLayout column(View top, View bottom) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        for (View v : new View[]{top, bottom}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            c.addView(v, lp);
        }
        return c;
    }

    private void styleButton(Button b, int bg, int fg) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(10));
        b.setBackground(d);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setPadding(0, 0, 0, 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
    }

    private Button textButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        styleButton(b, KEY_BG, Color.WHITE);
        b.setOnClickListener(l);
        return b;
    }

    private Button key(String id) {
        RKey k = RKey.ALL.get(id);
        Button b = new Button(this);
        b.setText(k.label);
        int bg = KEY_BG, fg = Color.WHITE;
        switch (id) {
            case "power": bg = Color.parseColor("#E53935"); break;
            case "red": bg = Color.parseColor("#E53935"); break;
            case "green": bg = Color.parseColor("#2E9E4F"); break;
            case "yellow": bg = Color.parseColor("#F5C518"); break;
            case "blue": bg = Color.parseColor("#2F6FE0"); break;
            case "volup": case "voldown": case "chup": case "chdown":
                bg = Color.parseColor("#26C6DA"); fg = Color.parseColor("#06323A"); break;
            default: break;
        }
        styleButton(b, bg, fg);
        if (id.equals("volup") || id.equals("chup") || id.equals("voldown") || id.equals("chdown")) b.setTextSize(15);
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setAlpha(0.55f);
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    press(k);
                    if (k.repeat) {
                        repeatKey = k;
                        ui.postDelayed(repeater, 450);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    repeatKey = null;
                    ui.removeCallbacks(repeater);
                    applyAlpha(k);
                    return true;
                default:
                    return true;
            }
        });
        keyButtons.put(id, b);
        return b;
    }

    // ------------------------------------------------------------------ mode souris

    private View buildMouseView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(BG);
        box.setPadding(dp(10), dp(14), dp(10), dp(10));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        sensButton = textButton("", x -> {
            sens = (sens + 1) % 3;
            prefs.edit().putInt("sens", sens).apply();
            updatePadHint();
        });
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(0, dp(40), 1f);
        ls.setMargins(0, 0, dp(8), 0);
        bar.addView(sensButton, ls);
        padModeButton = textButton("", x -> {
            padMode = (padMode + 1) % 3;
            prefs.edit().putInt("padmode", padMode).apply();
            updatePadHint();
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(dp(140), dp(40));
        l1.setMargins(0, 0, dp(8), 0);
        bar.addView(padModeButton, l1);
        bar.addView(textButton("Fermer ✕", x -> setMouse(false)), new LinearLayout.LayoutParams(dp(100), dp(40)));
        box.addView(bar);
        Button cursorBtn = textButton("Curseur de la TV : activer / désactiver", x -> toggleTvCursor());
        LinearLayout.LayoutParams lcb = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        lcb.setMargins(0, dp(8), 0, 0);
        box.addView(cursorBtn, lcb);

        padView = new TextView(this);
        padView.setGravity(Gravity.CENTER);
        padView.setTextColor(Color.GRAY);
        padView.setTextSize(15);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#1E1E1E"));
        d.setCornerRadius(dp(16));
        padView.setBackground(d);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        plp.setMargins(0, dp(10), 0, dp(10));
        box.addView(padView, plp);
        setupPad();
        updatePadHint();

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        addQuick(quick, "◀ Retour", 4);
        addQuick(quick, "Accueil", 3);
        addQuick(quick, "OK", 23);
        addQuick(quick, "Menu", 82);
        box.addView(quick);

        final EditText text = new EditText(this);
        text.setHint("Texte à envoyer à la TV");
        text.setHintTextColor(Color.GRAY);
        text.setTextColor(Color.WHITE);
        text.setSingleLine(true);
        text.setImeOptions(EditorInfo.IME_ACTION_SEND);
        final Runnable sendIt = () -> {
            String t = text.getText().toString();
            if (!t.isEmpty()) {
                sendText(t);
                text.setText("");
            }
        };
        text.setOnEditorActionListener((tv, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_SEND) {
                sendIt.run();
                return true;
            }
            return false;
        });
        LinearLayout kb = new LinearLayout(this);
        kb.setOrientation(LinearLayout.HORIZONTAL);
        kb.setGravity(Gravity.CENTER_VERTICAL);
        kb.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f));
        kb.addView(textButton("Envoyer", x -> sendIt.run()), new LinearLayout.LayoutParams(0, dp(44), 2f));
        kb.addView(textButton("⌫", x -> sendAdbKey(67)), new LinearLayout.LayoutParams(0, dp(44), 1f));
        kb.addView(textButton("↵", x -> sendAdbKey(66)), new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.setMargins(0, dp(8), 0, 0);
        box.addView(kb, klp);
        return box;
    }

    private void addQuick(LinearLayout parent, String label, final int code) {
        Button b = textButton(label, x -> sendAdbKey(code));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(b, lp);
    }

    private int arrowStep() {
        if (padMode == 2) return sens == 0 ? dp(26) : sens == 1 ? dp(17) : dp(11);
        return sens == 0 ? dp(110) : sens == 1 ? dp(75) : dp(50);
    }

    private void updatePadHint() {
        String[] names = {"Faible", "Moyenne", "Haute"};
        sensButton.setText("Sensibilité : " + names[sens]);
        sensButton.setVisibility(padMode == 0 ? View.INVISIBLE : View.VISIBLE);
        if (padMode == 0) {
            padView.setText("Cette zone représente l'écran de la TV\n(aucun curseur n'est visible sur la TV)\n\nToucher = clic à cet endroit\nGlisser = balayer / défiler\nMaintenir = appui long");
            padModeButton.setText("Mode : Pointeur");
        } else if (padMode == 1) {
            padView.setText("Glisser = flèches (menus)\nToucher = OK\nDouble toucher = Retour");
            padModeButton.setText("Mode : Flèches");
        } else {
            padView.setText("Pilote le curseur de la TV\n(activez-le avec le bouton ci-dessus)\n\nGlisser = déplacer le curseur\nToucher = clic");
            padModeButton.setText("Mode : Curseur TV");
        }
    }

    private int mapX(float x, int w) {
        return Math.max(0, Math.min(tvW - 1, (int) (x / w * tvW)));
    }

    private int mapY(float y, int h) {
        return Math.max(0, Math.min(tvH - 1, (int) (y / h * tvH)));
    }

    private void setupPad() {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop() * 2;
        final float[] acc = new float[2];
        final float[] st = new float[3]; // x, y, déplacé (0/1)
        final long[] t0 = new long[1];
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                acc[0] = 0; acc[1] = 0;
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                sendAdbKey(23);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                sendAdbKey(4);
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                final int step = arrowStep();
                acc[0] -= dx; acc[1] -= dy;
                while (Math.abs(acc[0]) >= step) {
                    sendAdbKey(acc[0] > 0 ? 22 : 21);
                    acc[0] += acc[0] > 0 ? -step : step;
                }
                while (Math.abs(acc[1]) >= step) {
                    sendAdbKey(acc[1] > 0 ? 20 : 19);
                    acc[1] += acc[1] > 0 ? -step : step;
                }
                return true;
            }
        });
        padView.setOnTouchListener((v, e) -> {
            if (padMode != 0) return gd.onTouchEvent(e);
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                st[0] = e.getX(); st[1] = e.getY(); st[2] = 0;
                t0[0] = e.getEventTime();
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (Math.hypot(e.getX() - st[0], e.getY() - st[1]) > slop) st[2] = 1;
            } else if (action == MotionEvent.ACTION_UP) {
                int w = Math.max(1, v.getWidth());
                int h = Math.max(1, v.getHeight());
                long dt = e.getEventTime() - t0[0];
                int x1 = mapX(st[0], w), y1 = mapY(st[1], h);
                int x2 = mapX(e.getX(), w), y2 = mapY(e.getY(), h);
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (st[2] == 0) {
                    if (dt > 600) adbRun("input swipe " + x1 + " " + y1 + " " + x1 + " " + y1 + " 800", null);
                    else adbRun("input tap " + x1 + " " + y1, null);
                } else {
                    long dur = Math.max(120, Math.min(1200, dt));
                    adbRun("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + dur, null);
                }
            }
            return true;
        });
    }

    private void setMouse(boolean on) {
        if (on && !wifiOk()) {
            toast("Connectez d'abord la TV en WiFi.");
            return;
        }
        mouseOn = on;
        remoteView.setVisibility(on ? View.GONE : View.VISIBLE);
        mouseView.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            View f = getCurrentFocus();
            if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        }
    }

    @Override
    public void onBackPressed() {
        if (mouseOn) setMouse(false);
        else super.onBackPressed();
    }

    private void parseScreenSize(String out) {
        Matcher m = Pattern.compile("(\\d{3,5})x(\\d{3,5})").matcher(out);
        int w = 0, h = 0;
        while (m.find()) {
            w = Integer.parseInt(m.group(1));
            h = Integer.parseInt(m.group(2));
        }
        if (w > 0 && h > 0) {
            tvW = w;
            tvH = h;
        }
    }

    // ------------------------------------------------------------------ raccourcis d'applications

    /** Liste les applis lançables (TV et téléphone) + les applis installées par l'utilisateur. */
    private static final String LIST_APPS_CMD =
            "pm query-activities --components -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER 2>/dev/null; "
                    + "pm query-activities --components -a android.intent.action.MAIN -c android.intent.category.LAUNCHER 2>/dev/null; "
                    + "pm list packages -3";

    private static final String[][] KEYWORDS = {
            {"YouTube", "youtube"}, {"IPTV", "iptv"}, {"IPTV", "smarters"}, {"Chrome", "chrome"},
            {"VLC", "vlc"}, {"Netflix", "netflix"}, {"Prime Video", "amazonvideo"},
            {"Disney+", "disney"}, {"Kodi", "kodi"}, {"Spotify", "spotify"}, {"Fichiers TV", "sendfiles"},
    };

    private static String appLabel(String pkg) {
        String low = pkg.toLowerCase(Locale.ROOT);
        for (String[] k : KEYWORDS) {
            if (low.contains(k[1])) return k[0];
        }
        String last = pkg.substring(pkg.lastIndexOf('.') + 1);
        return last.isEmpty() ? pkg : Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    /** Accepte "package:xxx" et "xxx/activité" (sortie de pm query-activities). */
    private static Set<String> parseLaunchable(String out) {
        Set<String> set = new LinkedHashSet<>();
        for (String raw : out.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("package:")) {
                set.add(line.substring(8).trim());
            } else if (line.indexOf('/') > 0 && line.indexOf(' ') < 0 && line.indexOf('.') > 0) {
                set.add(line.substring(0, line.indexOf('/')));
            }
        }
        return set;
    }

    private List<String> loadShortcuts() {
        List<String> l = new ArrayList<>();
        for (String x : prefs.getString("shortcuts", "").split("\\n")) {
            if (!x.trim().isEmpty()) l.add(x.trim());
        }
        return l;
    }

    private void saveShortcuts(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String x : l) sb.append(x).append('\n');
        prefs.edit().putString("shortcuts", sb.toString()).apply();
    }

    private void rebuildShortcuts() {
        shortcutBar.removeAllViews();
        for (final String pkg : loadShortcuts()) {
            Button b = textButton(appLabel(pkg), v -> launchApp(pkg));
            b.setPadding(dp(16), 0, dp(16), 0);
            b.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Retirer « " + appLabel(pkg) + " » ?")
                        .setPositiveButton("Retirer", (d, w) -> {
                            List<String> l = loadShortcuts();
                            l.remove(pkg);
                            saveShortcuts(l);
                            rebuildShortcuts();
                        })
                        .setNegativeButton("Annuler", null)
                        .show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
            lp.setMargins(dp(3), 0, dp(3), 0);
            shortcutBar.addView(b, lp);
        }
        Button add = textButton("＋ Raccourci", v -> showAddShortcut());
        add.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        lp.setMargins(dp(3), 0, dp(3), 0);
        shortcutBar.addView(add, lp);
    }

    private void launchApp(String pkg) {
        if (!wifiOk()) {
            toast("Connectez d'abord la TV en WiFi.");
            return;
        }
        adbRun("monkey -p " + pkg + " -c android.intent.category.LEANBACK_LAUNCHER 1; "
                + "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1", null);
    }

    /** Au premier branchement : propose YouTube, IPTV, Chrome, VLC... s'ils sont installés sur la TV. */
    private void seedShortcuts(String out) {
        if (prefs.getBoolean("sc_seeded2", false) || !loadShortcuts().isEmpty()) return;
        Set<String> installed = parseLaunchable(out);
        if (installed.isEmpty()) return;
        List<String> l = new ArrayList<>();
        for (String[] k : KEYWORDS) {
            for (String pkg : installed) {
                if (pkg.toLowerCase(Locale.ROOT).contains(k[1]) && !l.contains(pkg)
                        && !labelTaken(l, k[0])) {
                    l.add(pkg);
                    break;
                }
            }
        }
        if (l.isEmpty()) return;
        saveShortcuts(l);
        prefs.edit().putBoolean("sc_seeded2", true).apply();
        rebuildShortcuts();
    }

    private static boolean labelTaken(List<String> l, String label) {
        for (String p : l) if (appLabel(p).equals(label)) return true;
        return false;
    }

    private void showAddShortcut() {
        if (!wifiOk()) {
            toast("Connectez d'abord la TV en WiFi.");
            return;
        }
        adbRun(LIST_APPS_CMD, out -> {
            Set<String> set = parseLaunchable(out);
            set.removeAll(loadShortcuts());
            final List<String> pkgs = new ArrayList<>(set);
            Collections.sort(pkgs);
            if (pkgs.isEmpty()) {
                toast("Aucune application à ajouter.");
                return;
            }
            final String[] items = new String[pkgs.size()];
            for (int i = 0; i < items.length; i++) items[i] = appLabel(pkgs.get(i)) + "  (" + pkgs.get(i) + ")";
            new AlertDialog.Builder(this)
                    .setTitle("Ajouter un raccourci")
                    .setItems(items, (d, w) -> {
                        List<String> l = loadShortcuts();
                        l.add(pkgs.get(w));
                        saveShortcuts(l);
                        rebuildShortcuts();
                    }).show();
        });
    }

    // ------------------------------------------------------------------ curseur intégré de la TV

    private void showMouseSetup() {
        if (!wifiOk()) {
            toast("Connectez d'abord la TV en WiFi.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Curseur de la TV")
                .setMessage("Votre TV a un mode souris intégré (touche MOUSE de la télécommande d'origine). "
                        + "L'app va apprendre cette touche.\n\n"
                        + "1) Touchez « Démarrer ».\n"
                        + "2) Dans les 15 secondes, appuyez UNE FOIS sur MOUSE avec la télécommande d'origine, pointée vers la TV.\n"
                        + "3) L'app vous proposera de tester.")
                .setPositiveButton("Démarrer", (d, w) -> captureMouseKey())
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void captureMouseKey() {
        toast("Appuyez sur MOUSE maintenant (15 s)…");
        setStatus("Lecture de la télécommande d'origine… appuyez sur MOUSE.", ORANGE);
        exec.execute(() -> {
            try {
                final String raw = adb.shell("timeout 15 getevent -c 12 2>&1", 30000);
                // lignes : /dev/input/eventN: 0001 0074 00000001
                Pattern pat = Pattern.compile("(/dev/input/event\\d+):\\s+([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{8})");
                String dev = null;
                int code = -1;
                Matcher m = pat.matcher(raw);
                while (m.find()) {
                    if (Integer.parseInt(m.group(2), 16) == 1 && Integer.parseInt(m.group(4), 16) == 1) {
                        dev = m.group(1);
                        code = Integer.parseInt(m.group(3), 16);
                        break;
                    }
                }
                if (dev == null) {
                    final String shown = raw.trim().isEmpty() ? "(aucune sortie)" : raw.trim();
                    onUi(() -> {
                        setStatus("Aucune touche détectée.", RED);
                        new AlertDialog.Builder(this)
                                .setTitle("Aucune touche détectée")
                                .setMessage("Rien reçu de la télécommande. Réessayez en appuyant sur MOUSE juste après « Démarrer ».\n\nSortie brute :\n"
                                        + shown.substring(0, Math.min(500, shown.length())))
                                .setPositiveButton("OK", null).show();
                    });
                    return;
                }
                final String fdev = dev;
                final int fcode = code;
                String kl = adb.shell("grep -H -E '^key " + fcode + " ' /system/usr/keylayout/*.kl /vendor/usr/keylayout/*.kl 2>/dev/null", 8000);
                // choisit un libellé Android (préférence au fichier lié à la télécommande)
                String label = null;
                StringBuilder all = new StringBuilder();
                for (String line : kl.split("\\r?\\n")) {
                    Matcher km = Pattern.compile("^(\\S+):key\\s+" + fcode + "\\s+(\\S+)").matcher(line.trim());
                    if (km.find()) {
                        all.append(km.group(1).substring(km.group(1).lastIndexOf('/') + 1)).append(" → ").append(km.group(2)).append("\n");
                        String lower = km.group(1).toLowerCase(Locale.ROOT);
                        if (label == null || lower.contains("remote") || lower.contains("ir") || lower.contains("mtk")) label = km.group(2);
                    }
                }
                final String flabel = label;
                final String info = "Touche détectée : code " + fcode + " sur " + fdev + "\n"
                        + (flabel == null ? "Touche Android : inconnue (mode brut seulement)" : "Touche Android : KEYCODE_" + flabel)
                        + (all.length() > 0 ? "\n\nCorrespondances :\n" + all : "");
                onUi(() -> {
                    setStatus("Touche lue.", GREEN);
                    AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Touche MOUSE lue").setMessage(info);
                    if (flabel != null) {
                        b.setPositiveButton("Tester (touche Android)", (d, w) -> testMouseMethod("A:" + flabel));
                    }
                    b.setNeutralButton("Tester (brut)", (d, w) -> testMouseMethod("B:" + fdev + ":" + fcode));
                    b.setNegativeButton("Annuler", null);
                    b.show();
                });
            } catch (IOException e) {
                final String msg = e.getMessage();
                onUi(() -> {
                    setStatus("Lecture impossible : " + msg, RED);
                    refreshStates();
                });
            }
        });
    }

    private void sendMouseMethod(String m) {
        if (m.startsWith("A:")) {
            adbRun("input keyevent KEYCODE_" + m.substring(2), null);
        } else if (m.startsWith("B:")) {
            String[] p = m.split(":");
            if (p.length < 3) return;
            String d = p[1], c = p[2];
            adbRun("sendevent " + d + " 1 " + c + " 1; sendevent " + d + " 0 0 0; "
                    + "sendevent " + d + " 1 " + c + " 0; sendevent " + d + " 0 0 0", null);
        }
    }

    private void testMouseMethod(final String method) {
        sendMouseMethod(method);
        ui.postDelayed(() -> new AlertDialog.Builder(this)
                .setTitle("Test")
                .setMessage("Le curseur est-il apparu (ou a-t-il disparu) sur la TV ?")
                .setPositiveButton("Oui, enregistrer", (d, w) -> {
                    prefs.edit().putString("tvcursor", method).apply();
                    toast("Curseur TV enregistré.");
                })
                .setNegativeButton("Non", null)
                .show(), 1200);
    }

    private void toggleTvCursor() {
        String m = prefs.getString("tvcursor", "");
        if (m.isEmpty()) {
            showMouseSetup();
            return;
        }
        sendMouseMethod(m);
    }

    private void showMore() {
        final String[] items = {"Curseur de la TV (touche MOUSE)", "Codes IR (infrarouge)", "Diagnostic réseau", "Aide"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, w) -> {
                    if (w == 0) showMouseSetup();
                    else if (w == 1) showIrLab();
                    else if (w == 2) showDiagnostic();
                    else showHelp();
                }).show();
    }

    private void setHeaderCollapsed(boolean c) {
        headerBox.setVisibility(c ? View.GONE : View.VISIBLE);
        gearButton.setText(c ? "⚙ Réglages" : "▲ Masquer");
    }

    private void setStatus(String s, int color) {
        status.setText(s);
        status.setTextColor(color);
        if (miniText != null) {
            miniText.setText("● " + s);
            miniText.setTextColor(color);
        }
    }

    private void onUi(Runnable r) {
        ui.post(r);
    }

    // ------------------------------------------------------------------ état des touches

    private boolean wifiOk() {
        return adb != null && adb.isConnected();
    }

    private int irCmd(String id) {
        return prefs.getInt("ir_cmd_" + id, -1);
    }

    private boolean wifiRoute(RKey k) {
        return mode != Mode.IR && wifiOk() && k.adb >= 0;
    }

    private boolean irRoute(RKey k) {
        return mode != Mode.WIFI && ir.available() && irCmd(k.id) >= 0;
    }

    private void applyAlpha(RKey k) {
        Button b = keyButtons.get(k.id);
        if (b != null) b.setAlpha(wifiRoute(k) || irRoute(k) || k.id.equals("mouse") ? 1f : 0.4f);
    }

    private void refreshStates() {
        for (RKey k : RKey.ALL.values()) {
            applyAlpha(k);
            Button kb = keyButtons.get(k.id);
            if (kb != null) {
                boolean usable = k.adb >= 0 || irCmd(k.id) >= 0 || k.id.equals("mouse");
                kb.setVisibility(usable ? View.VISIBLE : View.GONE);
            }
        }
        for (Map.Entry<LinearLayout, List<String>> r : rowKeys.entrySet()) {
            boolean any = false;
            for (String id : r.getValue()) {
                Button kb = keyButtons.get(id);
                if (kb != null && kb.getVisibility() == View.VISIBLE) any = true;
            }
            r.getKey().setVisibility(any ? View.VISIBLE : View.GONE);
        }
        for (Button mb : modeButtons) {
            boolean on = mb.getTag() == mode;
            styleButton(mb, on ? Color.parseColor("#1565C0") : KEY_BG, Color.WHITE);
        }
    }

    // ------------------------------------------------------------------ envoi des commandes

    private void press(RKey k) {
        if (k.id.equals("mouse")) {
            setMouse(!mouseOn);
            return;
        }
        boolean wifi = wifiOk();
        boolean useIr;
        if (mode == Mode.IR) useIr = true;
        else if (mode == Mode.WIFI) useIr = false;
        else useIr = !wifi || (k.id.equals("power") && irCmd("power") >= 0);

        if (useIr) {
            if (!ir.available()) {
                toast("Ce téléphone n'a pas d'émetteur infrarouge.");
                return;
            }
            int cmd = irCmd(k.id);
            if (cmd < 0) {
                toast("Code IR inconnu pour cette touche — voir « Codes IR ».");
                return;
            }
            ir.sendNec(prefs.getInt("ir_lo", 0x00), prefs.getInt("ir_hi", 0xFF), cmd);
        } else {
            if (k.adb < 0) {
                toast("Cette touche n'existe qu'en infrarouge.");
                return;
            }
            if (!wifi) {
                toast("TV non connectée en WiFi.");
                return;
            }
            sendAdbKey(k.adb);
        }
    }

    /**
     * Les touches sont regroupées : si plusieurs appuis s'accumulent pendant qu'une commande
     * s'exécute (la TV met ~0,3 s par commande), ils partent ensemble dans un seul "input keyevent a b c".
     */
    private void sendAdbKey(int code) {
        synchronized (keyLock) {
            if (pendingKeys.size() < 12) pendingKeys.add(code);
            if (drainScheduled) return;
            drainScheduled = true;
        }
        exec.execute(this::drainKeys);
    }

    private void drainKeys() {
        while (true) {
            StringBuilder cmd = new StringBuilder("input keyevent");
            synchronized (keyLock) {
                if (pendingKeys.isEmpty()) {
                    drainScheduled = false;
                    return;
                }
                for (int c : pendingKeys) cmd.append(' ').append(c);
                pendingKeys.clear();
            }
            try {
                shellWithReconnect(cmd.toString());
            } catch (IOException e) {
                synchronized (keyLock) {
                    pendingKeys.clear();
                    drainScheduled = false;
                }
                final String msg = e.getMessage();
                onUi(() -> {
                    setStatus("Connexion WiFi perdue : " + msg, RED);
                    setHeaderCollapsed(false);
                    refreshStates();
                });
                return;
            }
        }
    }

    private String shellWithReconnect(String cmd) throws IOException {
        try {
            return adb.shell(cmd);
        } catch (IOException first) {
            String ip = prefs.getString("ip", "");
            if (ip.isEmpty()) throw first;
            adb.connect(ip, ADB_PORT, null);
            return adb.shell(cmd);
        }
    }

    private void sendText(String t) {
        String esc = t.replace(" ", "%s").replace("'", "'\\''");
        adbRun("input text '" + esc + "'", null);
    }

    private interface Result {
        void on(String out);
    }

    /** Exécute une commande shell sur la TV (thread dédié), avec une reconnexion automatique. */
    private void adbRun(String cmd, Result result) {
        exec.execute(() -> {
            try {
                String out = shellWithReconnect(cmd);
                if (result != null) {
                    final String o = out;
                    onUi(() -> result.on(o));
                }
            } catch (IOException e) {
                onUi(() -> {
                    setStatus("Connexion WiFi perdue : " + e.getMessage(), RED);
                    setHeaderCollapsed(false);
                    refreshStates();
                });
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ connexion / recherche

    private void connectTv() {
        final String ip = ipField.getText().toString().trim();
        if (ip.isEmpty()) {
            toast("Entrez l'adresse IP de la TV.");
            return;
        }
        prefs.edit().putString("ip", ip).apply();
        setStatus("Connexion à " + ip + "…", ORANGE);
        exec.execute(() -> {
            try {
                adb.connect(ip, ADB_PORT, () -> onUi(() ->
                        setStatus("Acceptez la demande sur l'écran de la TV (cochez « Toujours autoriser »)…", ORANGE)));
                onUi(() -> {
                    setStatus("Connecté à " + ip + " (WiFi)", GREEN);
                    setHeaderCollapsed(true);
                    adbRun("wm size", this::parseScreenSize);
                    if (!prefs.getBoolean("sc_seeded2", false) && loadShortcuts().isEmpty()) {
                        adbRun(LIST_APPS_CMD, this::seedShortcuts);
                    }
                    refreshStates();
                });
            } catch (IOException e) {
                onUi(() -> {
                    setStatus("Échec : " + e.getMessage() + ". Le débogage réseau est-il activé sur la TV ? (voir Aide)", RED);
                    setHeaderCollapsed(false);
                    refreshStates();
                });
            }
        });
    }

    private static String localSubnetPrefix() {
        try {
            String best = null;
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (Enumeration<InetAddress> ea = ni.getInetAddresses(); ea.hasMoreElements(); ) {
                    InetAddress a = ea.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String h = a.getHostAddress();
                        String p = h.substring(0, h.lastIndexOf('.') + 1);
                        if (ni.getName().startsWith("wlan")) return p;
                        if (best == null) best = p;
                    }
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private void discover() {
        final String base = localSubnetPrefix();
        if (base == null) {
            toast("Connectez le téléphone au WiFi de la TV.");
            return;
        }
        setStatus("Recherche des appareils ADB sur " + base + "0/24…", ORANGE);
        new Thread(() -> {
            final List<String> found = Collections.synchronizedList(new ArrayList<>());
            ExecutorService pool = Executors.newFixedThreadPool(48);
            for (int i = 1; i < 255; i++) {
                final String ip = base + i;
                pool.execute(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(ip, ADB_PORT), 500);
                        found.add(ip);
                    } catch (IOException ignored) { }
                });
            }
            pool.shutdown();
            try {
                pool.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) { }
            onUi(() -> {
                if (found.isEmpty()) {
                    setStatus("Aucun appareil trouvé (port 5555). Activez le débogage réseau sur la TV.", RED);
                } else if (found.size() == 1) {
                    ipField.setText(found.get(0));
                    connectTv();
                } else {
                    final String[] items = found.toArray(new String[0]);
                    new AlertDialog.Builder(this)
                            .setTitle("Choisir la TV")
                            .setItems(items, (d, w) -> {
                                ipField.setText(items[w]);
                                connectTv();
                            }).show();
                    setStatus(found.size() + " appareils trouvés.", ORANGE);
                }
            });
        }).start();
    }

    // ------------------------------------------------------------------ applications

    private void showApps() {
        if (!wifiOk()) {
            toast("Connectez d'abord la TV en WiFi.");
            return;
        }
        adbRun(LIST_APPS_CMD, out -> {
            List<String> pkgs = new ArrayList<>(parseLaunchable(out));
            Collections.sort(pkgs);
            pkgs.add(0, "⚙ Paramètres Android");
            final String[] items = pkgs.toArray(new String[0]);
            new AlertDialog.Builder(this)
                    .setTitle("Lancer sur la TV")
                    .setItems(items, (d, w) -> {
                        if (w == 0) {
                            adbRun("am start -a android.settings.SETTINGS", null);
                        } else {
                            launchApp(items[w]);
                        }
                    }).show();
        });
    }

    // ------------------------------------------------------------------ outil de recherche des codes IR

    private static String keyName(RKey k) {
        return k.label.isEmpty() ? k.id : k.label + "  (" + k.id + ")";
    }

    private static String hex2(int v) {
        return String.format(Locale.ROOT, "%02X", v & 0xff);
    }

    private void showIrLab() {
        if (!ir.available()) {
            new AlertDialog.Builder(this)
                    .setTitle("Pas d'émetteur IR")
                    .setMessage("Ce téléphone ne possède pas d'émetteur infrarouge : seul le mode WiFi est utilisable.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        final List<RKey> keys = new ArrayList<>(RKey.ALL.values());
        final List<String> names = new ArrayList<>();
        for (RKey k : keys) names.add(keyName(k));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), 0);

        TextView info = new TextView(this);
        info.setText("1) Pointez le téléphone vers la TV.\n"
                + "2) Laissez défiler les codes (ou avancez à la main) jusqu'à ce que la TV réagisse.\n"
                + "3) Choisissez la touche correspondante et enregistrez.\n"
                + "Conseil : commencez par Mute ou Vol+, évitez Power.");
        info.setTextSize(13);
        box.addView(info);

        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        box.addView(sp);

        final EditText addr = new EditText(this);
        addr.setSingleLine(true);
        addr.setHint("Adresse NEC (2 octets hex, ex. 00 FF)");
        addr.setText(hex2(prefs.getInt("ir_lo", 0x00)) + " " + hex2(prefs.getInt("ir_hi", 0xFF)));
        box.addView(addr);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        for (String p : new String[]{"00 FF", "40 BF", "40 40", "00 BF"}) {
            Button pb = textButton(p, v -> addr.setText(p));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
            lp.setMargins(dp(2), dp(4), dp(2), dp(4));
            presets.addView(pb, lp);
        }
        box.addView(presets);

        final int[] cur = {0};
        final TextView cmdView = new TextView(this);
        cmdView.setTextSize(22);
        cmdView.setGravity(Gravity.CENTER);
        cmdView.setPadding(0, dp(8), 0, dp(8));
        box.addView(cmdView);

        final TextView saved = new TextView(this);
        saved.setTextSize(12);
        saved.setGravity(Gravity.CENTER);
        box.addView(saved);

        final Runnable showCmd = () -> cmdView.setText("Commande : 0x" + hex2(cur[0]) + "  (" + cur[0] + ")");
        final Runnable showSaved = () -> {
            int c = irCmd(keys.get(sp.getSelectedItemPosition()).id);
            saved.setText(c < 0 ? "Aucun code enregistré pour cette touche" : "Code enregistré : 0x" + hex2(c));
        };
        showCmd.run();
        showSaved.run();
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { showSaved.run(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });

        final Runnable sendCur = () -> {
            int[] a = parseAddr(addr.getText().toString());
            if (a == null) {
                toast("Adresse invalide : saisissez 4 chiffres hexadécimaux (ex. 00FF).");
                return;
            }
            ir.sendNec(a[0], a[1], cur[0]);
        };
        final boolean[] first = {true};
        final Runnable stepAuto = new Runnable() {
            @Override public void run() {
                // cur[0] est toujours le code qui vient d'être envoyé (celui affiché)
                if (first[0]) first[0] = false;
                else if (cur[0] < 255) cur[0]++;
                else return;
                sendCur.run();
                showCmd.run();
                ui.postDelayed(this, 700);
            }
        };

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        int[] deltas = {-10, -1, 0, 1, 10};
        for (int dlt : deltas) {
            Button nb = textButton(dlt == 0 ? "Envoyer" : (dlt > 0 ? "+" : "") + dlt, v -> {
                if (dlt != 0) cur[0] = Math.max(0, Math.min(255, cur[0] + dlt));
                showCmd.run();
                sendCur.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), dlt == 0 ? 1.6f : 1f);
            lp.setMargins(dp(2), dp(4), dp(2), dp(4));
            nav.addView(nb, lp);
        }
        box.addView(nav);

        ToggleButton auto = new ToggleButton(this);
        auto.setTextOn("Défilement automatique : ON (0,7 s / code)");
        auto.setTextOff("Défilement automatique : OFF");
        auto.setChecked(false);
        auto.setOnCheckedChangeListener((b, on) -> {
            ui.removeCallbacks(stepAuto);
            if (on) ui.post(stepAuto);
        });
        box.addView(auto);

        LinearLayout act = new LinearLayout(this);
        act.setOrientation(LinearLayout.HORIZONTAL);
        Button save = textButton("Enregistrer pour cette touche", v -> {
            int[] a = parseAddr(addr.getText().toString());
            if (a == null) {
                toast("Adresse invalide.");
                return;
            }
            RKey k = keys.get(sp.getSelectedItemPosition());
            prefs.edit().putInt("ir_lo", a[0]).putInt("ir_hi", a[1]).putInt("ir_cmd_" + k.id, cur[0]).apply();
            showSaved.run();
            refreshStates();
            toast("Enregistré : " + k.id + " = 0x" + hex2(cur[0]));
        });
        Button clear = textButton("Effacer", v -> {
            RKey k = keys.get(sp.getSelectedItemPosition());
            prefs.edit().remove("ir_cmd_" + k.id).apply();
            showSaved.run();
            refreshStates();
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(44), 2f);
        l1.setMargins(dp(2), dp(8), dp(2), dp(4));
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        l2.setMargins(dp(2), dp(8), dp(2), dp(4));
        act.addView(save, l1);
        act.addView(clear, l2);
        box.addView(act);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Rechercher les codes IR")
                .setView(sv)
                .setPositiveButton("Fermer", null)
                .setOnDismissListener(d -> ui.removeCallbacks(stepAuto))
                .show();
    }

    /** "00 FF" ou "00FF" -> {0x00, 0xFF}. */
    private static int[] parseAddr(String s) {
        String h = s.replaceAll("[^0-9A-Fa-f]", "");
        if (h.length() != 4) return null;
        try {
            return new int[]{Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ diagnostic réseau

    private static String portLabel(int p) {
        switch (p) {
            case 5555: return "ADB";
            case 6466: return "AndroidTV-Remote";
            case 6467: return "AndroidTV-Pairing";
            case 8008: case 8009: return "Chromecast";
            case 80: case 8080: return "Web";
            default: return "";
        }
    }

    private void showDiagnostic() {
        final String base = localSubnetPrefix();
        if (base == null) {
            toast("Connectez le téléphone au WiFi de la TV.");
            return;
        }
        final int[] ports = {80, 8080, 5555, 6466, 6467, 8008, 8009, 9000, 9080, 5000, 7000, 55000};
        setStatus("Diagnostic réseau en cours (≈ 20 s)…", ORANGE);
        new Thread(() -> {
            final Map<Integer, List<Integer>> res = new HashMap<>();
            ExecutorService pool = Executors.newFixedThreadPool(96);
            for (int i = 1; i < 255; i++) {
                for (int pt : ports) {
                    final int host = i;
                    final int port = pt;
                    pool.execute(() -> {
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress(base + host, port), 700);
                            synchronized (res) {
                                List<Integer> l = res.get(host);
                                if (l == null) {
                                    l = new ArrayList<>();
                                    res.put(host, l);
                                }
                                l.add(port);
                            }
                        } catch (IOException ignored) { }
                    });
                }
            }
            pool.shutdown();
            try {
                pool.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) { }
            final StringBuilder sb = new StringBuilder("Réseau " + base + "0/24\n\n");
            synchronized (res) {
                List<Integer> hosts = new ArrayList<>(res.keySet());
                Collections.sort(hosts);
                for (int h : hosts) {
                    List<Integer> l = res.get(h);
                    Collections.sort(l);
                    sb.append(base).append(h).append("\n");
                    for (int pt : l) {
                        String lab = portLabel(pt);
                        sb.append("    port ").append(pt).append(lab.isEmpty() ? "" : "  (" + lab + ")").append("\n");
                    }
                }
                if (hosts.isEmpty()) sb.append("Aucun port ouvert trouvé.\n");
            }
            onUi(() -> {
                setStatus("Diagnostic terminé.", GREEN);
                TextView tv = new TextView(this);
                tv.setText(sb.toString());
                tv.setTextIsSelectable(true);
                tv.setPadding(dp(18), dp(8), dp(18), dp(8));
                ScrollView sv = new ScrollView(this);
                sv.addView(tv);
                new AlertDialog.Builder(this)
                        .setTitle("Appareils trouvés")
                        .setView(sv)
                        .setPositiveButton("Fermer", null)
                        .show();
            });
        }).start();
    }

    // ------------------------------------------------------------------ aide

    private void showHelp() {
        String msg = "WIFI (ADB)\n"
                + "Sur la TV : Paramètres → Préférences de l'appareil → À propos → appuyer 7 fois sur « Build » → "
                + "Options pour les développeurs → activer le débogage USB et, s'il existe, le « débogage réseau / ADB sur réseau ».\n"
                + "Téléphone et TV sur le même WiFi. Touchez « Chercher » (ou saisissez l'IP de la TV, visible dans les paramètres réseau), "
                + "puis « Connecter ». À la première connexion, acceptez la demande sur la TV en cochant « Toujours autoriser ».\n"
                + "Le WiFi ne peut pas allumer la TV éteinte : utilisez l'infrarouge pour Power.\n\n"
                + "INFRAROUGE\n"
                + "Les codes de votre télécommande ne sont pas connus d'avance. Utilisez « Codes IR » pour les retrouver touche par touche. "
                + "Le téléphone doit avoir un émetteur IR et être pointé vers la TV.\n\n"
                + "MODES\n"
                + "Auto : WiFi si connecté, sinon infrarouge (Power utilise l'IR si son code est enregistré).\n"
                + "RACCOURCIS : rangée sous les modes ; « ＋ Raccourci » pour ajouter une appli de la TV, appui long pour en retirer.\n"
                + "CURSEUR TV : menu Plus ⋯ → « Curseur de la TV » pour apprendre la touche MOUSE de la télécommande d'origine.\n"
                + "MOUSE : ouvre le mode souris (zone tactile + clavier) ; « Fermer » ou Retour revient à la télécommande.\n"
                + "Touches grisées : indisponibles dans le mode actuel (pas de connexion, ou code IR inconnu).";
        new AlertDialog.Builder(this)
                .setTitle("Aide")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }
}
