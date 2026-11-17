package ru.baikalteam.autotochka;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Автозапуск после перезагрузки. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Prefs prefs = new Prefs(context);
        if (!prefs.enabled()) {
            return;
        }
        Intent svc = new Intent(context, MonitorService.class);
        svc.setAction(MonitorService.ACTION_START);
        context.startForegroundService(svc);
    }
}
