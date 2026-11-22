# Télécommande WEIER (Android, IR + WiFi)

Application Android qui remplace la télécommande de la Smart TV WEIER 55" (carte CV9632H-A50, Android 9).
Même disposition que la télécommande d'origine.

## Obtenir l'APK
L'APK est compilé par GitHub Actions : onglet **Actions → « Télécommande WEIER - APK » → dernier run → Artifacts → `telecommande-weier-apk`**.
Installez `app-debug.apk` sur le téléphone (autoriser les sources inconnues).

## WiFi (via ADB réseau)
Le firmware n'a plus l'appli constructeur ; l'app pilote donc la TV par ADB (port 5555), comme `adb shell input keyevent`.
1. TV : Paramètres → Préférences de l'appareil → À propos → 7 clics sur « Build » → Options pour les développeurs → débogage USB (et « débogage réseau » s'il existe).
2. Téléphone et TV sur le même WiFi → **Chercher** → **Connecter** → accepter la demande sur la TV (« Toujours autoriser »).
3. Fonctions : toutes les touches Android (volume, chaînes, flèches, couleurs, média…), clavier (saisie de texte), pavé tactile (flèches/OK/Retour), lanceur d'applications.

Limites : la TV éteinte n'est pas joignable en WiFi (Power → infrarouge). La saisie de texte est limitée à l'ASCII.
Si la TV ne propose aucune option de débogage réseau, le mode WiFi est impossible et il reste l'infrarouge.

## Infrarouge
Émission NEC 38 kHz via l'émetteur IR du téléphone. **Les codes de cette télécommande ne sont pas connus** : l'écran « Codes IR »
sert à les retrouver (adresse NEC + balayage des 256 commandes, enregistrement touche par touche).
Si la TV ne réagit à aucune adresse/commande, la télécommande n'est peut-être pas en NEC (RC5, etc.) : il faudra alors lire son signal
avec un récepteur IR (module TSOP + Arduino) ou retrouver la table dans le firmware.

## Développement
- `app/src/main/java/.../AdbClient.java` : client ADB pur Java (auth RSA, shell). Test : `tools/AdbSelfTest.java` (faux adbd local).
- Build local : `./gradlew assembleDebug` (JDK 17 + SDK Android 34).
