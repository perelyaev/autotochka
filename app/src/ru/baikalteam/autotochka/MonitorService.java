package ru.baikalteam.autotochka;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;

/**
 * Следит за появлением устройства в результатах поиска Bluetooth
 * и включает Wi-Fi точку доступа.
 */
public class MonitorService extends Service {

    public static final String ACTION_START = "ru.baikalteam.autotochka.START";
    public static final String ACTION_STOP = "ru.baikalteam.autotochka.STOP";
    public static final String ACTION_TEST_ON = "ru.baikalteam.autotochka.TEST_ON";
    public static final String ACTION_TEST_OFF = "ru.baikalteam.autotochka.TEST_OFF";
    public static final String ACTION_SIMULATE = "ru.baikalteam.autotochka.SIMULATE";
    public static final String ACTION_SIMULATE_STALE = "ru.baikalteam.autotochka.SIMULATE_STALE";

    private static final String CHANNEL = "monitor";
    private static final int NOTIF_ID = 1;
    private static final long WATCHDOG_MS = 30_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.Set<String> seenNames = new java.util.HashSet<>();
    private Prefs prefs;
    private BluetoothAdapter adapter;
    private boolean started;

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            startScan();
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            // сверяемся с реальным состоянием: точку могли выключить вручную
            State.hotspotOn = Hotspot.isOn(MonitorService.this);
            checkProfiles();
            checkConnectedDevices();
            checkHotspotTimeout();
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    private BluetoothProfile a2dpProfile;
    private BluetoothProfile hfpProfile;

