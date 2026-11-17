package ru.baikalteam.autotochka;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Небольшой журнал в памяти + файл, чтобы показывать в интерфейсе. */
public final class Logs {

    private static final String TAG = "Autotochka";
    private static final int MAX_LINES = 300;
    private static final List<String> LINES = new ArrayList<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static File file;

    private Logs() {
    }

    public static synchronized void init(File f) {
        file = f;
    }

    public static synchronized void add(String msg) {
        String line = TIME.format(new Date()) + "  " + msg;
        Log.i(TAG, msg);
        LINES.add(line);
        while (LINES.size() > MAX_LINES) {
            LINES.remove(0);
        }
        if (file != null) {
            try (FileWriter w = new FileWriter(file, true)) {
                w.write(line + "\n");
            } catch (Throwable ignored) {
            }
        }
    }

    public static synchronized String tail(int count) {
        int from = Math.max(0, LINES.size() - count);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < LINES.size(); i++) {
            sb.append(LINES.get(i)).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}
