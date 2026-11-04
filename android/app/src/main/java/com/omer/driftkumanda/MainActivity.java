package com.omer.driftkumanda;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Base64;
import android.util.SparseArray;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drift Kumanda: kumanda arayüzü bir WebView içinde çalışır (assets/index.html).
 * Bluetooth işleri burada yapılır ve JavaScript'e "NativeBT" adıyla açılır:
 * uygulama içinde BLE + klasik Bluetooth tarama, BLE GATT ve klasik SPP (HC-05) bağlantısı.
 * Tüm Bluetooth durumu ana iş parçacığında tutulur; geri çağrılar oraya aktarılır.
 */
@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
public class MainActivity extends Activity {
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final int REQ_PERM = 11;
    private static final int REQ_BT = 12;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private Runnable afterReady;
    private final Map<String, Long> lastSeen = new HashMap<>();

    // BLE bağlantısı
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic txChar;
    private UUID svcU, txU, rxU;
    private int writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
    private int mtu = 23;
    private boolean discovering;
    private boolean ready;
    private String mode; // "ble" | "classic" | null
    private boolean userClosed;

    // yazma kuyruğu (ana iş parçacığı)
    private final ArrayDeque<Object[]> queue = new ArrayDeque<>();
    private boolean writing;
    private int writingId = -1;
    private int writeSeq;