    private final BluetoothProfile.ServiceListener a2dpListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            a2dpProfile = proxy;
        }

        @Override
        public void onServiceDisconnected(int profile) {
            a2dpProfile = null;
        }
    };

    private final BluetoothProfile.ServiceListener hfpListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            hfpProfile = proxy;
        }

        @Override
        public void onServiceDisconnected(int profile) {
            hfpProfile = null;
        }
    };

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                onDeviceFound(intent);
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                onAclChanged(intent, true);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                onAclChanged(intent, false);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                State.scanning = false;
                scheduleScan(prefs.scanGapSec() * 1000L);
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (st == BluetoothAdapter.STATE_ON) {
                    Logs.add("Bluetooth включён — начинаю поиск");
                    scheduleScan(1000);
                } else if (st == BluetoothAdapter.STATE_OFF) {
                    State.scanning = false;
                    Logs.add("Bluetooth выключен");
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        adapter = BluetoothAdapter.getDefaultAdapter();
        Logs.init(new File(getFilesDir(), "monitor.log"));
        createChannel();

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(btReceiver, filter);
        }
        try {
            if (adapter != null) {
                adapter.getProfileProxy(this, a2dpListener, BluetoothProfile.A2DP);
                adapter.getProfileProxy(this, hfpListener, BluetoothProfile.HEADSET);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification("Слежу за " + prefs.btName()));

        if (ACTION_TEST_ON.equals(action)) {
            State.lastSeen = System.currentTimeMillis();
            State.lastResult = Hotspot.enable(this);
            State.hotspotOn = State.lastResult.startsWith("OK");
            Logs.add("тест: включить точку доступа -> " + State.lastResult);
            updateNotification();
            return START_STICKY;
        }
        if (ACTION_TEST_OFF.equals(action)) {
            State.lastResult = Hotspot.disable(this);
            State.hotspotOn = false;
            Logs.add("тест: выключить точку доступа -> " + State.lastResult);
            updateNotification();
            return START_STICKY;
        }
        if (ACTION_SIMULATE_STALE.equals(action)) {
            State.lastSeen = System.currentTimeMillis() - 20 * 60_000L;
            State.hotspotOn = true;
            Logs.add("тест: имитирую, что устройство не видно 20 минут");
            updateNotification();
            return START_STICKY;
        }
        if (ACTION_SIMULATE.equals(action)) {
            Logs.add("тест: имитирую появление " + prefs.btName() + " в поиске");
            handleSeen(prefs.btName(), "00:11:22:33:44:55", (short) -45);
            updateNotification();
            return START_STICKY;
        }

        if (!started) {
            started = true;
            prefs.setEnabled(true);
            State.serviceRunning = true;
            State.hotspotOn = Hotspot.isOn(this);
            Logs.add("сервис запущен, ищу \"" + prefs.btName() + "\"");
            scheduleScan(1000);
            handler.postDelayed(watchdog, WATCHDOG_MS);
        }
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        State.serviceRunning = false;
        try {
            if (adapter != null) {
                if (a2dpProfile != null) {
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dpProfile);
                }
                if (hfpProfile != null) {
                    adapter.closeProfileProxy(BluetoothProfile.HEADSET, hfpProfile);
                }
            }
        } catch (Throwable ignored) {
        }
        State.scanning = false;
        try {
            unregisterReceiver(btReceiver);
        } catch (Throwable ignored) {
        }
        handler.removeCallbacksAndMessages(null);
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a != null && a.isDiscovering()) {
            try {
                a.cancelDiscovery();
            } catch (Throwable ignored) {
            }
        }
        Logs.add("сервис остановлен");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scheduleScan(long delayMs) {
        handler.removeCallbacks(scanRunnable);
        handler.postDelayed(scanRunnable, delayMs);
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startScan() {
        if (adapter == null) {
            Logs.add("Bluetooth адаптер не найден");
            scheduleScan(30_000);
            return;
        }
        if (!hasScanPermission()) {
            Logs.add("нет разрешения на поиск Bluetooth — откройте приложение и выдайте");
            scheduleScan(30_000);
            return;
        }
        if (!adapter.isEnabled()) {
            State.scanning = false;
            scheduleScan(15_000);
            return;
        }
        if (adapter.isDiscovering()) {
            return;
        }
        try {
            State.scanning = adapter.startDiscovery();
            State.scans++;
            State.lastScanStart = System.currentTimeMillis();
            if (State.scanning) {
                Logs.add("поиск устройств запущен");
            } else {
                Logs.add("startDiscovery вернул false");
                scheduleScan(15_000);
            }
        } catch (Throwable t) {
            State.scanning = false;
            Logs.add("ошибка startDiscovery: " + t);
            scheduleScan(15_000);
        }
        updateNotification();
    }

    private void onDeviceFound(Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
        String addr = device != null ? device.getAddress() : "";
        if (name == null && device != null) {
            try {
                name = device.getName();
            } catch (Throwable ignored) {
            }
        }
        if (name == null) {
            // устройство может не отдавать имя (например, занято соединением с другим
            // телефоном) — не выбрасываем его, MAC-совпадение всё равно сработает
            name = "";
        }
        short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) 0);
        if (name.length() > 0 && seenNames.add(name)) {
            Logs.add("в эфире: " + name);
        } else if (name.length() == 0) {
            Logs.add("в эфире без имени: " + addr);
        }
        handleSeen(name, addr, rssi);
    }

    /** Соединение/отключение BT-устройства (в т.ч. когда машина подключена к этому телефону). */
    private void onAclChanged(Intent intent, boolean connected) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null) {
            return;
        }
        String addr = device.getAddress();
        String name;
        try {
            name = device.getName();
        } catch (Throwable t) {
            name = null;
        }
        if (name == null) {
            name = "";
        }
        Logs.add((connected ? "подключено: " : "отключено: ") + (name.length() > 0 ? name : addr));
        if (connected) {
            handleSeen(name, addr, (short) 0);
        }
    }

    private void handleSeen(String name, String addr, short rssi) {
        String wantName = prefs.btName();
        String wantMac = prefs.btMac();
        boolean nameMatch = wantName.length() > 0 && name.equalsIgnoreCase(wantName);
        boolean macMatch = wantMac.length() > 0 && addr.equalsIgnoreCase(wantMac);
        if (!nameMatch && !macMatch) {
            return;
        }
        if (nameMatch && wantMac.length() == 0 && addr.length() > 0) {
            // запоминаем MAC: дальше машину можно ловить даже без имени в эфире
            prefs.setBtMac(addr);
            Logs.add("запомнил MAC " + addr + " для " + wantName);
        }
        State.lastSeen = System.currentTimeMillis();
        State.lastDevice = name + " (" + addr + ", rssi " + rssi + ")";
        Logs.add("увидел " + State.lastDevice);
        if (!Hotspot.isOn(this)) {
            String res = Hotspot.enable(this);
            State.hotspotOn = res.startsWith("OK");
            State.lastResult = res;
            Logs.add("включаю точку доступа -> " + res);
        } else {
            State.hotspotOn = true;
        }
        updateNotification();
    }

    /** Подключённые устройства через профили A2DP/HFP (машина может быть подключена к нам). */
    private void checkProfiles() {
        BluetoothProfile[] profiles = new BluetoothProfile[]{a2dpProfile, hfpProfile};
        for (BluetoothProfile p : profiles) {
            if (p == null) {
                continue;
            }
            try {
                for (BluetoothDevice d : p.getConnectedDevices()) {
                    String name;
                    try {
                        name = d.getName();
                    } catch (Throwable t) {
                        name = null;
                    }
                    handleSeen(name == null ? "" : name, d.getAddress(), (short) 0);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Если машина подключена к этому телефону — считаем её видимой, точка не гаснет. */
    private void checkConnectedDevices() {
        if (adapter == null) {
            return;
        }
        try {
            java.lang.reflect.Method m = BluetoothAdapter.class.getMethod("getConnectedDevices");
            Object res = m.invoke(adapter);
            if (res instanceof java.util.Set) {
                for (Object o : (java.util.Set<?>) res) {
                    BluetoothDevice d = (BluetoothDevice) o;
                    String addr = d.getAddress();
                    String name;
                    try {
                        name = d.getName();
                    } catch (Throwable t) {
                        name = null;
                    }
                    handleSeen(name == null ? "" : name, addr, (short) 0);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void checkHotspotTimeout() {
        int minutes = prefs.offAfterMin();
        if (minutes <= 0 || !State.hotspotOn) {
            return;
        }
        long last = State.lastSeen;
        if (last == 0) {
            last = State.lastScanStart;
        }
        if (last > 0 && System.currentTimeMillis() - last > minutes * 60_000L) {
            String res = Hotspot.disable(this);
            State.hotspotOn = false;
            State.lastResult = res;
            Logs.add("не вижу " + prefs.btName() + " уже " + minutes + " мин — выключаю точку доступа -> " + res);
            updateNotification();
        }
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Слежение за Bluetooth", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Автоточка")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        String text;
        if (State.hotspotOn) {
            text = "точка доступа включена";
        } else if (State.scanning) {
            text = "поиск " + prefs.btName() + "…";
        } else {
            text = "ожидание " + prefs.btName();
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(text));
        }
    }
}
