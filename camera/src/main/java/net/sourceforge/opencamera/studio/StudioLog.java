package net.sourceforge.opencamera.studio;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Small in-memory log (shown in Camera info) so camera problems on a specific phone can be
 *  diagnosed without adb. */
public final class StudioLog {
    private static final String TAG = "Studio";
    private static final int MAX = 300;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private StudioLog() {
    }

    public static synchronized void i(String msg) {
        Log.i(TAG, msg);
        add("I " + msg);
    }

    public static synchronized void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        add("E " + msg + (t != null ? (": " + t) : ""));
    }

    public static synchronized void e(String msg) {
        e(msg, null);
    }

    private static void add(String s) {
        lines.addLast(fmt.format(new Date()) + " " + s);
        while( lines.size() > MAX )
            lines.removeFirst();
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder("=== Studio log ===\n");
        for(String s : lines)
            sb.append(s).append('\n');
        return sb.toString();
    }
}
