# 🖨️ Gin Khao Borne — APK Kiosk avec impression USB native

APK Android kiosk qui affiche la borne Gin Khao en plein écran et imprime les tickets **directement** sur l'imprimante thermique USB, sans RawBT ni aucune app tierce.

## Comment ça marche

- La WebView charge `borne.html` en plein écran immersif (pas de barres Android).
- Le HTML appelle `AndroidPrinter.print(base64EscPos)` → l'APK envoie les bytes ESC/POS directement au port USB de l'imprimante.
- La permission USB est accordée **automatiquement** au branchement de l'imprimante (filtre classe imprimante dans le manifest).
- La caméra est autorisée pour le scan QR fidélité.
- L'écran reste toujours allumé, le bouton retour est neutralisé.

## Installation (5 étapes)

### 1. Configurer l'URL de la borne
Dans `app/src/main/java/com/ginkhao/borne/MainActivity.java`, ligne ~30 :
```java
private static final String BORNE_URL = "https://TON-SITE.netlify.app/borne.html";
```
→ Remplace par l'URL réelle de ta borne.

### 2. Créer le repo et pousser
Crée un nouveau repo GitHub (ex: `gin-khao-borne-apk`), copie tous ces fichiers dedans, push sur `main`.

### 3. Récupérer l'APK
GitHub → onglet **Actions** → le workflow "Build APK Borne" tourne automatiquement → clique dessus → télécharge l'artifact **gin-khao-borne-apk** (c'est un zip contenant `app-debug.apk`).

### 4. Installer sur la tablette
Transfère l'APK sur la tablette (câble, Drive, lien...) et installe-le (autoriser les "sources inconnues" si demandé).

### 5. Brancher l'imprimante
Branche l'imprimante thermique en USB (câble OTG si la tablette n'a qu'un port USB-C/micro-USB). Android proposera d'ouvrir "Gin Khao Borne" → coche "Toujours" pour que la permission soit permanente.

## Test d'impression

Depuis la console de la WebView (ou temporairement dans le HTML) :
```javascript
// Vérifier que l'imprimante est vue
AndroidPrinter.isConnected();  // true = imprimante branchée
AndroidPrinter.isReady();      // true = branchée + autorisée

// Imprimer un test
AndroidPrinter.print(btoa('\x1B@Gin Khao TEST\n\n\n\n\x1DV\x00'));
```

## Notes techniques

- **APK debug signé automatiquement** : installable directement, pas besoin de keystore. (Pour une distribution Play Store il faudrait une signature release, inutile ici.)
- **Imprimante non détectée ?** Le code détecte d'abord les imprimantes classe USB 7, puis en fallback n'importe quel périphérique USB avec un endpoint BULK OUT (imprimantes chinoises génériques).
- **Mode kiosk total (optionnel)** : l'app déclare la catégorie HOME. Sur la tablette : Paramètres → Applications → Applications par défaut → Application d'accueil → "Gin Khao Borne". La borne devient le launcher, impossible d'en sortir sans repasser par les paramètres.
