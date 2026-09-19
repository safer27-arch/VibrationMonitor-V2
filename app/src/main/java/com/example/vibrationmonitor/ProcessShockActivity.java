package com.example.vibrationmonitor;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public class ProcessShockActivity extends Activity implements SensorEventListener {
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private TextView statusText, elapsedText, totalText, peakText, rmsText, impactText, directionText;
    private Spinner processSpinner, unitSpinner;
    private ShockGraph graph;

    private boolean running = false;
    private boolean overThreshold = false;
    private long startMs = 0L;
    private long sampleCount = 0L;
    private long lastUiMs = 0L;
    private int impactCount = 0;
    private double peakValue = 0.0;
    private double sumSq = 0.0;
    private double spec = 2.0;

    private float gravityX = 0f, gravityY = 0f, gravityZ = 0f;
    private static final float ALPHA = 0.90f;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable background(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private TextView label(String text, float size, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setPadding(dp(10), dp(8), dp(10), dp(8));
        return v;
    }

    private Button actionButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setBackground(background(color, 14));
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        spec = getSharedPreferences("VibrationSettings", MODE_PRIVATE).getFloat("spec", 2.0f);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(18), dp(14), dp(28));
        root.setBackgroundColor(Color.rgb(238, 243, 248));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(14), dp(16), dp(14));
        header.setBackground(background(Color.rgb(15, 38, 61), 18));

        TextView title = label("PROCESS SHOCK PROFILER", 23, Color.WHITE);
        title.setTypeface(null, 1);
        TextView subtitle = label("CONTINUOUS EQUIPMENT IMPACT MONITORING", 12, Color.rgb(185, 207, 225));
        statusText = label("● READY", 15, Color.rgb(80, 220, 150));

        header.addView(title);
        header.addView(subtitle);
        header.addView(statusText);
        root.addView(header);

        root.addView(label("PROCESS", 13, Color.DKGRAY));
        processSpinner = new Spinner(this);
        processSpinner.setAdapter(new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"STACK", "PACKAGE", "ACTIVATION"}
        ));
        root.addView(processSpinner);

        root.addView(label("UNIT / ACTION", 13, Color.DKGRAY));
        unitSpinner = new Spinner(this);
        root.addView(unitSpinner);
        setUnits(0);

        processSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                setUnits(position);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        LinearLayout liveCard = new LinearLayout(this);
        liveCard.setOrientation(LinearLayout.VERTICAL);
        liveCard.setGravity(Gravity.CENTER);
        liveCard.setPadding(dp(10), dp(10), dp(10), dp(12));
        liveCard.setBackground(background(Color.WHITE, 16));

        elapsedText = label("00:00:00", 18, Color.rgb(70, 90, 105));
        elapsedText.setGravity(Gravity.CENTER);
        TextView totalLabel = label("TOTAL IMPACT", 13, Color.GRAY);
        totalLabel.setGravity(Gravity.CENTER);
        totalText = label("0.000 m/s²", 38, Color.rgb(15, 38, 61));
        totalText.setGravity(Gravity.CENTER);
        totalText.setTypeface(null, 1);

        liveCard.addView(elapsedText);
        liveCard.addView(totalLabel);
        liveCard.addView(totalText);
        root.addView(liveCard);

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);

        peakText = label("PEAK\n0.000", 16, Color.rgb(15, 38, 61));
        rmsText = label("RMS\n0.000", 16, Color.rgb(15, 38, 61));
        impactText = label("IMPACT\n0", 16, Color.rgb(15, 38, 61));

        for (TextView v : new TextView[]{peakText, rmsText, impactText}) {
            v.setGravity(Gravity.CENTER);
            v.setBackground(background(Color.WHITE, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(74), 1f);
            lp.setMargins(dp(3), dp(8), dp(3), dp(8));
            cards.addView(v, lp);
        }
        root.addView(cards);

        graph = new ShockGraph(this);
        root.addView(graph, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));

        directionText = label("MAIN DIRECTION : -", 16, Color.rgb(15, 38, 61));
        directionText.setGravity(Gravity.CENTER);
        directionText.setBackground(background(Color.WHITE, 12));
        root.addView(directionText);

        Button startButton = actionButton("START MONITORING", Color.rgb(0, 145, 105));
        Button stopButton = actionButton("STOP & ANALYZE", Color.rgb(190, 55, 55));

        LinearLayout.LayoutParams startLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        startLp.setMargins(0, dp(9), 0, 0);
        root.addView(startButton, startLp);

        LinearLayout.LayoutParams stopLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        stopLp.setMargins(0, dp(9), 0, 0);
        root.addView(stopButton, stopLp);

        startButton.setOnClickListener(v -> startMonitoring());
        stopButton.setOnClickListener(v -> stopMonitoring());

        setContentView(scroll);
    }

    private void setUnits(int processIndex) {
        String[][] units = {
                {"Stack Transfer", "Alignment", "Stack Press", "Pick / Place"},
                {"Cell Transfer", "Sealing", "Gripper Pick / Place", "Conveyor Stop / Start"},
                {"Tray Loading", "Tray Unloading", "Conveyor", "Lift / Transfer"}
        };
        int index = Math.max(0, Math.min(2, processIndex));
        unitSpinner.setAdapter(new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_dropdown_item, units[index]));
    }

    private void startMonitoring() {
        if (accelerometer == null) {
            Toast.makeText(this, "가속도 센서가 없습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        running = true;
        overThreshold = false;
        startMs = SystemClock.elapsedRealtime();
        sampleCount = 0L;
        peakValue = 0.0;
        sumSq = 0.0;
        impactCount = 0;
        graph.clear();
        statusText.setText("● MONITORING");
        statusText.setTextColor(Color.rgb(80, 220, 150));
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
    }

    private void stopMonitoring() {
        running = false;
        sensorManager.unregisterListener(this);
        statusText.setText("● STOPPED / ANALYSIS READY");
        statusText.setTextColor(Color.rgb(255, 185, 70));
        Toast.makeText(this, "연속 측정 종료 · Impact " + impactCount + "회", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!running) return;

        gravityX = ALPHA * gravityX + (1 - ALPHA) * event.values[0];
        gravityY = ALPHA * gravityY + (1 - ALPHA) * event.values[1];
        gravityZ = ALPHA * gravityZ + (1 - ALPHA) * event.values[2];

        float x = event.values[0] - gravityX;
        float y = event.values[1] - gravityY;
        float z = event.values[2] - gravityZ;
        double total = Math.sqrt(x * x + y * y + z * z);

        sampleCount++;
        sumSq += total * total;
        peakValue = Math.max(peakValue, total);

        if (total >= spec && !overThreshold) {
            impactCount++;
            overThreshold = true;
        } else if (total < spec * 0.80) {
            overThreshold = false;
        }

        graph.add(x, y, z, total, spec);

        long now = SystemClock.elapsedRealtime();
        if (now - lastUiMs > 80) {
            lastUiMs = now;
            long sec = (now - startMs) / 1000L;

            elapsedText.setText(String.format(Locale.US, "%02d:%02d:%02d",
                    sec / 3600, (sec / 60) % 60, sec % 60));
            totalText.setText(String.format(Locale.US, "%.3f m/s²", total));
            peakText.setText(String.format(Locale.US, "PEAK\n%.3f", peakValue));
            rmsText.setText(String.format(Locale.US, "RMS\n%.3f",
                    Math.sqrt(sumSq / Math.max(1L, sampleCount))));
            impactText.setText("IMPACT\n" + impactCount);

            float ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
            String axis = ax >= ay && ax >= az ? "X" : (ay >= az ? "Y" : "Z");
            directionText.setText("MAIN DIRECTION : " + axis + " AXIS");
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override protected void onPause() {
        super.onPause();
        if (running) sensorManager.unregisterListener(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (running && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    static class ShockGraph extends View {
        private final ArrayList<Float> xs = new ArrayList<>();
        private final ArrayList<Float> ys = new ArrayList<>();
        private final ArrayList<Float> zs = new ArrayList<>();
        private final ArrayList<Float> totals = new ArrayList<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double spec = 2.0;

        ShockGraph(android.content.Context context) {
            super(context);
            setBackgroundColor(Color.WHITE);
        }

        private void put(ArrayList<Float> a, float value) {
            a.add(value);
            if (a.size() > 240) a.remove(0);
        }

        void add(float x, float y, float z, double total, double threshold) {
            put(xs, Math.abs(x));
            put(ys, Math.abs(y));
            put(zs, Math.abs(z));
            put(totals, (float) total);
            spec = threshold;
            invalidate();
        }

        void clear() {
            xs.clear(); ys.clear(); zs.clear(); totals.clear();
            invalidate();
        }

        private void drawLine(Canvas canvas, ArrayList<Float> data, int color, float maxY) {
            if (data.size() < 2) return;
            paint.setColor(color);
            paint.setStrokeWidth(color == Color.rgb(110, 75, 190) ? 5f : 3f);
            for (int i = 1; i < data.size(); i++) {
                float x1 = (i - 1) * getWidth() / 239f;
                float x2 = i * getWidth() / 239f;
                float y1 = getHeight() - data.get(i - 1) / maxY * getHeight();
                float y2 = getHeight() - data.get(i) / maxY * getHeight();
                canvas.drawLine(x1, y1, x2, y2, paint);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float maxY = (float) Math.max(3.0, spec * 1.5);
            for (float value : totals) maxY = Math.max(maxY, value * 1.15f);

            paint.setColor(Color.rgb(220, 225, 230));
            paint.setStrokeWidth(1f);
            for (int i = 1; i < 4; i++) {
                canvas.drawLine(0, getHeight() * i / 4f, getWidth(), getHeight() * i / 4f, paint);
            }

            float specY = (float) (getHeight() - spec / maxY * getHeight());
            paint.setColor(Color.RED);
            paint.setStrokeWidth(2f);
            canvas.drawLine(0, specY, getWidth(), specY, paint);

            drawLine(canvas, xs, Color.rgb(0, 130, 220), maxY);
            drawLine(canvas, ys, Color.rgb(0, 170, 110), maxY);
            drawLine(canvas, zs, Color.rgb(230, 145, 20), maxY);
            drawLine(canvas, totals, Color.rgb(110, 75, 190), maxY);
        }
    }
}
