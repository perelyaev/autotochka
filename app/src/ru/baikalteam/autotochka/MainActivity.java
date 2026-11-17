package ru.baikalteam.autotochka;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private TextView status;
    private TextView logView;
    private EditText nameEdit;
    private EditText gapEdit;
    private EditText offEdit;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        if (getIntent() != null && getIntent().hasExtra("set_name")) {
            prefs.setBtName(getIntent().getStringExtra("set_name"));
            Logs.add("имя из интента: " + prefs.btName());
        }
        Logs.init(new File(getFilesDir(), "monitor.log"));
        setContentView(buildUi());
        requestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Автоточка");
        title.setTextSize(22);
        title.setGravity(Gravity.START);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button start = new Button(this);
        start.setText("Старт");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setEnabled(true);
                Intent i = new Intent(MainActivity.this, MonitorService.class);
                i.setAction(MonitorService.ACTION_START);
                startForegroundService(i);
                Logs.add("запуск из интерфейса");
            }
        });
        Button stop = new Button(this);
        stop.setText("Стоп");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setEnabled(false);
                stopService(new Intent(MainActivity.this, MonitorService.class));
            }
        });
        row1.addView(start, weight());
        row1.addView(stop, weight());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button testOn = new Button(this);
        testOn.setText("Точка вкл");
        testOn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, MonitorService.class);
                i.setAction(MonitorService.ACTION_TEST_ON);
                startForegroundService(i);
            }
        });
        Button testOff = new Button(this);
        testOff.setText("Точка выкл");
        testOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, MonitorService.class);
                i.setAction(MonitorService.ACTION_TEST_OFF);
                startForegroundService(i);
            }
        });
        row2.addView(testOn, weight());
        row2.addView(testOff, weight());
        root.addView(row2);

        Button writeSettings = new Button(this);
        writeSettings.setText("Разрешить изменение настроек");
        writeSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    Logs.add("не открылась страница WRITE_SETTINGS: " + t);
                }
            }
        });
        root.addView(writeSettings);

        TextView settingsTitle = new TextView(this);
        settingsTitle.setText("Настройки");
        settingsTitle.setPadding(0, dp(12), 0, 0);
        root.addView(settingsTitle);

        nameEdit = new EditText(this);
        nameEdit.setHint("Имя Bluetooth машины");
        nameEdit.setText(prefs.btName());
        nameEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(nameEdit);

        gapEdit = new EditText(this);
        gapEdit.setHint("Пауза между поисками, сек");
        gapEdit.setText(String.valueOf(prefs.scanGapSec()));
        gapEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(gapEdit);

        offEdit = new EditText(this);
        offEdit.setHint("Выключать, если не видно N минут (0 — не выключать)");
        offEdit.setText(String.valueOf(prefs.offAfterMin()));
        offEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(offEdit);

        Button save = new Button(this);
        save.setText("Сохранить");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setBtName(nameEdit.getText().toString().trim());
                prefs.setScanGapSec(parseInt(gapEdit.getText().toString(), 15));
                prefs.setOffAfterMin(parseInt(offEdit.getText().toString(), 10));
                Logs.add("настройки сохранены: имя=" + prefs.btName()
                        + ", пауза=" + prefs.scanGapSec() + "с, выкл. через " + prefs.offAfterMin() + " мин");
            }
        });
        root.addView(save);

        Button clear = new Button(this);
        clear.setText("Очистить журнал");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Logs.clear();
                logView.setText("");
            }
        });
        root.addView(clear);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setPadding(0, dp(8), 0, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, lp);

        return root;
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        sb.append("Сервис: ").append(State.serviceRunning ? "работает" : "остановлен").append('\n');
        sb.append("Сканирование: ").append(State.scanning ? "идёт" : "ожидание").append('\n');
        sb.append("Точка доступа: ").append(State.hotspotOn ? "включена" : "выключена").append('\n');
        sb.append("Изменение системных настроек: ")
                .append(Settings.System.canWrite(this) ? "разрешено" : "НЕ разрешено").append('\n');
        if (State.lastSeen > 0) {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
            sb.append("Последний раз видел: ").append(f.format(new Date(State.lastSeen))).append('\n');
        }
        if (!State.lastDevice.isEmpty()) {
            sb.append("Устройство: ").append(State.lastDevice).append('\n');
        }
        sb.append("Поисков: ").append(State.scans).append('\n');
        if (!State.lastResult.isEmpty()) {
            sb.append("Результат: ").append(State.lastResult).append('\n');
        }
        status.setText(sb.toString());
        logView.setText(Logs.tail(40));
    }

    private void requestPermissions() {
        String[] perms = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION}
                : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION};
        boolean need = false;
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                need = true;
                break;
            }
        }
        if (need) {
            requestPermissions(perms, 1);
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }
}
