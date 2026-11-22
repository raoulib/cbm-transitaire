package com.sawki.weierremote;

import java.util.LinkedHashMap;
import java.util.Map;

/** Une touche de la télécommande : code Android (WiFi/ADB) et, si connu, commande IR (NEC). */
final class RKey {
    final String id;
    final String label;
    final int adb;        // KEYCODE_* Android, -1 si la touche n'existe pas côté Android
    final boolean repeat; // répétition quand on maintient appuyé

    private RKey(String id, String label, int adb, boolean repeat) {
        this.id = id; this.label = label; this.adb = adb; this.repeat = repeat;
    }

    static final Map<String, RKey> ALL = new LinkedHashMap<>();

    private static void add(String id, String label, int adb, boolean repeat) {
        ALL.put(id, new RKey(id, label, adb, repeat));
    }

    static {
        add("power", "Power", 26, false);
        add("mute", "Mute", 164, false);
        for (int i = 0; i <= 9; i++) add("n" + i, String.valueOf(i), 7 + i, false);
        add("freeze", "FREEZE", -1, false);
        add("recall", "Rappel", 229, false);
        add("pmode", "P.MODE", -1, false);
        add("smode", "S.MODE", -1, false);
        add("sleep", "SLEEP", 223, false);
        add("volup", "VOL +", 24, true);
        add("voldown", "VOL −", 25, true);
        add("chup", "CH +", 166, true);
        add("chdown", "CH −", 167, true);
        add("ttx", "TTX", 233, false);
        add("nicam", "NICAM", -1, false);
        add("fav", "FAV.", 174, false);
        add("home", "HOME", 3, false);
        add("aspect", "ASPECT", 255, false);
        add("display", "DISPLAY", 165, false);
        add("up", "▲", 19, true);
        add("down", "▼", 20, true);
        add("left", "◀", 21, true);
        add("right", "▶", 22, true);
        add("enter", "ENTER", 23, false);
        add("source", "SOURCE", 178, false);
        add("menu", "MENU", 82, false);
        add("exit", "EXIT", 4, false);
        add("red", "", 183, false);
        add("green", "", 184, false);
        add("yellow", "", 185, false);
        add("blue", "", 186, false);
        add("rew", "◀◀", 89, false);
        add("play", "▶❙❙", 85, false);
        add("ff", "▶▶", 90, false);
        add("prev", "❙◀◀", 88, false);
        add("stop", "■", 86, false);
        add("next", "▶▶❙", 87, false);
        add("mouse", "MOUSE", -1, false);
        add("browser", "BROWSER", 64, false);
        add("index", "INDEX", -1, false);
        add("hold", "HOLD", -1, false);
        add("cancel", "CANCEL", -1, false);
        add("size", "SIZE", -1, false);
    }
}
