package com.megathon.incidentvoice.probe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import com.megathon.incidentvoice.engine.NativeBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Plan A probe (PRD §26.6): can an app-UID process open the Hexagon NPU on this firmware?
 * Loads the real libiv_native.so, initialises Genie (LLM only) from the models dir and runs "Say OK.".
 * Result → screen, logcat tag IVPROBE, and <externalFilesDir>/probe_result.txt (adb pull-able).
 * Models dir: <externalFilesDir>/models (push llama/ + dsp/ there) or an override passed via intent extra "models".
 */
public class MainActivity extends Activity {
    private TextView tv;
    private final StringBuilder out = new StringBuilder();

    private void say(String s) {
        Log.i("IVPROBE", s);
        out.append(s).append('\n');
        runOnUiThread(() -> tv.setText(out.toString()));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tv = new TextView(this);
        tv.setTextSize(13);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
        final String override = getIntent().getStringExtra("models");
        new Thread(() -> run(override)).start();
    }

    private void run(String override) {
        File ext = getExternalFilesDir(null);
        String models = override != null ? override : new File(ext, "models").getAbsolutePath();
        String libDir = getApplicationInfo().nativeLibraryDir;
        say("uid=" + android.os.Process.myUid() + " models=" + models + " libDir=" + libDir);
        String[] libs = {"c++_shared", "QnnSystem", "QnnHtp", "QnnHtpV79Stub", "Genie", "iv_native"};
        for (String l : libs) {
            try { System.loadLibrary(l); say("loaded lib" + l + ".so"); }
            catch (Throwable t) { say("FAILED lib" + l + ": " + t.getMessage()); }
        }
        String verdict;
        try {
            long t0 = System.currentTimeMillis();
            String info = NativeBridge.init(models, libDir, "{\"load_asr\":false,\"load_llm\":true}");
            say("init (" + (System.currentTimeMillis() - t0) + " ms): " + info);
            if (info.contains("\"error\"")) {
                verdict = "PLAN_A_FAIL init: " + info;
            } else {
                String p = "<|start_header_id|>user<|end_header_id|>\n\nSay OK.<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n";
                String g = NativeBridge.llmGenerate(p, "WARMUP", "greedy", 8, null);
                say("generate: " + g);
                verdict = g.contains("\"error\"") ? "PLAN_A_FAIL generate: " + g : "PLAN_A_OK";
            }
        } catch (Throwable t) {
            verdict = "PLAN_A_FAIL exception: " + t;
        }
        say("VERDICT " + verdict);
        try (FileOutputStream fo = new FileOutputStream(new File(ext, "probe_result.txt"))) {
            fo.write(out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { say("write failed " + e); }
    }
}
