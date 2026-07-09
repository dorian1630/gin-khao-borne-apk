package com.ginkhao.borne;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.util.HashMap;

/**
 * Impression directe ESC/POS sur imprimante thermique USB.
 * - Détecte automatiquement l'imprimante (classe USB 7, ou fallback : premier
 *   périphérique avec un endpoint BULK OUT).
 * - Demande la permission USB si nécessaire (accordée automatiquement au
 *   branchement grâce au device_filter du manifest).
 * - Envoie les bytes ESC/POS par bulkTransfer, en chunks.
 */
public class UsbEscPosPrinter {

    private static final String TAG = "UsbEscPosPrinter";
    private static final String ACTION_USB_PERMISSION = "com.ginkhao.borne.USB_PERMISSION";
    private static final int CHUNK_SIZE = 8192;      // 8 Ko par transfert
    private static final int TIMEOUT_MS = 5000;

    private final Context context;
    private final UsbManager usbManager;

    // Données en attente si on doit d'abord demander la permission
    private byte[] pendingData = null;

    public UsbEscPosPrinter(Context context) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        registerPermissionReceiver();
    }

    // ────────────────────────────────────────────────────────────
    //  API publique
    // ────────────────────────────────────────────────────────────

    /** Imprime les bytes ESC/POS. Thread-safe, non bloquant pour l'UI. */
    public void print(final byte[] data) {
        new Thread(() -> {
            UsbDevice printer = findPrinter();
            if (printer == null) {
                Log.e(TAG, "Aucune imprimante USB détectée");
                return;
            }
            if (!usbManager.hasPermission(printer)) {
                Log.i(TAG, "Permission USB manquante → demande en cours");
                pendingData = data;
                requestPermission(printer);
                return;
            }
            sendToPrinter(printer, data);
        }).start();
    }

    /** true si une imprimante USB est branchée et autorisée. */
    public boolean isReady() {
        UsbDevice printer = findPrinter();
        return printer != null && usbManager.hasPermission(printer);
    }

    /** true si une imprimante USB est branchée (même sans permission). */
    public boolean isConnected() {
        return findPrinter() != null;
    }

    // ────────────────────────────────────────────────────────────
    //  Détection de l'imprimante
    // ────────────────────────────────────────────────────────────

    private UsbDevice findPrinter() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices.isEmpty()) return null;

        // 1er passage : classe imprimante officielle (7)
        for (UsbDevice device : devices.values()) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                    return device;
                }
            }
        }
        // 2e passage : fallback — n'importe quel périphérique avec un BULK OUT
        // (certaines imprimantes chinoises se déclarent en vendor-specific)
        for (UsbDevice device : devices.values()) {
            if (findBulkOutInterface(device) != null) {
                return device;
            }
        }
        return null;
    }

    /** Retourne [interface, endpoint] BULK OUT du device, ou null. */
    private Object[] findBulkOutInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                        && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    return new Object[]{intf, ep};
                }
            }
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────
    //  Envoi des données
    // ────────────────────────────────────────────────────────────

    private void sendToPrinter(UsbDevice device, byte[] data) {
        Object[] found = findBulkOutInterface(device);
        if (found == null) {
            Log.e(TAG, "Pas d'endpoint BULK OUT sur l'imprimante");
            return;
        }
        UsbInterface intf = (UsbInterface) found[0];
        UsbEndpoint endpoint = (UsbEndpoint) found[1];

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "Impossible d'ouvrir la connexion USB");
            return;
        }
        try {
            if (!connection.claimInterface(intf, true)) {
                Log.e(TAG, "claimInterface a échoué");
                return;
            }
            // Envoi en chunks
            int offset = 0;
            while (offset < data.length) {
                int len = Math.min(CHUNK_SIZE, data.length - offset);
                byte[] chunk = new byte[len];
                System.arraycopy(data, offset, chunk, 0, len);
                int sent = connection.bulkTransfer(endpoint, chunk, len, TIMEOUT_MS);
                if (sent < 0) {
                    Log.e(TAG, "bulkTransfer a échoué à l'offset " + offset);
                    return;
                }
                offset += sent;
            }
            Log.i(TAG, "Ticket envoyé : " + data.length + " bytes");
        } finally {
            try { connection.releaseInterface(intf); } catch (Exception ignored) {}
            connection.close();
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Gestion de la permission USB
    // ────────────────────────────────────────────────────────────

    private void requestPermission(UsbDevice device) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE; // requis pour USB sur Android 12+
        }
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(context.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, flags);
        usbManager.requestPermission(device, pi);
    }

    private void registerPermissionReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (granted && device != null && pendingData != null) {
                    final byte[] data = pendingData;
                    pendingData = null;
                    new Thread(() -> sendToPrinter(device, data)).start();
                } else {
                    Log.w(TAG, "Permission USB refusée ou pas de données en attente");
                    pendingData = null;
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }
}