    // klasik Bluetooth (SPP)
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int connSeq;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#11141b"));
        setContentView(web);
        immersive();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* tarayıcı yok */ }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "NativeBT");

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, f);

        web.loadUrl("file:///android_asset/index.html");
    }

    /* ======================= JavaScript köprüsü ======================= */
    public class Bridge {
        @JavascriptInterface public boolean available() { return adapter != null; }

        @JavascriptInterface public String info() {
            JSONObject o = new JSONObject();
            put(o, "sdk", Build.VERSION.SDK_INT);
            put(o, "perm", hasPerms());
            try { put(o, "version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName); } catch (Exception e) { /* yok */ }
            return o.toString();
        }

        @JavascriptInterface public void scan(final boolean on, final boolean classic) { ui.post(() -> doScan(on, classic)); }

        @JavascriptInterface public void connectBle(final String addr, final String svc, final String tx, final String rx) { ui.post(() -> doConnectBle(addr, svc, tx, rx)); }

        @JavascriptInterface public void connectClassic(final String addr) { ui.post(() -> doConnectClassic(addr)); }

        @JavascriptInterface public void write(final int id, final String b64) {
            final byte[] data;
            try { data = Base64.decode(b64, Base64.DEFAULT); } catch (Exception e) { emitWrote(id); return; }
            ui.post(() -> enqueue(id, data));
        }

        @JavascriptInterface public void disconnect() { ui.post(() -> { userClosed = true; closeLink(); }); }

        @JavascriptInterface public void vibrate(final int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(Math.max(1, ms), VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(ms);
            } catch (Exception e) { /* titreşim yok */ }
        }

        @JavascriptInterface public void openSettings(final String which) { ui.post(() -> openSet(which)); }

        @JavascriptInterface public void openUrl(final String url) {
            ui.post(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { /* yok */ } });
        }
    }

    /* ======================= İzinler ======================= */
    private String[] perms() {
        if (Build.VERSION.SDK_INT >= 31) return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPerms() {
        for (String p : perms()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private boolean btOn() {
        try { return adapter != null && adapter.isEnabled(); } catch (Exception e) { return false; }
    }

    /** İzin ve Bluetooth açık olduğunda r'yi çalıştırır; gerekirse önce kullanıcıya sorar. */
    private void ensureReady(Runnable r) {
        if (adapter == null) { need("nobt"); failConnect("Bu telefonda Bluetooth yok"); return; }
        if (!hasPerms()) { afterReady = r; requestPermissions(perms(), REQ_PERM); return; }
        if (!btOn()) {
            afterReady = r;
            try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT); }
            catch (Exception e) { afterReady = null; need("bt"); failConnect("Bluetooth kapalı"); }
            return;
        }
        r.run();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] res) {
        super.onRequestPermissionsResult(code, p, res);
        if (code != REQ_PERM) return;
        Runnable r = afterReady;
        afterReady = null;
        if (hasPerms()) { if (r != null) ensureReady(r); return; }
        boolean blocked = false;
        for (String s : perms()) if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(s)) blocked = true;
        need(blocked ? "permblocked" : "perm");
        failConnect("Bluetooth izni verilmedi");
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_BT) return;
        Runnable r = afterReady;
        afterReady = null;
        if (btOn()) { if (r != null) ensureReady(r); }
        else { need("bt"); failConnect("Bluetooth kapalı"); }
    }

    private boolean locationOn() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return true;
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) { return true; }
    }

    private void openSet(String which) {
        try {
            Intent i;
            if ("location".equals(which)) i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            else if ("bt".equals(which)) i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            else i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
        } catch (Exception e) { /* ayar ekranı yok */ }
    }

    /* ======================= Tarama ======================= */
    private void doScan(boolean on, boolean classic) {
        if (!on) { stopScan(); return; }
        ensureReady(() -> {
            stopScan();
            if (Build.VERSION.SDK_INT < 31 && !locationOn()) need("location");
            synchronized (lastSeen) { lastSeen.clear(); }
            try {
                scanner = adapter.getBluetoothLeScanner();
                if (scanner != null) {
                    ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
                    scanner.startScan(null, st, scanCb);
                    scanning = true;
                }
            } catch (Exception e) { error("Tarama başlatılamadı: " + e.getMessage()); }
            if (classic) {
                try {
                    Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                    if (bonded != null) for (BluetoothDevice d : bonded) {
                        int t = d.getType();
                        if (t == BluetoothDevice.DEVICE_TYPE_CLASSIC || t == BluetoothDevice.DEVICE_TYPE_DUAL) emitDevice(d, d.getName(), 0, "classic", null, null, true);
                    }
                } catch (Exception e) { /* yok */ }
                try { adapter.cancelDiscovery(); adapter.startDiscovery(); } catch (Exception e) { /* yok */ }
            }
            JSONObject o = obj("scanstate");
            put(o, "on", true);
            emit(o);
            ui.removeCallbacks(scanStop);
            ui.postDelayed(scanStop, 15000);
        });
    }

    private final Runnable scanStop = this::stopScan;

    private void stopScan() {
        ui.removeCallbacks(scanStop);
        try { if (scanner != null && scanning) scanner.stopScan(scanCb); } catch (Exception e) { /* yok */ }
        try { if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (Exception e) { /* yok */ }
        scanning = false;
        JSONObject o = obj("scanstate");
        put(o, "on", false);
        emit(o);
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { handleResult(r); }
        @Override public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) handleResult(r); }
        @Override public void onScanFailed(final int code) {
            ui.post(() -> {
                scanning = false;
                error("Tarama hatası (kod " + code + "). Birkaç saniye sonra yeniden dene.");
                JSONObject o = obj("scanstate");
                put(o, "on", false);
                emit(o);
            });
        }
    };

    private void handleResult(ScanResult r) {
        final BluetoothDevice d = r.getDevice();
        if (d == null) return;
        final String addr = d.getAddress();
        long now = System.currentTimeMillis();
        synchronized (lastSeen) {
            Long t = lastSeen.get(addr);
            if (t != null && now - t < 700) return;
            lastSeen.put(addr, now);
        }
        ScanRecord rec = r.getScanRecord();
        String name = rec != null ? rec.getDeviceName() : null;
        if (name == null) { try { name = d.getName(); } catch (Exception e) { /* yok */ } }
        final JSONArray svc = new JSONArray();
        final JSONObject mfr = new JSONObject();
        if (rec != null) {
            List<ParcelUuid> us = rec.getServiceUuids();
            if (us != null) for (ParcelUuid u : us) svc.put(u.getUuid().toString());
            SparseArray<byte[]> m = rec.getManufacturerSpecificData();
            if (m != null) for (int i = 0; i < m.size(); i++) put(mfr, String.valueOf(m.keyAt(i)), hex(m.valueAt(i)));
        }
        final String nm = name;
        final int rssi = r.getRssi();
        ui.post(() -> emitDevice(d, nm, rssi, "ble", svc, mfr, false));
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                int t = BluetoothDevice.DEVICE_TYPE_UNKNOWN;
                try { t = d.getType(); } catch (Exception e) { /* yok */ }
                if (t == BluetoothDevice.DEVICE_TYPE_LE) return;
                short rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) 0);
                String name = i.getStringExtra(BluetoothDevice.EXTRA_NAME);
                if (name == null) { try { name = d.getName(); } catch (Exception e) { /* yok */ } }
                emitDevice(d, name, rssi, "classic", null, null, false);
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) {
                int s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (s == BluetoothAdapter.STATE_OFF && mode != null) {
                    userClosed = false;
                    closeLink();
                }
            }
        }
    };

    private void emitDevice(BluetoothDevice d, String name, int rssi, String kind, JSONArray svc, JSONObject mfr, boolean bonded) {
        JSONObject dd = new JSONObject();
        put(dd, "id", d.getAddress());
        put(dd, "name", name == null ? JSONObject.NULL : name);
        put(dd, "rssi", rssi);
        put(dd, "kind", kind);
        put(dd, "svc", svc == null ? new JSONArray() : svc);
        put(dd, "mfr", mfr == null ? new JSONObject() : mfr);
        put(dd, "bonded", bonded);
        JSONObject o = obj("scan");
        put(o, "d", dd);
        emit(o);
    }

    /* ======================= BLE bağlantısı ======================= */
    private void doConnectBle(final String addr, final String svc, final String tx, final String rx) {
        ensureReady(() -> {
            stopScan();
            hardClose();
            try {
                svcU = UUID.fromString(svc);
                txU = UUID.fromString(tx);
                rxU = rx == null || rx.isEmpty() ? null : UUID.fromString(rx);
                BluetoothDevice d = adapter.getRemoteDevice(addr);
                userClosed = false;
                mode = "ble";
                mtu = 23;
                ready = false;
                discovering = false;
                gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
                ui.removeCallbacks(connTimeout);
                ui.postDelayed(connTimeout, 15000);
            } catch (Exception e) {
                mode = null;
                failConnect("Bağlanılamadı: " + e.getMessage());
            }
        });
    }

    private final Runnable connTimeout = () -> {
        if (!ready && gatt != null) fail("Robot yanıt vermedi. Açık, yakında ve başka bir yere bağlı değil mi?");
    };

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) { ui.post(() -> onState(g, status, newState)); }

        @Override public void onMtuChanged(final BluetoothGatt g, final int m, final int status) {
            ui.post(() -> {
                if (g != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = m;
                discover();
            });
        }

        @Override public void onServicesDiscovered(final BluetoothGatt g, final int status) { ui.post(() -> onServices(g)); }

        @Override public void onDescriptorWrite(final BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            ui.post(() -> { if (g == gatt && !ready) markReady(); });
        }

        @Override public void onCharacteristicWrite(final BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            ui.post(() -> {
                if (g == gatt && writing) { writing = false; emitWrote(writingId); pump(); }
            });
        }

        @Override public void onCharacteristicChanged(final BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            final byte[] v = value == null ? new byte[0] : value.clone();
            ui.post(() -> { if (g == gatt) emitData(v); });
        }

        @Override public void onCharacteristicChanged(final BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT >= 33) return;
            byte[] raw = c.getValue();
            final byte[] v = raw == null ? new byte[0] : raw.clone();
            ui.post(() -> { if (g == gatt) emitData(v); });
        }
    };

    private void onState(BluetoothGatt g, int status, int newState) {
        if (g != gatt) { try { g.close(); } catch (Exception e) { /* yok */ } return; }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            discovering = false;
            boolean asked = false;
            try { asked = g.requestMtu(247); } catch (Exception e) { /* yok */ }
            if (!asked) discover();
            else ui.postDelayed(this::discover, 1500);
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            try { g.close(); } catch (Exception e) { /* yok */ }
            gatt = null;
            txChar = null;
            boolean was = ready;
            ready = false;
            mode = null;
            ui.removeCallbacks(connTimeout);
            failQueue();
            JSONObject o = obj("disc");
            put(o, "why", userClosed ? "user" : (was ? "lost" : "Bağlantı kurulamadı (kod " + status + "). Robotu kapatıp açmayı dene."));
            emit(o);
        }
    }

    private void discover() {
        if (gatt == null || discovering || ready) return;
        discovering = true;
        try { if (!gatt.discoverServices()) fail("Robotun servisleri okunamadı"); }
        catch (Exception e) { fail("Robotun servisleri okunamadı"); }
    }

    private void onServices(BluetoothGatt g) {
        if (g != gatt || ready) return;
        BluetoothGattService s = g.getService(svcU);
        if (s == null) {
            StringBuilder sb = new StringBuilder();
            for (BluetoothGattService x : g.getServices()) sb.append(' ').append(x.getUuid().toString().substring(4, 8));
            fail("Bu cihaz seçilen robot türüne uymuyor." + (sb.length() > 0 ? " Bulunan servisler:" + sb : ""));
            return;
        }
        txChar = s.getCharacteristic(txU);
        if (txChar == null) { fail("Robotun yazma kanalı bulunamadı"); return; }
        int p = txChar.getProperties();
        writeType = (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        BluetoothGattCharacteristic rx = rxU == null ? null : s.getCharacteristic(rxU);
        if (rx != null) {
            int rp = rx.getProperties();
            boolean notify = (rp & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
            boolean indicate = (rp & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
            if (notify || indicate) {
                try {
                    g.setCharacteristicNotification(rx, true);
                    BluetoothGattDescriptor d = rx.getDescriptor(CCCD);
                    if (d != null) {
                        byte[] val = notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                        boolean ok;
                        if (Build.VERSION.SDK_INT >= 33) ok = g.writeDescriptor(d, val) == BluetoothStatusCodes.SUCCESS;
                        else { d.setValue(val); ok = g.writeDescriptor(d); }
                        if (ok) {
                            ui.postDelayed(() -> { if (g == gatt && !ready) markReady(); }, 2000);
                            return;
                        }
                    }
                } catch (Exception e) { /* bildirim yok, yine de devam */ }
            }
        }
        markReady();
    }

    private void markReady() {
        if (ready) return;
        ready = true;
        ui.removeCallbacks(connTimeout);
        JSONObject o = obj("conn");
        put(o, "mtu", mtu);
        emit(o);
        pump();
    }

    private void fail(String msg) {
        error(msg);
        closeLink();
    }

    /** Bağlantı hiç kurulamadan bittiğinde JS tarafındaki bekleyen bağlantıyı düşürür. */
    private void failConnect(String msg) {
        error(msg);
        JSONObject o = obj("disc");
        put(o, "why", msg);
        emit(o);
    }

    /** Kullanıcı ya da hata kapatması: olayları gönderir. */
    private void closeLink() {
        ui.removeCallbacks(connTimeout);
        if (gatt != null) {
            final BluetoothGatt g = gatt;
            try { g.disconnect(); } catch (Exception e) { /* yok */ }
            ui.postDelayed(() -> {
                if (gatt == g) {
                    try { g.close(); } catch (Exception e) { /* yok */ }
                    gatt = null;
                    txChar = null;
                    ready = false;
                    mode = null;
                    failQueue();
                    JSONObject o = obj("disc");
                    put(o, "why", userClosed ? "user" : "lost");
                    emit(o);
                }
            }, 1200);
        }
        if (socket != null) {
            try { socket.close(); } catch (IOException e) { /* yok */ }
        }
    }

    /** Sessiz kapatma: yeni bir bağlantıdan önce eskisini temizler. */
    private void hardClose() {
        ui.removeCallbacks(connTimeout);
        connSeq++;
        if (gatt != null) {
            BluetoothGatt g = gatt;
            gatt = null;
            try { g.disconnect(); } catch (Exception e) { /* yok */ }
            try { g.close(); } catch (Exception e) { /* yok */ }
        }
        if (socket != null) {
            BluetoothSocket s = socket;
            socket = null;
            out = null;
            try { s.close(); } catch (IOException e) { /* yok */ }
        }
        txChar = null;
        ready = false;
        mode = null;
        queue.clear();
        writing = false;
    }

    /* ======================= Klasik Bluetooth (SPP) ======================= */
    private void doConnectClassic(final String addr) {
        ensureReady(() -> {
            stopScan();
            hardClose();
            final BluetoothDevice d;
            try { d = adapter.getRemoteDevice(addr); } catch (Exception e) { failConnect("Geçersiz cihaz adresi"); return; }
            userClosed = false;
            mode = "classic";
            final int seq = connSeq;
            new Thread(() -> {
                BluetoothSocket s = null;
                String err = null;
                try { adapter.cancelDiscovery(); } catch (Exception e) { /* yok */ }
                try {
                    s = d.createRfcommSocketToServiceRecord(SPP);
                    s.connect();
                } catch (Exception e1) {
                    err = e1.getMessage();
                    try { if (s != null) s.close(); } catch (Exception ig) { /* yok */ }
                    s = null;
                    try {
                        s = (BluetoothSocket) d.getClass().getMethod("createRfcommSocket", int.class).invoke(d, 1);
                        if (s != null) s.connect();
                    } catch (Exception e2) {
                        try { if (s != null) s.close(); } catch (Exception ig) { /* yok */ }
                        s = null;
                    }
                }
                final BluetoothSocket fs = s;
                final String em = err;
                if (fs == null) {
                    ui.post(() -> {
                        if (seq != connSeq) return;
                        mode = null;
                        failConnect("Klasik Bluetooth bağlantısı kurulamadı" + (em != null ? " (" + em + ")" : "") + ". Cihaz eşleşmiş mi? PIN genelde 1234 ya da 0000.");
                    });
                    return;
                }
                final InputStream in;
                try {
                    in = fs.getInputStream();
                    out = fs.getOutputStream();
                } catch (IOException e) {
                    try { fs.close(); } catch (IOException ig) { /* yok */ }
                    ui.post(() -> { if (seq == connSeq) { mode = null; failConnect("Bağlantı açılamadı"); } });
                    return;
                }
                socket = fs;
                ui.post(() -> {
                    if (seq != connSeq) { try { fs.close(); } catch (IOException ig) { /* yok */ } return; }
                    ready = true;
                    JSONObject o = obj("conn");
                    put(o, "mtu", 515);
                    emit(o);
                    pump();
                });
                byte[] buf = new byte[512];
                try {
                    while (true) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        if (n > 0) {
                            final byte[] v = Arrays.copyOf(buf, n);
                            ui.post(() -> emitData(v));
                        }
                    }
                } catch (IOException e) { /* bağlantı bitti */ }
                ui.post(() -> {
                    if (socket == fs) {
                        socket = null;
                        out = null;
                        ready = false;
                        mode = null;
                        failQueue();
                        JSONObject o = obj("disc");
                        put(o, "why", userClosed ? "user" : "lost");
                        emit(o);
                    }
                });
            }).start();
        });
    }

    /* ======================= Yazma kuyruğu ======================= */
    private void enqueue(int id, byte[] data) {
        queue.add(new Object[]{id, data, 0});
        pump();
    }

    private void failQueue() {
        while (!queue.isEmpty()) emitWrote((Integer) queue.poll()[0]);
        writing = false;
    }

    private void pump() {
        if (writing || queue.isEmpty()) return;
        if (!ready) {
            if (mode == null) failQueue();
            return;
        }
        if ("classic".equals(mode)) {
            final Object[] it = queue.poll();
            final OutputStream o = out;
            writing = true;
            io.execute(() -> {
                try { if (o != null) { o.write((byte[]) it[1]); o.flush(); } } catch (IOException e) { /* okuyucu kapanışı yakalar */ }
                ui.post(() -> { writing = false; emitWrote((Integer) it[0]); pump(); });
            });
            return;
        }
        if (gatt == null || txChar == null) { failQueue(); return; }
        Object[] it = queue.peek();
        byte[] data = (byte[]) it[1];
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= 33) ok = gatt.writeCharacteristic(txChar, data, writeType) == BluetoothStatusCodes.SUCCESS;
            else { txChar.setWriteType(writeType); txChar.setValue(data); ok = gatt.writeCharacteristic(txChar); }
        } catch (Exception e) { ok = false; }
        if (ok) {
            queue.poll();
            writing = true;
            final int id = (Integer) it[0];
            writingId = id;
            final int seq = ++writeSeq;
            ui.postDelayed(() -> { if (writing && writeSeq == seq) { writing = false; emitWrote(id); pump(); } }, 600);
        } else {
            int tries = (Integer) it[2];
            if (tries > 25) { queue.poll(); emitWrote((Integer) it[0]); ui.post(this::pump); }
            else { it[2] = tries + 1; ui.postDelayed(this::pump, 8); }
        }
    }

    /* ======================= JS'e olay gönderme ======================= */
    private static JSONObject obj(String t) { JSONObject o = new JSONObject(); put(o, "t", t); return o; }

    private static void put(JSONObject o, String k, Object v) { try { o.put(k, v); } catch (Exception e) { /* yok */ } }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        if (b != null) for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private void emit(JSONObject o) {
        final String js = "window.__nbt&&window.__nbt(" + o.toString() + ")";
        ui.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    private void emitData(byte[] v) { JSONObject o = obj("data"); put(o, "b", Base64.encodeToString(v, Base64.NO_WRAP)); emit(o); }

    private void emitWrote(int id) { JSONObject o = obj("wrote"); put(o, "id", id); emit(o); }

    private void need(String w) { JSONObject o = obj("need"); put(o, "what", w); emit(o); }

    private void error(String m) { JSONObject o = obj("err"); put(o, "msg", m); emit(o); }

    /* ======================= Yaşam döngüsü ======================= */
    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.evaluateJavascript("window.__appPause&&window.__appPause()", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (web != null) web.evaluateJavascript("window.__appResume&&window.__appResume()", null);
    }

    @Override
    public void onBackPressed() {
        if (web == null) { finish(); return; }
        web.evaluateJavascript("(window.__onBack&&window.__onBack())?1:0", v -> { if (!"1".equals(v)) finish(); });
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception e) { /* yok */ }
        try { if (scanner != null && scanning) scanner.stopScan(scanCb); } catch (Exception e) { /* yok */ }
        userClosed = true;
        hardClose();
        if (web != null) { web.destroy(); web = null; }
        io.shutdownNow();
        super.onDestroy();
    }
}
