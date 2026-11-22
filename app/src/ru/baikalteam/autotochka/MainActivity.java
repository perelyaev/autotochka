package ru.baikalteam.autotochka;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.io.File;

/**
 * Один экран: сверху имя Bluetooth-устройства, по центру большая круглая кнопка.
 * Стиль — минималистичный, в духе shadcn: тёмный фон, тонкие границы, крупная типографика.
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFF09090B;          // фон
    private static final int BORDER = 0xFF27272A;      // границы
    private static final int FG = 0xFFFAFAFA;          // основной текст
    private static final int MUTED = 0xFFA1A1AA;       // вторичный текст
    private static final int PRIMARY = 0xFFFAFAFA;     // «белая» кнопка
    private static final int PRIMARY_FG = 0xFF18181B;  // текст на белой кнопке

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private TextView action;
    private TextView status;
    private EditText nameEdit;
    private boolean armed;   // нажатие уже привело к запросу разрешения

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
        paintSystemBars();
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

    private void paintSystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarDividerColor(BG);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            // тёмный фон — значит иконки в статус-баре должны быть светлыми
            w.getDecorView().setSystemUiVisibility(0);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(24), dp(32), dp(24), dp(32));

        // строка: иконка Bluetooth слева от поля, само поле — в рамке
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView btIcon = new ImageView(this);
        btIcon.setImageResource(R.drawable.ic_bluetooth);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconLp.setMargins(0, 0, dp(12), 0);
        row.addView(btIcon, iconLp);

        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(fieldBackground());
        field.setPadding(dp(16), 0, dp(16), 0);

        nameEdit = new EditText(this);
        nameEdit.setHint("Например, GEELY_BT");
        nameEdit.setText(prefs.btName());
        nameEdit.setHintTextColor(0xFF71717A);
        nameEdit.setTextColor(FG);
        nameEdit.setTextSize(16);
        nameEdit.setSingleLine(true);
        nameEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nameEdit.setBackground(null);
        nameEdit.setPadding(0, 0, 0, 0);
        field.addView(nameEdit, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(0,
                dp(52), 1f);
        row.addView(field, fieldLp);

        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        nameEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                prefs.setBtName(s.toString().trim());
            }
        });

        Space top = new Space(this);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.HORIZONTAL);
        center.setGravity(Gravity.CENTER);
        action = new TextView(this);
        action.setText("Включить");
        action.setTextSize(24);
        action.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        action.setGravity(Gravity.CENTER);
        action.setClickable(true);
        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPrimaryTap();
            }
        });
        center.addView(action, new LinearLayout.LayoutParams(dp(220), dp(220)));
        root.addView(center);

        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(MUTED);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(20), 0, 0);
        root.addView(status);

        Space bottom = new Space(this);
        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    /** Поле ввода в стиле shadcn: скруглённый прямоугольник с тонкой границей. */
    private GradientDrawable fieldBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(10));
        d.setColor(BG);
        d.setStroke(dp(1), BORDER);
        return d;
    }

    /** Нажатие главной кнопки: включить, выключить или попросить разрешение. */
    private void onPrimaryTap() {
        if (!prefs.enabled() && !Settings.System.canWrite(this)) {
            // без «Изменения системных настроек» точка доступа не поднимется
            armed = true;
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                Logs.add("не открылась страница WRITE_SETTINGS: " + t);
            }
            refresh();
            return;
        }

        if (prefs.enabled()) {
            prefs.setEnabled(false);
            stopService(new Intent(this, MonitorService.class));
            Logs.add("остановлено с экрана");
        } else {
            String name = nameEdit.getText().toString().trim();
            if (name.isEmpty()) {
                status.setText("Укажите имя Bluetooth-устройства");
                return;
            }
            prefs.setBtName(name);
            prefs.setEnabled(true);
            Intent i = new Intent(this, MonitorService.class);
            i.setAction(MonitorService.ACTION_START);
            startForegroundService(i);
            Logs.add("запущено с экрана, устройство: " + name);
        }
        refresh();
    }

    private void refresh() {
        boolean on = prefs.enabled();
        boolean canWrite = Settings.System.canWrite(this);

        action.setText(on ? "Выключить" : "Включить");
        if (on) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(BG);
            d.setStroke(dp(2), BORDER);
            action.setBackground(new RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x22FFFFFF), d, null));
            action.setTextColor(FG);
        } else {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(canWrite ? PRIMARY : 0xFF3F3F46);
            action.setBackground(new RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x22000000), d, null));
            action.setTextColor(canWrite ? PRIMARY_FG : FG);
        }

        String hint;
        if (!canWrite) {
            hint = armed ? "Разрешите «Изменение системных настроек» и вернитесь" :
                    "Нужно разрешение «Изменение системных настроек»";
        } else if (!on) {
            hint = "Готово к запуску";
        } else if (!State.serviceRunning) {
            hint = "Запускается…";
        } else if (State.hotspotOn) {
            hint = "Работает · точка доступа включена";
        } else if (State.scanning) {
            hint = "Работает · ищет машину";
        } else {
            hint = "Работает";
        }
        status.setText(hint);
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

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
