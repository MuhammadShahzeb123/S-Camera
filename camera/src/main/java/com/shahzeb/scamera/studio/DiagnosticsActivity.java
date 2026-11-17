package com.shahzeb.scamera.studio;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.shahzeb.scamera.op.CameraDiagnostics;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Shows what the phone exposes to third party camera apps (camera ids, logical/physical cameras,
 *  concurrent camera support, manual ranges) plus the results of multi-camera open tests, and lets
 *  the user copy / share the report. Very useful for diagnosing OEM restrictions (e.g. OnePlus). */
public class DiagnosticsActivity extends Activity {
    private TextView text;
    private String report = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Camera info");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.START);
        Button copy = new Button(this);
        copy.setText("Copy");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Camera info", report));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        Button share = new Button(this);
        share.setText("Share");
        share.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, report);
            startActivity(Intent.createChooser(send, "Share camera info"));
        });
        Button test = new Button(this);
        test.setText("Test multi-cam");
        test.setOnClickListener(v -> runTests());
        buttons.addView(copy);
        buttons.addView(share);
        buttons.addView(test);
        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        text = new TextView(this);
        text.setTextColor(0xFFDDDDDD);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        text.setTextIsSelectable(true);
        scroll.addView(text);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
        setContentView(root);

        report = CameraDiagnostics.report(this);
        text.setText(report);
    }

    private void runTests() {
        text.setText(report + "\n\nRunning multi-camera tests...\n");
        new MultiCamProbe(this, result -> runOnUiThread(() -> {
            report = report + "\n\n=== Multi-camera tests ===\n" + result;
            text.setText(report);
            saveReport();
        })).run();
    }

    private void saveReport() {
        try {
            File dir = getExternalFilesDir(null);
            if( dir == null )
                return;
            File f = new File(dir, "camera_info.txt");
            try( FileOutputStream out = new FileOutputStream(f) ) {
                out.write(report.getBytes(StandardCharsets.UTF_8));
            }
        }
        catch(Exception ignored) {
        }
    }
}
