package ru.baikalteam.autotochka;

import android.content.Context;
import android.content.SharedPreferences;

/** Настройки приложения. */
public final class Prefs {

    private static final String NAME = "autotochka";
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public String btName() {
        return sp.getString("bt_name", "");
    }

    public void setBtName(String v) {
        sp.edit().putString("bt_name", v).apply();
    }

    public String btMac() {
        return sp.getString("bt_mac", "");
    }

    public void setBtMac(String v) {
        sp.edit().putString("bt_mac", v).apply();
    }

    public int scanGapSec() {
        return sp.getInt("scan_gap", 15);
    }

    public void setScanGapSec(int v) {
        sp.edit().putInt("scan_gap", v).apply();
    }

    public int offAfterMin() {
        return sp.getInt("off_after", 10);
    }

    public void setOffAfterMin(int v) {
        sp.edit().putInt("off_after", v).apply();
    }

    public boolean enabled() {
        return sp.getBoolean("enabled", false);
    }

    public void setEnabled(boolean v) {
        sp.edit().putBoolean("enabled", v).apply();
    }
}
