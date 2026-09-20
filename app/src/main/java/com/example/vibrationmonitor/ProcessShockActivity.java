package com.example.vibrationmonitor;

import android.app.Activity;
import android.os.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public class ProcessShockActivity extends Activity implements SensorEventListener {

    private SensorManager sm;
    private Sensor acc;

    private TextView status, cam, elapsed, total, peak, rms, impact, dir, last;
    private Spinner process, unit;
    private ShockGraph graph;

    private boolean running = false;
    private boolean calibrating = false;
    private boolean eventOn = false;
    private boolean gravityReady = false;

    private boolean cameraReady = false;
    private boolean cameraOpening = false;

    private long calibrationEndMs = 0L;
    private long startMs = 0L;
    private long n = 0L;
    private long lastUi = 0L;
    private long eventStart = 0L;
    private long lastAbove = 0L;
    private long lastClosed = 0L;

    private int impactCount = 0;
    private double sessionPeak = 0.0;
    private double sumSq = 0.0;
    private double sessionMaxX = 0.0;
    private double sessionMaxY = 0.0;
    private double sessionMaxZ = 0.0;
    private double spec = 2.0;

    private float gx = 0f, gy = 0f, gz = 0f;
    private static final float A = .90f;

    private final ArrayDeque<P> pre = new ArrayDeque<>();
    private final ArrayList<P> event = new ArrayList<>();

    private String ep = "", eu = "", base = "";
    private java.io.File photo = null;

    private android.hardware.camera2.CameraDevice camera;
    private android.hardware.camera2.CameraCaptureSession session;
    private android.media.ImageReader reader;
    private android.os.HandlerThread camThread;
    private android.os.Handler camHandler;
    private String cameraId;
    private volatile java.io.File photoTarget;

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + .5f);
    }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private TextView tv(String text, float size, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setPadding(dp(10), dp(8), dp(10), dp(8));
        return v;
    }

    private Button btn(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setBackground(bg(color, 14));
        return b;
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        spec = getSharedPreferences("VibrationSettings", MODE_PRIVATE)
                .getFloat("spec", 2f);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(18), dp(14), dp(28));
        root.setBackgroundColor(Color.rgb(238, 243, 248));
        sv.addView(root);

        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(16), dp(14), dp(16), dp(14));
        h.setBackground(bg(Color.rgb(15, 38, 61), 18));

        TextView title = tv("PROCESS SHOCK\nPROFILER", 23, Color.WHITE);
        title.setTypeface(null, 1);
        h.addView(title);
        h.addView(tv("CONTINUOUS EQUIPMENT IMPACT MONITORING", 12, Color.rgb(185, 207, 225)));

        status = tv("● READY", 15, Color.rgb(80, 220, 150));
        cam = tv("CAMERA : CHECKING...", 13, Color.rgb(185, 207, 225));
        h.addView(status);
        h.addView(cam);
        root.addView(h);

        TextView bb = tv("IMPACT BLACKBOX · PRE 3s + EVENT + POST 3s", 12, Color.rgb(70, 90, 105));
        bb.setGravity(Gravity.CENTER);
        root.addView(bb);

        root.addView(tv("PROCESS", 13, Color.DKGRAY));
        process = new Spinner(this);
        process.setAdapter(new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"STACK", "PACKAGE", "ACTIVATION"}
        ));
        root.addView(process);

        root.addView(tv("UNIT / ACTION", 13, Color.DKGRAY));
        unit = new Spinner(this);
        root.addView(unit);
        units(0);

        process.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                units(pos);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        LinearLayout live = new LinearLayout(this);
        live.setOrientation(LinearLayout.VERTICAL);
        live.setGravity(Gravity.CENTER);
        live.setPadding(dp(10), dp(10), dp(10), dp(12));
        live.setBackground(bg(Color.WHITE, 16));

        elapsed = tv("00:00:00", 18, Color.rgb(70, 90, 105));
        elapsed.setGravity(Gravity.CENTER);

        TextView tl = tv("TOTAL IMPACT", 13, Color.GRAY);
        tl.setGravity(Gravity.CENTER);

        total = tv("0.000 m/s²", 38, Color.rgb(15, 38, 61));
        total.setGravity(Gravity.CENTER);
        total.setTypeface(null, 1);

        live.addView(elapsed);
        live.addView(tl);
        live.addView(total);
        root.addView(live);

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);

        peak = tv("PEAK\n0.000", 16, Color.rgb(15, 38, 61));
        rms = tv("RMS\n0.000", 16, Color.rgb(15, 38, 61));
        impact = tv("IMPACT\n0", 16, Color.rgb(15, 38, 61));

        for (TextView v : new TextView[]{peak, rms, impact}) {
            v.setGravity(Gravity.CENTER);
            v.setBackground(bg(Color.WHITE, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(74), 1f);
            lp.setMargins(dp(3), dp(8), dp(3), dp(8));
            cards.addView(v, lp);
        }
        root.addView(cards);

        graph = new ShockGraph(this);
        root.addView(graph, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(280)
        ));

        dir = tv("MAIN DIRECTION : -", 16, Color.rgb(15, 38, 61));
        dir.setGravity(Gravity.CENTER);
        dir.setBackground(bg(Color.WHITE, 12));
        root.addView(dir);

        last = tv(
                "LAST IMPACT : -\n충격 시 사진 · 전후 트렌드 · CSV · 요약정보를 자동 저장합니다.",
                13,
                Color.rgb(70, 90, 105)
        );
        last.setBackground(bg(Color.WHITE, 12));
        root.addView(last);

        Button history = btn("IMPACT EVENT HISTORY", Color.rgb(42, 91, 126));
        LinearLayout.LayoutParams hp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        hp.setMargins(0, dp(9), 0, 0);
        root.addView(history, hp);
        history.setOnClickListener(v -> showHistory());

        Button start = btn("START MONITORING", Color.rgb(0, 145, 105));
        Button stop = btn("STOP & ANALYZE", Color.rgb(190, 55, 55));

        LinearLayout.LayoutParams p1 =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        p1.setMargins(0, dp(9), 0, 0);
        root.addView(start, p1);

        LinearLayout.LayoutParams p2 =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        p2.setMargins(0, dp(9), 0, 0);
        root.addView(stop, p2);

        start.setOnClickListener(v -> startMon());
        stop.setOnClickListener(v -> stopMon());

        setContentView(sv);
        setupCamera();
    }

    private void units(int p) {
        String[][] u = {
                {"Stack Transfer", "Alignment", "Stack Press", "Pick / Place"},
                {"Cell Transfer", "Sealing", "Gripper Pick / Place", "Conveyor Stop / Start"},
                {"Tray Loading", "Tray Unloading", "Conveyor", "Lift / Transfer"}
        };

        unit.setAdapter(new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                u[Math.max(0, Math.min(2, p))]
        ));
    }

    private void startMon() {
        if (acc == null) {
            Toast.makeText(this, "가속도 센서가 없습니다.", Toast.LENGTH_LONG).show();
            return;
        }

        running = true;
        calibrating = true;
        eventOn = false;
        gravityReady = false;

        calibrationEndMs = SystemClock.elapsedRealtime() + 2000L;
        startMs = 0L;
        n = 0L;
        sessionPeak = 0.0;
        sumSq = 0.0;
        sessionMaxX = 0.0;
        sessionMaxY = 0.0;
        sessionMaxZ = 0.0;
        impactCount = 0;

        pre.clear();
        event.clear();
        graph.clear();

        elapsed.setText("CALIBRATING 2.0s");
        total.setText("0.000 m/s²");
        peak.setText("PEAK\n0.000");
        rms.setText("RMS\n0.000");
        impact.setText("IMPACT\n0");
        dir.setText("MAIN DIRECTION : -");

        status.setText("● SENSOR CALIBRATION");
        status.setTextColor(Color.rgb(255, 185, 70));

        process.setEnabled(false);
        unit.setEnabled(false);

        if (!cameraReady) setupCamera();

        sm.unregisterListener(this);
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
    }

    private void stopMon() {
        if (!running) return;

        if (eventOn) finishEvent();

        running = false;
        calibrating = false;
        sm.unregisterListener(this);

        status.setText("● STOPPED / ANALYSIS READY");
        status.setTextColor(Color.rgb(255, 185, 70));

        process.setEnabled(true);
        unit.setEnabled(true);

        showSessionSummary();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!running) return;

        long now = SystemClock.elapsedRealtime();

        if (!gravityReady) {
            gx = e.values[0];
            gy = e.values[1];
            gz = e.values[2];
            gravityReady = true;
        } else {
            gx = A * gx + (1 - A) * e.values[0];
            gy = A * gy + (1 - A) * e.values[1];
            gz = A * gz + (1 - A) * e.values[2];
        }

        float x = e.values[0] - gx;
        float y = e.values[1] - gy;
        float z = e.values[2] - gz;
        double t = Math.sqrt(x * x + y * y + z * z);

        if (calibrating) {
            long remain = Math.max(0L, calibrationEndMs - now);

            if (now - lastUi > 80L) {
                lastUi = now;
                elapsed.setText(String.format(Locale.US, "CALIBRATING %.1fs", remain / 1000.0));
                total.setText(String.format(Locale.US, "%.3f m/s²", t));
            }

            if (now >= calibrationEndMs) {
                calibrating = false;
                startMs = now;
                n = 0L;
                sessionPeak = 0.0;
                sumSq = 0.0;
                sessionMaxX = 0.0;
                sessionMaxY = 0.0;
                sessionMaxZ = 0.0;
                pre.clear();
                event.clear();
                graph.clear();

                elapsed.setText("00:00:00");
                total.setText("0.000 m/s²");
                status.setText("● MONITORING");
                status.setTextColor(Color.rgb(80, 220, 150));

                Toast.makeText(
                        this,
                        "센서 안정화 완료 · 충격 감시 시작",
                        Toast.LENGTH_SHORT
                ).show();
            }
            return;
        }

        P p = new P(System.currentTimeMillis(), now, x, y, z, t);

        pre.addLast(p);
        while (!pre.isEmpty() && now - pre.peekFirst().mono > 3000L) {
            pre.removeFirst();
        }

        n++;
        sumSq += t * t;
        sessionPeak = Math.max(sessionPeak, t);
        sessionMaxX = Math.max(sessionMaxX, Math.abs(x));
        sessionMaxY = Math.max(sessionMaxY, Math.abs(y));
        sessionMaxZ = Math.max(sessionMaxZ, Math.abs(z));

        if (!eventOn && t >= spec && now - lastClosed >= 1000L) {
            beginEvent(now);
        }

        if (eventOn) {
            if (event.isEmpty() || event.get(event.size() - 1).mono != p.mono) {
                event.add(p);
            }

            if (t >= spec) {
                lastAbove = now;
            }

            if (now - lastAbove >= 3000L) {
                finishEvent();
            }
        }

        graph.add(x, y, z, t, spec);

        if (now - lastUi > 80L) {
            lastUi = now;

            long sec = Math.max(0L, (now - startMs) / 1000L);

            elapsed.setText(String.format(
                    Locale.US,
                    "%02d:%02d:%02d",
                    sec / 3600,
                    (sec / 60) % 60,
                    sec % 60
            ));

            total.setText(String.format(Locale.US, "%.3f m/s²", t));
            peak.setText(String.format(Locale.US, "PEAK\n%.3f", sessionPeak));
            rms.setText(String.format(
                    Locale.US,
                    "RMS\n%.3f",
                    Math.sqrt(sumSq / Math.max(1L, n))
            ));
            impact.setText("IMPACT\n" + impactCount);

            float ax = Math.abs(x);
            float ay = Math.abs(y);
            float az = Math.abs(z);

            dir.setText(
                    "MAIN DIRECTION : "
                            + (ax >= ay && ax >= az ? "X" : (ay >= az ? "Y" : "Z"))
                            + " AXIS"
            );
        }
    }

    private void beginEvent(long now) {
        eventOn = true;
        eventStart = now;
        lastAbove = now;
        impactCount++;

        ep = String.valueOf(process.getSelectedItem());
        eu = String.valueOf(unit.getSelectedItem());

        base = String.format(
                Locale.US,
                "IMPACT_%03d_%s",
                impactCount,
                new java.text.SimpleDateFormat(
                        "yyyyMMdd_HHmmss_SSS",
                        Locale.US
                ).format(new java.util.Date())
        );

        event.clear();
        event.addAll(pre);

        photo = new java.io.File(eventDir(), base + ".jpg");

        status.setText("● IMPACT #" + impactCount + " RECORDING");
        status.setTextColor(Color.rgb(255, 185, 70));

        takePhoto(photo);
    }

    private void finishEvent() {
        if (!eventOn) return;

        final int no = impactCount;
        final String pp = ep;
        final String uu = eu;
        final String bn = base;
        final java.io.File ph = photo;
        final ArrayList<P> d = new ArrayList<>(event);
        final long es = eventStart;
        final long la = lastAbove;

        eventOn = false;
        event.clear();
        lastClosed = SystemClock.elapsedRealtime();

        final Stats st = stats(d, es, la);

        last.setText(String.format(
                Locale.US,
                "LAST IMPACT #%03d · %s > %s\nPEAK %.3f | RMS %.3f | %.2fs | %s AXIS\n사진 · 그래프 · CSV · 요약 저장 중...",
                no,
                pp,
                uu,
                st.pk,
                st.rms,
                st.dur,
                st.axis
        ));

        if (running && !calibrating) {
            status.setText("● MONITORING");
            status.setTextColor(Color.rgb(80, 220, 150));
        }

        new Thread(() -> saveEvent(no, pp, uu, bn, ph, d, st)).start();
    }

    private Stats stats(ArrayList<P> d, long es, long la) {
        Stats s = new Stats();

        double q = 0.0;
        double mx = 0.0;
        double my = 0.0;
        double mz = 0.0;

        for (P p : d) {
            s.pk = Math.max(s.pk, p.t);
            q += p.t * p.t;
            mx = Math.max(mx, Math.abs(p.x));
            my = Math.max(my, Math.abs(p.y));
            mz = Math.max(mz, Math.abs(p.z));
        }

        s.rms = d.isEmpty() ? 0.0 : Math.sqrt(q / d.size());
        s.dur = Math.max(0.0, (la - es) / 1000.0);
        s.mx = mx;
        s.my = my;
        s.mz = mz;
        s.axis = mx >= my && mx >= mz ? "X" : (my >= mz ? "Y" : "Z");

        return s;
    }

    private void showSessionSummary() {
        long now = SystemClock.elapsedRealtime();
        long durationSec = startMs > 0L ? Math.max(0L, (now - startMs) / 1000L) : 0L;
        double sessionRms = n > 0L ? Math.sqrt(sumSq / n) : 0.0;

        String mainAxis =
                sessionMaxX >= sessionMaxY && sessionMaxX >= sessionMaxZ
                        ? "X"
                        : (sessionMaxY >= sessionMaxZ ? "Y" : "Z");

        String pp = String.valueOf(process.getSelectedItem());
        String uu = String.valueOf(unit.getSelectedItem());

        String summary = String.format(
                Locale.US,
                "PROCESS : %s\n"
                        + "UNIT / ACTION : %s\n\n"
                        + "측정시간 : %02d:%02d:%02d\n"
                        + "Impact : %d회\n"
                        + "최대 Peak : %.3f m/s²\n"
                        + "Session RMS : %.3f m/s²\n"
                        + "주 충격 방향 : %s AXIS\n"
                        + "X / Y / Z Peak : %.3f / %.3f / %.3f m/s²\n"
                        + "SPEC : %.3f m/s²\n\n"
                        + "충격 이벤트는 ShockEvents 폴더에\n사진 · 그래프 · CSV · 요약으로 저장됩니다.",
                pp,
                uu,
                durationSec / 3600,
                (durationSec / 60) % 60,
                durationSec % 60,
                impactCount,
                sessionPeak,
                sessionRms,
                mainAxis,
                sessionMaxX,
                sessionMaxY,
                sessionMaxZ,
                spec
        );

        new android.app.AlertDialog.Builder(this)
                .setTitle("SESSION SUMMARY")
                .setMessage(summary)
                .setPositiveButton("확인", null)
                .show();
    }

    private void saveEvent(
            int no,
            String pp,
            String uu,
            String bn,
            java.io.File ph,
            ArrayList<P> d,
            Stats st
    ) {
        java.io.File dir = eventDir();
        java.io.File csv = new java.io.File(dir, bn + ".csv");
        java.io.File png = new java.io.File(dir, bn + "_graph.png");
        java.io.File txt = new java.io.File(dir, bn + "_summary.txt");

        writeCsv(csv, pp, uu, d);
        writeGraph(png, d);
        writeSummary(txt, no, pp, uu, ph, png, csv, st);

        android.content.SharedPreferences sp =
                getSharedPreferences("TelegramSettings", MODE_PRIVATE);

        String token = sp.getString("bot_token", "");
        String ids = sp.getString("chat_id", "");

        String cap = String.format(
                Locale.US,
                "⚡ PROCESS SHOCK IMPACT #%03d\n"
                        + "Process : %s\n"
                        + "Unit / Action : %s\n"
                        + "PEAK : %.3f m/s²\n"
                        + "RMS : %.3f m/s²\n"
                        + "Duration : %.2f s\n"
                        + "Main Direction : %s\n"
                        + "X/Y/Z Peak : %.3f / %.3f / %.3f m/s²\n"
                        + "SPEC : %.3f m/s²\n"
                        + "Attached : photo + trend graph + CSV",
                no,
                pp,
                uu,
                st.pk,
                st.rms,
                st.dur,
                st.axis,
                st.mx,
                st.my,
                st.mz,
                spec
        );

        if (token != null
                && ids != null
                && !token.trim().isEmpty()
                && !ids.trim().isEmpty()) {

            TelegramSender.sendEventBundleMulti(
                    token.trim(),
                    ids.trim(),
                    cap,
                    ph,
                    png,
                    csv,
                    (ok, msg) -> runOnUiThread(() ->
                            last.append(
                                    ok
                                            ? "\nTelegram : SENT"
                                            : "\nTelegram : FAILED (" + msg + ")"
                            )
                    )
            );
        } else {
            runOnUiThread(() ->
                    last.append("\nTelegram : 설정 없음 · Local 저장 완료")
            );
        }

        runOnUiThread(() ->
                Toast.makeText(
                        this,
                        "Impact #" + no + " 블랙박스 저장 완료",
                        Toast.LENGTH_SHORT
                ).show()
        );
    }

    private java.io.File eventDir() {
        java.io.File d = new java.io.File(
                getExternalFilesDir(null),
                "ShockEvents"
        );

        if (!d.exists()) {
            d.mkdirs();
        }

        return d;
    }

    private String safe(String s) {
        return s == null
                ? ""
                : s.replace(",", " ")
                   .replace("\n", " ")
                   .replace("\r", " ");
    }

    private void writeCsv(
            java.io.File f,
            String pp,
            String uu,
            ArrayList<P> d
    ) {
        try (java.io.PrintWriter o =
                     new java.io.PrintWriter(
                             new java.io.OutputStreamWriter(
                                     new java.io.FileOutputStream(f),
                                     java.nio.charset.StandardCharsets.UTF_8
                             )
                     )) {

            o.println("DateTime,ElapsedMs,Process,UnitAction,X,Y,Z,Total,Spec");

            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss.SSS",
                            Locale.US
                    );

            long zero = d.isEmpty() ? 0L : d.get(0).mono;

            for (P p : d) {
                o.println(String.format(
                        Locale.US,
                        "%s,%d,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f",
                        fmt.format(new java.util.Date(p.wall)),
                        p.mono - zero,
                        safe(pp),
                        safe(uu),
                        p.x,
                        p.y,
                        p.z,
                        p.t,
                        spec
                ));
            }
        } catch (Exception ignored) {}
    }

    private void writeSummary(
            java.io.File f,
            int no,
            String pp,
            String uu,
            java.io.File ph,
            java.io.File png,
            java.io.File csv,
            Stats s
    ) {
        try (java.io.PrintWriter o =
                     new java.io.PrintWriter(
                             new java.io.OutputStreamWriter(
                                     new java.io.FileOutputStream(f),
                                     java.nio.charset.StandardCharsets.UTF_8
                             )
                     )) {

            o.println("PROCESS SHOCK EVENT #" + String.format(Locale.US, "%03d", no));
            o.println("Time : " + new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.US
            ).format(new java.util.Date()));

            o.println("Process : " + pp);
            o.println("Unit / Action : " + uu);

            o.println(String.format(
                    Locale.US,
                    "Peak : %.3f m/s²\n"
                            + "RMS : %.3f m/s²\n"
                            + "Impact Duration : %.2f sec\n"
                            + "Main Direction : %s AXIS\n"
                            + "X / Y / Z Peak : %.3f / %.3f / %.3f m/s²\n"
                            + "SPEC : %.3f m/s²",
                    s.pk,
                    s.rms,
                    s.dur,
                    s.axis,
                    s.mx,
                    s.my,
                    s.mz,
                    spec
            ));

            o.println("Photo : " + (ph == null ? "-" : ph.getName()));
            o.println("Graph : " + png.getName());
            o.println("CSV : " + csv.getName());
            o.println("Window : PRE 3 sec + IMPACT + POST 3 sec");

        } catch (Exception ignored) {}
    }

    private void writeGraph(
            java.io.File f,
            ArrayList<P> d
    ) {
        if (d.size() < 2) return;

        try {
            int W = 1200, H = 700, L = 90, R = 1160, T = 60, B = 610;

            Bitmap bm = Bitmap.createBitmap(
                    W,
                    H,
                    Bitmap.Config.ARGB_8888
            );

            Canvas c = new Canvas(bm);
            c.drawColor(Color.WHITE);

            Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
            grid.setColor(Color.rgb(220, 226, 232));
            grid.setStrokeWidth(2f);

            Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
            red.setColor(Color.RED);
            red.setStrokeWidth(4f);

            Paint[] ps = new Paint[4];
            int[] cs = {
                    Color.rgb(0, 130, 220),
                    Color.rgb(0, 170, 110),
                    Color.rgb(230, 145, 20),
                    Color.rgb(110, 75, 190)
            };

            for (int k = 0; k < 4; k++) {
                ps[k] = new Paint(Paint.ANTI_ALIAS_FLAG);
                ps[k].setColor(cs[k]);
                ps[k].setStyle(Paint.Style.STROKE);
                ps[k].setStrokeWidth(k == 3 ? 6f : 4f);
            }

            double m = Math.max(3.0, spec * 1.5);

            for (P p : d) {
                m = Math.max(
                        m,
                        Math.max(
                                p.t,
                                Math.max(
                                        Math.abs(p.x),
                                        Math.max(Math.abs(p.y), Math.abs(p.z))
                                )
                        ) * 1.15
                );
            }

            for (int i = 0; i <= 4; i++) {
                float y = T + (B - T) * i / 4f;
                c.drawLine(L, y, R, y, grid);
            }

            float sy = B - (float) (spec / m * (B - T));
            c.drawLine(L, sy, R, sy, red);

            android.graphics.Path[] path = {
                    new android.graphics.Path(),
                    new android.graphics.Path(),
                    new android.graphics.Path(),
                    new android.graphics.Path()
            };

            for (int i = 0; i < d.size(); i++) {
                P p = d.get(i);
                double[] vv = {
                        Math.abs(p.x),
                        Math.abs(p.y),
                        Math.abs(p.z),
                        p.t
                };

                float x = L + (R - L) * (i / (float) (d.size() - 1));

                for (int k = 0; k < 4; k++) {
                    float y = B - (float) (vv[k] / m * (B - T));

                    if (i == 0) {
                        path[k].moveTo(x, y);
                    } else {
                        path[k].lineTo(x, y);
                    }
                }
            }

            for (int k = 0; k < 4; k++) {
                c.drawPath(path[k], ps[k]);
            }

            try (java.io.FileOutputStream o =
                         new java.io.FileOutputStream(f)) {
                bm.compress(Bitmap.CompressFormat.PNG, 100, o);
            }

            bm.recycle();

        } catch (Exception ignored) {}
    }

    private void showHistory() {
        java.io.File[] files = eventDir().listFiles(
                (dir, name) -> name.endsWith("_summary.txt")
        );

        if (files == null || files.length == 0) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("IMPACT EVENT HISTORY")
                    .setMessage("저장된 충격 이벤트가 없습니다.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }

        Arrays.sort(files, (a, b) ->
                Long.compare(b.lastModified(), a.lastModified())
        );

        String[] names = new String[files.length];

        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].getName().replace("_summary.txt", "");
        }

        final java.io.File[] list = files;

        new android.app.AlertDialog.Builder(this)
                .setTitle("IMPACT EVENT HISTORY")
                .setItems(names, (dialog, which) -> showSummaryFile(list[which]))
                .setNegativeButton("닫기", null)
                .show();
    }

    private void showSummaryFile(java.io.File file) {
        StringBuilder sb = new StringBuilder();

        try (java.io.BufferedReader br =
                     new java.io.BufferedReader(
                             new java.io.InputStreamReader(
                                     new java.io.FileInputStream(file),
                                     java.nio.charset.StandardCharsets.UTF_8
                             )
                     )) {

            String line;

            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }

        } catch (Exception e) {
            sb.append("읽기 오류 : ").append(e.getMessage());
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("IMPACT DETAIL")
                .setMessage(sb.toString())
                .setPositiveButton("닫기", null)
                .show();
    }

    private void setupCamera() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {

            cam.setText("CAMERA : PERMISSION REQUIRED");

            requestPermissions(
                    new String[]{android.Manifest.permission.CAMERA},
                    2202
            );
            return;
        }

        openCamera();
    }

    private void cameraThread() {
        if (camThread != null) return;

        camThread = new android.os.HandlerThread("ShockCamera");
        camThread.start();

        camHandler = new android.os.Handler(
                camThread.getLooper()
        );
    }

    private void openCamera() {
        if (camera != null || cameraOpening) return;

        cameraOpening = true;
        cameraThread();

        try {
            android.hardware.camera2.CameraManager m =
                    (android.hardware.camera2.CameraManager)
                            getSystemService(CAMERA_SERVICE);

            cameraId = null;

            for (String id : m.getCameraIdList()) {
                Integer face =
                        m.getCameraCharacteristics(id)
                         .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);

                if (face != null
                        && face ==
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }

            if (cameraId == null) {
                cameraOpening = false;
                cam.setText("CAMERA : NOT FOUND");
                return;
            }

            reader = android.media.ImageReader.newInstance(
                    1280,
                    720,
                    android.graphics.ImageFormat.JPEG,
                    2
            );

            reader.setOnImageAvailableListener(
                    r -> {
                        android.media.Image im = null;

                        try {
                            im = r.acquireLatestImage();
                            java.io.File f = photoTarget;

                            if (im == null || f == null) return;

                            java.nio.ByteBuffer b =
                                    im.getPlanes()[0].getBuffer();

                            byte[] bytes =
                                    new byte[b.remaining()];

                            b.get(bytes);

                            try (java.io.FileOutputStream o =
                                         new java.io.FileOutputStream(f)) {
                                o.write(bytes);
                            }

                            photoTarget = null;

                            runOnUiThread(() ->
                                    cam.setText("CAMERA : READY · EVENT PHOTO SAVED")
                            );

                        } catch (Exception ignored) {
                        } finally {
                            if (im != null) {
                                im.close();
                            }
                        }
                    },
                    camHandler
            );

            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(android.Manifest.permission.CAMERA)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                cameraOpening = false;
                return;
            }

            m.openCamera(
                    cameraId,
                    new android.hardware.camera2.CameraDevice.StateCallback() {

                        @Override
                        public void onOpened(android.hardware.camera2.CameraDevice c) {
                            camera = c;
                            cameraReady = true;
                            cameraOpening = false;

                            runOnUiThread(() ->
                                    cam.setText("CAMERA : READY")
                            );
                        }

                        @Override
                        public void onDisconnected(android.hardware.camera2.CameraDevice c) {
                            c.close();
                            camera = null;
                            cameraReady = false;
                            cameraOpening = false;
                        }

                        @Override
                        public void onError(android.hardware.camera2.CameraDevice c, int e) {
                            c.close();
                            camera = null;
                            cameraReady = false;
                            cameraOpening = false;

                            runOnUiThread(() ->
                                    cam.setText("CAMERA : ERROR " + e)
                            );
                        }
                    },
                    camHandler
            );

        } catch (Exception e) {
            cameraOpening = false;
            cameraReady = false;
            cam.setText("CAMERA : ERROR");
        }
    }

    private void takePhoto(java.io.File f) {
        if (!cameraReady || camera == null || reader == null) {
            cam.setText("CAMERA : NOT READY · EVENT WITHOUT PHOTO");
            return;
        }

        photoTarget = f;

        try {
            if (session != null) {
                session.close();
            }

            android.view.Surface s = reader.getSurface();

            camera.createCaptureSession(
                    java.util.Collections.singletonList(s),
                    new android.hardware.camera2.CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(
                                android.hardware.camera2.CameraCaptureSession ss
                        ) {
                            session = ss;

                            try {
                                android.hardware.camera2.CaptureRequest.Builder b =
                                        camera.createCaptureRequest(
                                                android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE
                                        );

                                b.addTarget(s);

                                ss.capture(
                                        b.build(),
                                        null,
                                        camHandler
                                );

                            } catch (Exception e) {
                                photoTarget = null;
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                android.hardware.camera2.CameraCaptureSession ss
                        ) {
                            photoTarget = null;
                        }
                    },
                    camHandler
            );

        } catch (Exception e) {
            photoTarget = null;
        }
    }

    private void closeCamera() {
        cameraReady = false;
        cameraOpening = false;

        try {
            if (session != null) session.close();
        } catch (Exception ignored) {}
        session = null;

        try {
            if (camera != null) camera.close();
        } catch (Exception ignored) {}
        camera = null;

        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {}
        reader = null;

        if (camThread != null) {
            camThread.quitSafely();
            camThread = null;
            camHandler = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int r,
            String[] p,
            int[] g
    ) {
        super.onRequestPermissionsResult(r, p, g);

        if (r == 2202) {
            if (g.length > 0
                    && g[0]
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                cam.setText("CAMERA : PERMISSION DENIED");
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) {}

    @Override
    protected void onPause() {
        super.onPause();

        if (running) {
            sm.unregisterListener(this);
        }

        closeCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (running && acc != null) {
            sm.registerListener(
                    this,
                    acc,
                    SensorManager.SENSOR_DELAY_GAME
            );
        }

        if (cam != null) {
            setupCamera();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sm.unregisterListener(this);
        closeCamera();
    }

    static class P {
        long wall;
        long mono;
        float x, y, z;
        double t;

        P(long w, long m, float X, float Y, float Z, double T) {
            wall = w;
            mono = m;
            x = X;
            y = Y;
            z = Z;
            t = T;
        }
    }

    static class Stats {
        double pk = 0.0;
        double rms = 0.0;
        double dur = 0.0;
        double mx = 0.0;
        double my = 0.0;
        double mz = 0.0;
        String axis = "-";
    }

    static class ShockGraph extends View {
        ArrayList<Float> x = new ArrayList<>();
        ArrayList<Float> y = new ArrayList<>();
        ArrayList<Float> z = new ArrayList<>();
        ArrayList<Float> t = new ArrayList<>();

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        double spec = 2.0;

        ShockGraph(android.content.Context c) {
            super(c);
            setBackgroundColor(Color.WHITE);
        }

        void put(ArrayList<Float> a, float q) {
            a.add(q);
            if (a.size() > 240) {
                a.remove(0);
            }
        }

        void add(float X, float Y, float Z, double T, double s) {
            put(x, Math.abs(X));
            put(y, Math.abs(Y));
            put(z, Math.abs(Z));
            put(t, (float) T);
            spec = s;
            invalidate();
        }

        void clear() {
            x.clear();
            y.clear();
            z.clear();
            t.clear();
            invalidate();
        }

        void line(Canvas c, ArrayList<Float> a, int col, float m) {
            if (a.size() < 2) return;

            p.setColor(col);
            p.setStrokeWidth(
                    col == Color.rgb(110, 75, 190)
                            ? 5f
                            : 3f
            );

            for (int i = 1; i < a.size(); i++) {
                float x1 = (i - 1) * getWidth() / 239f;
                float x2 = i * getWidth() / 239f;

                c.drawLine(
                        x1,
                        getHeight() - a.get(i - 1) / m * getHeight(),
                        x2,
                        getHeight() - a.get(i) / m * getHeight(),
                        p
                );
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);

            float m = (float) Math.max(3.0, spec * 1.5);

            for (float q : t) {
                m = Math.max(m, q * 1.15f);
            }

            p.setColor(Color.rgb(220, 225, 230));
            p.setStrokeWidth(1f);

            for (int i = 1; i < 4; i++) {
                c.drawLine(
                        0,
                        getHeight() * i / 4f,
                        getWidth(),
                        getHeight() * i / 4f,
                        p
                );
            }

            float sy =
                    (float) (
                            getHeight()
                                    - spec / m * getHeight()
                    );

            p.setColor(Color.RED);
            p.setStrokeWidth(2f);

            c.drawLine(
                    0,
                    sy,
                    getWidth(),
                    sy,
                    p
            );

            line(c, x, Color.rgb(0, 130, 220), m);
            line(c, y, Color.rgb(0, 170, 110), m);
            line(c, z, Color.rgb(230, 145, 20), m);
            line(c, t, Color.rgb(110, 75, 190), m);
        }
    }
}
