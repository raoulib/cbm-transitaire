package com.sawki.weierremote;

import android.content.Context;
import android.hardware.ConsumerIrManager;

/** Émission infrarouge NEC (38 kHz) via l'émetteur IR du téléphone. */
final class IrSender {
    private final ConsumerIrManager mgr;

    IrSender(Context ctx) {
        mgr = (ConsumerIrManager) ctx.getSystemService(Context.CONSUMER_IR_SERVICE);
    }

    boolean available() {
        try {
            return mgr != null && mgr.hasIrEmitter();
        } catch (Exception e) {
            return false;
        }
    }

    /** Trame NEC étendue : adresse (2 octets), commande, commande inversée ; bits LSB en premier. */
    static int[] necPattern(int addrLo, int addrHi, int cmd) {
        int[] bytes = {addrLo & 0xff, addrHi & 0xff, cmd & 0xff, (cmd ^ 0xff) & 0xff};
        int[] p = new int[2 + 32 * 2 + 2];
        int i = 0;
        p[i++] = 9000;
        p[i++] = 4500;
        for (int b : bytes) {
            for (int bit = 0; bit < 8; bit++) {
                p[i++] = 560;
                p[i++] = ((b >> bit) & 1) == 1 ? 1690 : 560;
            }
        }
        p[i++] = 560;
        p[i++] = 40000;
        return p;
    }

    boolean sendNec(int addrLo, int addrHi, int cmd) {
        if (!available()) return false;
        try {
            mgr.transmit(38000, necPattern(addrLo, addrHi, cmd));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
