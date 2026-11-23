package ru.baikalteam.autotochka;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Включение/выключение Wi-Fi точки доступа из приложения.
 *
 * Пробуем несколько путей (какой сработает на конкретной прошивке):
 *  1) TetheringManager.startTethering() через getSystemService("tethering");
 *  2) скрытый WifiManager.setWifiApEnabled() (на ColorOS он ещё жив).
 */
public final class Hotspot {

    private static final String TAG = "Autotochka";
    private static final int TETHERING_WIFI = 0;

    static {
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L");
            Log.i(TAG, "hidden api bypass установлен");
        } catch (Throwable t) {
            Log.w(TAG, "hidden api bypass не удался: " + t);
        }
    }

    private Hotspot() {
    }

    public static String enable(Context ctx) {
        String a = viaTetheringManager(ctx, true);
        Log.i(TAG, "enable via TetheringManager: " + a);
        if (a.startsWith("OK")) {
            return a;
        }
        String req = viaTetheringRequest(ctx);
        Log.i(TAG, "enable via TetheringRequest(entitlementUi): " + req);
        if (req.startsWith("OK")) {
            return req;
        }
        String b = viaWifiApEnabled(ctx, true);
        Log.i(TAG, "enable via setWifiApEnabled: " + b);
        if (b.startsWith("OK")) {
            return b;
        }
        return a + "; " + req + "; " + b;
    }

    /** TetheringRequest с shouldShowEntitlementUi=true (как делает MacroDroid). */
    private static String viaTetheringRequest(Context ctx) {
        try {
            Object tm = ctx.getSystemService("tethering");
            Class<?> builderCls = Class.forName("android.net.TetheringManager$TetheringRequest$Builder");
            Class<?> reqCls = Class.forName("android.net.TetheringManager$TetheringRequest");
            Object builder = builderCls.getConstructor(int.class).newInstance(TETHERING_WIFI);
            builderCls.getMethod("setShouldShowEntitlementUi", boolean.class).invoke(builder, true);
            Object request = builderCls.getMethod("build").invoke(builder);

            Class<?> cbIface = Class.forName("android.net.TetheringManager$StartTetheringCallback");
            final CountDownLatch latch = new CountDownLatch(1);
            final int[] error = {0};
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String n = method.getName();
                    if ("onTetheringStarted".equals(n)) {
                        latch.countDown();
                    } else if ("onTetheringFailed".equals(n)) {
                        if (args != null && args.length > 0 && args[0] instanceof Integer) {
                            error[0] = (Integer) args[0];
                        }
                        latch.countDown();
                    }
                    return null;
                }
            };
            Object callback = Proxy.newProxyInstance(cbIface.getClassLoader(),
                    new Class<?>[]{cbIface}, handler);
            Executor inline = new Executor() {
                @Override
                public void execute(Runnable r) {
                    r.run();
                }
            };
            Method m = tm.getClass().getMethod("startTethering", reqCls, Executor.class, cbIface);
            m.invoke(tm, request, inline, callback);
            if (!latch.await(20, TimeUnit.SECONDS)) {
                return "FAIL timeout";
            }
            return error[0] == 0 ? "OK tethering-request" : "FAIL err=" + error[0];
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return "FAIL " + c.getClass().getSimpleName() + ": " + c.getMessage();
        }
    }

    /** Так делает MacroDroid: старый скрытый ConnectivityManager.startTethering. */
    private static String viaConnectivityManager(Context ctx) {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.os.ResultReceiver rr = new android.os.ResultReceiver(null);
            Method m;
            try {
                m = cm.getClass().getMethod("startTethering", int.class,
                        android.os.ResultReceiver.class, boolean.class);
                m.invoke(cm, TETHERING_WIFI, rr, false);
                return "OK legacy-cm";
            } catch (NoSuchMethodException e) {
                m = cm.getClass().getMethod("startTethering", int.class,
                        android.os.ResultReceiver.class, boolean.class, String.class);
                m.invoke(cm, TETHERING_WIFI, rr, false, ctx.getPackageName());
                return "OK legacy-cm-pkg";
            }
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return "FAIL " + c.getClass().getSimpleName() + ": " + c.getMessage();
        }
    }

    public static String disable(Context ctx) {
        String a = viaTetheringManager(ctx, false);
        Log.i(TAG, "disable via TetheringManager: " + a);
        if (a.startsWith("OK")) {
            return a;
        }
        String b = viaWifiApEnabled(ctx, false);
        Log.i(TAG, "disable via setWifiApEnabled: " + b);
        return a + "; " + b;
    }

    public static boolean isOn(Context ctx) {
        // 1. Надёжнее всего — есть ли поднятый интерфейс точки доступа (ap0 и подобные).
        //    Работает на любой прошивке и не зависит от скрытых API.
        if (apInterfaceUp()) {
            return true;
        }
        // 2. Скрытый getWifiApState, если прошивка его отдаёт.
        //    ВАЖНО: 12 = ENABLED, 13 = DISABLING (а не «включено», как считалось раньше).
        try {
            Object wm = ctx.getSystemService(Context.WIFI_SERVICE);
            Method m = wm.getClass().getMethod("getWifiApState");
            int state = (Integer) m.invoke(wm);
            return state == 12;
        } catch (Throwable t) {
            Log.w(TAG, "getWifiApState failed: " + t);
        }
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Method m = cm.getClass().getMethod("getTetheredIfaces");
            String[] ifaces = (String[]) m.invoke(cm);
            if (ifaces != null) {
                for (String s : ifaces) {
                    if (s != null && (s.startsWith("ap") || s.startsWith("softap"))) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getTetheredIfaces failed: " + t);
        }
        return false;
    }

    /** Поднят ли сетевой интерфейс точки доступа (ap0, softap0, wlan1). */
    private static boolean apInterfaceUp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> e =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                java.net.NetworkInterface ni = e.nextElement();
                String n = ni.getName();
                if (n == null) {
                    continue;
                }
                boolean looksLikeAp = n.startsWith("ap") || n.startsWith("softap") || n.equals("wlan1");
                if (looksLikeAp && ni.isUp()) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "apInterfaceUp failed: " + t);
        }
        return false;
    }

    private static String viaTetheringManager(Context ctx, boolean on) {
        try {
            Object tm = ctx.getSystemService("tethering");
            if (tm == null) {
                return "FAIL no tethering service";
            }
            if (on) {
                Class<?> cbIface = Class.forName("android.net.TetheringManager$StartTetheringCallback");
                final CountDownLatch latch = new CountDownLatch(1);
                final int[] error = {0};
                InvocationHandler handler = new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String n = method.getName();
                        if ("onTetheringStarted".equals(n)) {
                            latch.countDown();
                        } else if ("onTetheringFailed".equals(n)) {
                            if (args != null && args.length > 0 && args[0] instanceof Integer) {
                                error[0] = (Integer) args[0];
                            }
                            latch.countDown();
                        }
                        return null;
                    }
                };
                Object callback = Proxy.newProxyInstance(cbIface.getClassLoader(),
                        new Class<?>[]{cbIface}, handler);
                Executor inline = new Executor() {
                    @Override
                    public void execute(Runnable r) {
                        r.run();
                    }
                };
                Method m = tm.getClass().getMethod("startTethering", int.class, Executor.class, cbIface);
                m.invoke(tm, TETHERING_WIFI, inline, callback);
                if (!latch.await(20, TimeUnit.SECONDS)) {
                    return "FAIL timeout";
                }
                return error[0] == 0 ? "OK tethering-manager" : "FAIL err=" + error[0];
            } else {
                Method m = tm.getClass().getMethod("stopTethering", int.class);
                m.invoke(tm, TETHERING_WIFI);
                return "OK tethering-manager";
            }
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return "FAIL " + c.getClass().getSimpleName() + ": " + c.getMessage();
        }
    }

    private static String viaWifiApEnabled(Context ctx, boolean on) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            Method m = WifiManager.class.getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            Object res = m.invoke(wm, null, on);
            return "OK setWifiApEnabled(" + res + ")";
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return "FAIL " + c.getClass().getSimpleName() + ": " + c.getMessage();
        }
    }
}
