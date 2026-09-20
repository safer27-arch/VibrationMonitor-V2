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
    private Spinner process, unit, mode;
    private LinearLayout manualUnitRow;
    private EditText lineInput, equipmentInput;
    private ShockGraph graph;
    private ImpactTimelineView timeline;
    private TextView segmentInfo, processClock;
    private Button recipeButton, pauseButton;
    private long lastCheckpointMs = 0L;

    private final ArrayList<RecipeUnit> recipe = new ArrayList<>();
    private boolean processPaused = false;
    private long processPauseStartMs = 0L;
    private long totalProcessPausedMs = 0L;
    private long segmentProcessStartMs = 0L;
    private long eventProcessOffsetSec = 0L;

    private java.io.BufferedWriter continuousWriter = null;
    private java.io.File continuousFile = null;
    private int continuousSinceFlush = 0;

    private final ArrayList<UnitSegment> unitSegments = new ArrayList<>();
    private long segmentStartMs = 0L;
    private long segmentCount = 0L;
    private double segmentSumSq = 0.0;
    private double segmentPeak = 0.0;
    private double segmentMaxX = 0.0;
    private double segmentMaxY = 0.0;
    private double segmentMaxZ = 0.0;
    private int segmentImpactStartCount = 0;
    private String segmentProcess = "";
    private String segmentUnit = "";

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
    private int thresholdCandidateCount = 0;

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
    private final ArrayList<EventRecord> sessionEvents = new ArrayList<>();

    private String eventLine = "";
    private String eventEquipment = "";
    private String ep = "";
    private String eu = "";
    private String base = "";
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

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setPadding(dp(10), dp(6), dp(10), dp(6));
        return e;
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        spec = getSharedPreferences("VibrationSettings", MODE_PRIVATE).getFloat("spec", 2f);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(14));
        root.setBackgroundColor(Color.rgb(238, 243, 248));
        sv.addView(root);

        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(14), dp(10), dp(14), dp(10));
        h.setBackground(bg(Color.rgb(15, 38, 61), 18));

        TextView title = tv("PROCESS SHOCK\nPROFILER", 21, Color.WHITE);
        title.setTypeface(null, 1);
        h.addView(title);
        h.addView(tv("FIELD ANALYZER · CONTINUOUS IMPACT BLACKBOX", 12, Color.rgb(185, 207, 225)));

        status = tv("● READY", 15, Color.rgb(80, 220, 150));
        cam = tv("CAMERA : CHECKING...", 13, Color.rgb(185, 207, 225));
        h.addView(status);
        h.addView(cam);
        root.addView(h);

        TextView bb = tv("PRE 3s + IMPACT + POST 3s · PHOTO · GRAPH · CSV · TELEGRAM", 11, Color.rgb(70, 90, 105));
        bb.setGravity(Gravity.CENTER);
        bb.setPadding(dp(4), dp(4), dp(4), dp(5));
        root.addView(bb);

        android.content.SharedPreferences cp = getSharedPreferences("ShockContext", MODE_PRIVATE);

        LinearLayout configCard = new LinearLayout(this);
        configCard.setOrientation(LinearLayout.VERTICAL);
        configCard.setPadding(dp(8), dp(6), dp(8), dp(7));
        configCard.setBackground(bg(Color.WHITE, 12));

        // LINE / EQUIPMENT / PROCESS : one compact row
        LinearLayout basicRow = new LinearLayout(this);
        basicRow.setOrientation(LinearLayout.HORIZONTAL);
        basicRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout lineCell = new LinearLayout(this);
        lineCell.setOrientation(LinearLayout.VERTICAL);
        TextView lineLabel = tv("LINE", 9, Color.rgb(90, 105, 118));
        lineLabel.setPadding(dp(4), dp(1), dp(4), 0);
        lineCell.addView(lineLabel);
        lineInput = input("Line");
        lineInput.setTextSize(13);
        lineInput.setText(cp.getString("line", ""));
        lineCell.addView(lineInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        LinearLayout.LayoutParams lineLp =
                new LinearLayout.LayoutParams(0, dp(58), 0.75f);
        lineLp.setMargins(0, 0, dp(5), 0);
        basicRow.addView(lineCell, lineLp);

        LinearLayout equipmentCell = new LinearLayout(this);
        equipmentCell.setOrientation(LinearLayout.VERTICAL);
        TextView equipmentLabel = tv("EQUIPMENT", 9, Color.rgb(90, 105, 118));
        equipmentLabel.setPadding(dp(4), dp(1), dp(4), 0);
        equipmentCell.addView(equipmentLabel);
        equipmentInput = input("Equip.");
        equipmentInput.setTextSize(13);
        equipmentInput.setText(cp.getString("equipment", ""));
        equipmentCell.addView(equipmentInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        LinearLayout.LayoutParams equipmentLp =
                new LinearLayout.LayoutParams(0, dp(58), 1.0f);
        equipmentLp.setMargins(0, 0, dp(5), 0);
        basicRow.addView(equipmentCell, equipmentLp);

        LinearLayout processCell = new LinearLayout(this);
        processCell.setOrientation(LinearLayout.VERTICAL);
        TextView processLabel = tv("PROCESS", 9, Color.rgb(90, 105, 118));
        processLabel.setPadding(dp(4), dp(1), dp(4), 0);
        processCell.addView(processLabel);
        process = new Spinner(this);
        process.setAdapter(new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"STACK", "PACKAGE", "ACTIVATION"}
        ));
        processCell.addView(process, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        basicRow.addView(
                processCell,
                new LinearLayout.LayoutParams(0, dp(58), 1.20f)
        );

        configCard.addView(basicRow);

        // ANALYSIS MODE : one row
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setPadding(0, dp(2), 0, 0);

        TextView modeLabel = tv("MODE", 10, Color.rgb(90, 105, 118));
        modeLabel.setPadding(dp(4), 0, dp(4), 0);
        modeRow.addView(modeLabel, new LinearLayout.LayoutParams(dp(48), dp(42)));

        mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"AUTO TIMELINE", "MANUAL UNIT"}
        ));
        modeRow.addView(mode, new LinearLayout.LayoutParams(0, dp(42), 1f));

        recipeButton = btn("RECIPE", Color.rgb(67, 88, 108));
        recipeButton.setTextSize(12);
        LinearLayout.LayoutParams recipeLp =
                new LinearLayout.LayoutParams(dp(90), dp(40));
        recipeLp.setMargins(dp(5), 0, 0, 0);
        modeRow.addView(recipeButton, recipeLp);
        configCard.addView(modeRow);

        // Only shown in MANUAL UNIT mode
        manualUnitRow = new LinearLayout(this);
        manualUnitRow.setOrientation(LinearLayout.HORIZONTAL);
        manualUnitRow.setGravity(Gravity.CENTER_VERTICAL);
        manualUnitRow.setPadding(0, dp(2), 0, 0);

        TextView unitLabel = tv("UNIT", 10, Color.rgb(90, 105, 118));
        unitLabel.setPadding(dp(4), 0, dp(4), 0);
        manualUnitRow.addView(
                unitLabel,
                new LinearLayout.LayoutParams(dp(48), dp(42))
        );

        unit = new Spinner(this);
        manualUnitRow.addView(
                unit,
                new LinearLayout.LayoutParams(0, dp(42), 1f)
        );
        configCard.addView(manualUnitRow);

        units(0);
        root.addView(configCard);

        segmentInfo = tv(
                "UNIT SEGMENT : READY",
                12,
                Color.rgb(55, 75, 92)
        );
        segmentInfo.setBackground(bg(Color.WHITE, 10));
        segmentInfo.setPadding(dp(8), dp(5), dp(8), dp(5));
        root.addView(segmentInfo);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setGravity(Gravity.CENTER_VERTICAL);

        processClock = tv(
                "REAL 00:00 · PROCESS 00:00 · READY",
                11,
                Color.rgb(55, 75, 92)
        );
        processClock.setBackground(bg(Color.WHITE, 10));
        processClock.setTextSize(10);
        clockRow.addView(processClock, new LinearLayout.LayoutParams(0, dp(54), 1f));

        pauseButton = btn("PAUSE", Color.rgb(230, 150, 45));
        pauseButton.setTextSize(12);
        LinearLayout.LayoutParams pauseLp =
                new LinearLayout.LayoutParams(dp(112), dp(44));
        pauseLp.setMargins(dp(6), 0, 0, 0);
        clockRow.addView(pauseButton, pauseLp);
        root.addView(clockRow);

        process.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                units(pos);
                loadRecipeForProcess();
                updateModeUi();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                updateModeUi();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        recipeButton.setOnClickListener(v -> showRecipeDialog());
        pauseButton.setOnClickListener(v -> toggleProcessPause());

        loadRecipeForProcess();
        updateModeUi();

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
                dp(245)
        ));

        LinearLayout scaleRow = new LinearLayout(this);
        scaleRow.setOrientation(LinearLayout.HORIZONTAL);
        scaleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView scaleLabel = tv("GRAPH SCALE", 11, Color.rgb(70, 90, 105));
        scaleRow.addView(scaleLabel, new LinearLayout.LayoutParams(0, dp(44), 1.2f));

        String[] scaleNames = {"AUTO", "2", "5", "10", "20"};
        double[] scaleValues = {0.0, 2.0, 5.0, 10.0, 20.0};

        for (int i = 0; i < scaleNames.length; i++) {
            Button sb = new Button(this);
            sb.setText(scaleNames[i]);
            sb.setTextSize(10);
            sb.setAllCaps(false);
            sb.setMinHeight(dp(38));
            sb.setPadding(0, 0, 0, 0);
            final double scaleValue = scaleValues[i];
            sb.setOnClickListener(v -> graph.setScale(scaleValue));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            slp.setMargins(dp(2), dp(2), dp(2), dp(2));
            scaleRow.addView(sb, slp);
        }
        root.addView(scaleRow);

        TextView timelineTitle = tv("PROCESS / IMPACT TIMELINE", 12, Color.rgb(15, 38, 61));
        timelineTitle.setTypeface(null, 1);
        root.addView(timelineTitle);

        timeline = new ImpactTimelineView(this);
        timeline.setRecipe(recipe);
        root.addView(timeline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(96)
        ));

        dir = tv("MAIN DIRECTION : -", 16, Color.rgb(15, 38, 61));
        dir.setGravity(Gravity.CENTER);
        dir.setBackground(bg(Color.WHITE, 12));
        root.addView(dir);

        last = tv(
                "LAST IMPACT : -\n이벤트 발생 시 사진 · 전후 트렌드 · CSV · 요약을 자동 저장합니다.",
                13,
                Color.rgb(70, 90, 105)
        );
        last.setBackground(bg(Color.WHITE, 12));
        root.addView(last);

        Button history = btn("IMPACT EVENT HISTORY", Color.rgb(42, 91, 126));
        LinearLayout.LayoutParams hp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        hp.setMargins(0, dp(9), 0, 0);
        root.addView(history, hp);
        history.setOnClickListener(v -> showHistory());

        Button start = btn("START MONITORING", Color.rgb(0, 145, 105));
        Button stop = btn("STOP & ANALYZE", Color.rgb(190, 55, 55));

        LinearLayout.LayoutParams p1 =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        p1.setMargins(0, dp(9), 0, 0);
        root.addView(start, p1);

        LinearLayout.LayoutParams p2 =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        p2.setMargins(0, dp(9), 0, 0);
        root.addView(stop, p2);

        start.setOnClickListener(v -> startMon());
        stop.setOnClickListener(v -> stopMon());

        // Safe area for Android edge-to-edge system bars.
        sv.setClipToPadding(false);
        sv.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset = insets.getSystemWindowInsetTop();
            int bottomInset = insets.getSystemWindowInsetBottom();
            v.setPadding(0, topInset, 0, bottomInset);
            return insets;
        });

        setContentView(sv);
        sv.requestApplyInsets();
        setupCamera();
    }

    private void units(int p) {
        String[][] u = {
                {"Stack Transfer", "Alignment", "Stack Press", "Pick / Place"},
                {"Cell Transfer", "Sealing", "Gripper Pick / Place", "Conveyor Stop / Start"},
                {"Tray Loading", "Tray Unloading", "Conveyor", "Lift / Transfer"}
        };

        String old = unit.getSelectedItem() == null ? "" : String.valueOf(unit.getSelectedItem());

        unit.setAdapter(new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                u[Math.max(0, Math.min(2, p))]
        ));

        for (int i = 0; i < unit.getCount(); i++) {
            if (old.equals(String.valueOf(unit.getItemAtPosition(i)))) {
                unit.setSelection(i);
                break;
            }
        }
    }

    private boolean isAutoMode() {
        return mode != null && mode.getSelectedItemPosition() == 0;
    }

    private String recipeKey() {
        String p = process == null || process.getSelectedItem() == null
                ? "STACK"
                : String.valueOf(process.getSelectedItem());
        return "recipe_" + p;
    }

    private String defaultRecipeText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            if (i > 1) sb.append("\n");
            sb.append("U").append(i).append(",10");
        }
        return sb.toString();
    }

    private String recipeToText() {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < recipe.size(); i++) {
            if (i > 0) sb.append("\n");
            RecipeUnit r = recipe.get(i);
            sb.append(r.name)
                    .append(",")
                    .append(String.format(Locale.US, "%.1f", r.seconds));
        }

        return sb.toString();
    }

    private ArrayList<RecipeUnit> parseRecipe(String text) {
        ArrayList<RecipeUnit> out = new ArrayList<>();
        if (text == null) return out;

        String[] lines = text.split("\\r?\\n");

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            String[] p = line.split(",");
            if (p.length < 2) continue;

            String name = p[0].trim();

            try {
                double sec = Double.parseDouble(p[1].trim());

                if (!name.isEmpty() && sec > 0.05) {
                    RecipeUnit r = new RecipeUnit();
                    r.name = name;
                    r.seconds = sec;
                    out.add(r);
                }
            } catch (Exception ignored) {}
        }

        return out;
    }

    private void loadRecipeForProcess() {
        if (process == null) return;

        String text = getSharedPreferences("ShockRecipe", MODE_PRIVATE)
                .getString(recipeKey(), defaultRecipeText());

        ArrayList<RecipeUnit> parsed = parseRecipe(text);

        recipe.clear();

        if (parsed.isEmpty()) {
            parsed = parseRecipe(defaultRecipeText());
        }

        recipe.addAll(parsed);

        if (timeline != null) {
            timeline.setRecipe(recipe);
        }
    }

    private void showRecipeDialog() {
        if (running) {
            Toast.makeText(
                    this,
                    "측정 중에는 Recipe를 변경할 수 없습니다.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        final EditText e = new EditText(this);
        e.setText(recipeToText());
        e.setTextSize(14);
        e.setMinLines(10);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );

        new android.app.AlertDialog.Builder(this)
                .setTitle("AUTO TIMELINE RECIPE")
                .setMessage(
                        "한 줄에 Unit 이름, 기준시간(초)\n"
                                + "예: Loader,8.5\nTransfer,10\nPress,12\n\n"
                                + "설비가 멈추면 PROCESS PAUSE를 누르면\n"
                                + "진동 측정은 계속하고 공정시간만 멈춥니다."
                )
                .setView(e)
                .setPositiveButton("저장", (d, w) -> {
                    ArrayList<RecipeUnit> parsed =
                            parseRecipe(e.getText().toString());

                    if (parsed.isEmpty()) {
                        Toast.makeText(
                                this,
                                "Recipe 형식을 확인해주세요.",
                                Toast.LENGTH_LONG
                        ).show();
                        return;
                    }

                    recipe.clear();
                    recipe.addAll(parsed);

                    getSharedPreferences("ShockRecipe", MODE_PRIVATE)
                            .edit()
                            .putString(recipeKey(), recipeToText())
                            .apply();

                    Toast.makeText(
                            this,
                            "Recipe " + recipe.size() + "개 Unit 저장 완료",
                            Toast.LENGTH_SHORT
                    ).show();

                    updateModeUi();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private double recipeTotalSec() {
        double total = 0.0;
        for (RecipeUnit r : recipe) total += r.seconds;
        return total;
    }

    private long getProcessElapsedMs(long now) {
        if (startMs <= 0L) return 0L;

        long paused = totalProcessPausedMs;

        if (processPaused && processPauseStartMs > 0L) {
            paused += Math.max(0L, now - processPauseStartMs);
        }

        return Math.max(0L, now - startMs - paused);
    }

    private int activeRecipeIndex(long processMs) {
        if (recipe.isEmpty()) return -1;

        double sec = processMs / 1000.0;
        double end = 0.0;

        for (int i = 0; i < recipe.size(); i++) {
            end += recipe.get(i).seconds;
            if (sec < end) return i;
        }

        return -1;
    }

    private double recipeUnitStartSec(int index) {
        double sec = 0.0;

        for (int i = 0; i < recipe.size() && i < index; i++) {
            sec += recipe.get(i).seconds;
        }

        return sec;
    }

    private double recipeUnitProgressSec(long now) {
        if (!isAutoMode()) return 0.0;

        long processMs = getProcessElapsedMs(now);
        int idx = activeRecipeIndex(processMs);

        if (idx < 0 || idx >= recipe.size()) return 0.0;

        double start = recipeUnitStartSec(idx);
        return Math.max(0.0, processMs / 1000.0 - start);
    }

    private double recipeUnitDurationSec(long now) {
        if (!isAutoMode()) return 0.0;

        int idx = activeRecipeIndex(getProcessElapsedMs(now));

        if (idx < 0 || idx >= recipe.size()) return 0.0;

        return recipe.get(idx).seconds;
    }

    private String currentTaggedUnit(long now) {
        if (!isAutoMode()) {
            return String.valueOf(unit.getSelectedItem());
        }

        if (processPaused) {
            return "LINE STOP";
        }

        int idx = activeRecipeIndex(getProcessElapsedMs(now));

        if (idx >= 0 && idx < recipe.size()) {
            return recipe.get(idx).name;
        }

        return "AFTER RECIPE";
    }

    private void updateModeUi() {
        if (mode == null
                || unit == null
                || recipeButton == null
                || pauseButton == null
                || segmentInfo == null) {
            return;
        }

        boolean auto = isAutoMode();

        unit.setEnabled(!auto);
        if (manualUnitRow != null) {
            manualUnitRow.setVisibility(auto ? View.GONE : View.VISIBLE);
        }
        recipeButton.setEnabled(!running && auto);
        recipeButton.setVisibility(auto ? View.VISIBLE : View.GONE);
        pauseButton.setEnabled(running && auto && !calibrating);

        if (timeline != null) {
            timeline.setRecipe(auto ? recipe : new ArrayList<RecipeUnit>());
        }

        if (!running) {
            pauseButton.setText("PAUSE");

            segmentInfo.setText(
                    auto
                            ? "AUTO TIMELINE · "
                                + recipe.size()
                                + " Units · "
                                + String.format(Locale.US, "%.1fs", recipeTotalSec())
                            : "MANUAL UNIT · READY"
            );
        }
    }

    private void toggleProcessPause() {
        if (!running || !isAutoMode() || calibrating) return;

        long now = SystemClock.elapsedRealtime();

        if (!processPaused) {
            processPaused = true;
            processPauseStartMs = now;
            pauseButton.setText("RESUME");
            status.setText("● LINE STOP / PROCESS TIME PAUSED");
            status.setTextColor(Color.rgb(255, 185, 70));
        } else {
            totalProcessPausedMs += Math.max(0L, now - processPauseStartMs);
            processPauseStartMs = 0L;
            processPaused = false;
            pauseButton.setText("PAUSE");
            status.setText("● MONITORING · AUTO TIMELINE");
            status.setTextColor(Color.rgb(80, 220, 150));
        }
    }

    private void openContinuousCsv() {
        closeContinuousCsv();

        try {
            String stamp = new java.text.SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    Locale.US
            ).format(new java.util.Date());

            continuousFile = new java.io.File(
                    eventDir(),
                    "FULL_RUN_" + stamp + ".csv"
            );

            continuousWriter = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(continuousFile),
                            java.nio.charset.StandardCharsets.UTF_8
                    ),
                    64 * 1024
            );

            continuousWriter.write(
                    "DateTime,RealElapsedMs,ProcessElapsedMs,ProcessPaused,"
                            + "Line,Equipment,Process,UnitAction,X,Y,Z,Total,Spec\n"
            );

            continuousSinceFlush = 0;

        } catch (Exception e) {
            continuousWriter = null;
            continuousFile = null;
        }
    }

    private void writeContinuousSample(
            P p,
            String processName,
            String unitName,
            long now
    ) {
        if (continuousWriter == null || startMs <= 0L) return;

        try {
            String ts = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.US
            ).format(new java.util.Date(p.wall));

            continuousWriter.write(String.format(
                    Locale.US,
                    "%s,%d,%d,%s,%s,%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                    ts,
                    Math.max(0L, now - startMs),
                    getProcessElapsedMs(now),
                    processPaused ? "Y" : "N",
                    safe(lineInput.getText().toString().trim()),
                    safe(equipmentInput.getText().toString().trim()),
                    safe(processName),
                    safe(unitName),
                    p.x,
                    p.y,
                    p.z,
                    p.t,
                    spec
            ));

            continuousSinceFlush++;

            if (continuousSinceFlush >= 100) {
                continuousWriter.flush();
                continuousSinceFlush = 0;
            }

        } catch (Exception ignored) {}
    }

    private void closeContinuousCsv() {
        if (continuousWriter != null) {
            try {
                continuousWriter.flush();
                continuousWriter.close();
            } catch (Exception ignored) {}
        }

        continuousWriter = null;
        continuousSinceFlush = 0;
    }

    private void startMon() {
        if (acc == null) {
            Toast.makeText(this, "가속도 센서가 없습니다.", Toast.LENGTH_LONG).show();
            return;
        }

        getSharedPreferences("ShockContext", MODE_PRIVATE).edit()
                .putString("line", lineInput.getText().toString().trim())
                .putString("equipment", equipmentInput.getText().toString().trim())
                .apply();

        if (isAutoMode()) {
            loadRecipeForProcess();

            if (recipe.isEmpty()) {
                Toast.makeText(this, "AUTO TIMELINE Recipe가 없습니다.", Toast.LENGTH_LONG).show();
                return;
            }
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
        thresholdCandidateCount = 0;

        pre.clear();
        event.clear();
        sessionEvents.clear();
        unitSegments.clear();
        graph.clear();
        timeline.clear();
        lastCheckpointMs = 0L;
        segmentStartMs = 0L;
        segmentCount = 0L;
        segmentSumSq = 0.0;
        segmentPeak = 0.0;
        segmentMaxX = 0.0;
        segmentMaxY = 0.0;
        segmentMaxZ = 0.0;
        segmentImpactStartCount = 0;
        segmentProcess = "";
        segmentUnit = "";

        elapsed.setText("CALIBRATING 2.0s");
        total.setText("0.000 m/s²");
        peak.setText("PEAK\n0.000");
        rms.setText("RMS\n0.000");
        impact.setText("IMPACT\n0");
        dir.setText("MAIN DIRECTION : -");
        segmentInfo.setText("UNIT SEGMENT : CALIBRATING");
        last.setText("LAST IMPACT : -\n측정 중 공정/UNIT을 변경하면 구간별 Peak/RMS/Impact를 자동 비교합니다.");

        status.setText("● SENSOR CALIBRATION");
        status.setTextColor(Color.rgb(255, 185, 70));

        lineInput.setEnabled(false);
        equipmentInput.setEnabled(false);
        process.setEnabled(false);
        mode.setEnabled(false);
        recipeButton.setEnabled(false);
        unit.setEnabled(!isAutoMode());
        pauseButton.setEnabled(false);
        pauseButton.setText("PAUSE");
        processClock.setText("REAL 00:00 · PROCESS 00:00 · CALIBRATING");

        if (!cameraReady) setupCamera();

        startKeepAliveService();

        sm.unregisterListener(this);
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
    }

    private void stopMon() {
        if (!running) return;

        if (eventOn) finishEvent();
        finalizeUnitSegment(SystemClock.elapsedRealtime());

        running = false;
        calibrating = false;
        sm.unregisterListener(this);
        stopService(new android.content.Intent(this, ProcessShockKeepAliveService.class));

        status.setText("● STOPPED / ANALYSIS READY");
        status.setTextColor(Color.rgb(255, 185, 70));

        lineInput.setEnabled(true);
        equipmentInput.setEnabled(true);
        process.setEnabled(true);
        mode.setEnabled(true);
        recipeButton.setEnabled(isAutoMode());
        unit.setEnabled(!isAutoMode());
        pauseButton.setEnabled(false);
        pauseButton.setText("PAUSE");

        saveSessionCsv();
        saveUnitSummaryCsv();
        clearActiveCheckpoint();
        showSessionSummary();
    }

    private void startKeepAliveService() {
        android.content.Intent i = new android.content.Intent(this, ProcessShockKeepAliveService.class);

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Background monitor 시작 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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
                status.setText(
                        isAutoMode()
                                ? "● MONITORING · AUTO TIMELINE"
                                : "● MONITORING · MANUAL UNIT"
                );
                status.setTextColor(Color.rgb(80, 220, 150));
                pauseButton.setEnabled(isAutoMode());
                startUnitSegment(now);
                openContinuousCsv();

                Toast.makeText(this, "센서 안정화 완료 · 연속 공정 감시 시작", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        String selectedProcess = String.valueOf(process.getSelectedItem());
        String selectedUnit = currentTaggedUnit(now);

        if (segmentStartMs <= 0L) {
            startUnitSegment(now);
        } else if (!selectedProcess.equals(segmentProcess)
                || !selectedUnit.equals(segmentUnit)) {
            finalizeUnitSegment(now);
            startUnitSegment(now);
        }

        if (!isAutoMode() || !processPaused) {
            segmentCount++;
            segmentSumSq += t * t;
            segmentPeak = Math.max(segmentPeak, t);
            segmentMaxX = Math.max(segmentMaxX, Math.abs(x));
            segmentMaxY = Math.max(segmentMaxY, Math.abs(y));
            segmentMaxZ = Math.max(segmentMaxZ, Math.abs(z));
        }

        P p = new P(System.currentTimeMillis(), now, x, y, z, t);
        writeContinuousSample(p, selectedProcess, selectedUnit, now);

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

        if (!eventOn) {
            if (t >= spec * 1.5) {
                thresholdCandidateCount = 2;
            } else if (t >= spec) {
                thresholdCandidateCount++;
            } else if (t < spec * 0.8) {
                thresholdCandidateCount = 0;
            }

            if (thresholdCandidateCount >= 2 && now - lastClosed >= 1000L) {
                thresholdCandidateCount = 0;
                beginEvent(now);
            }
        }

        if (eventOn) {
            if (event.isEmpty() || event.get(event.size() - 1).mono != p.mono) {
                event.add(p);
            }

            if (t >= spec * 0.8) {
                lastAbove = now;
            }

            if (now - lastAbove >= 3000L) {
                finishEvent();
            }
        }

        graph.add(x, y, z, t, spec);

        if (startMs > 0L) {
            timeline.setDuration(
                    isAutoMode()
                            ? Math.max(1L, Math.round(recipeTotalSec()))
                            : Math.max(1L, (now - startMs) / 1000L)
            );

            timeline.setProcessPosition(
                    isAutoMode()
                            ? getProcessElapsedMs(now) / 1000.0
                            : Math.max(0.0, (now - startMs) / 1000.0),
                    processPaused
            );
        }

        if (now - lastCheckpointMs >= 15000L) {
            lastCheckpointMs = now;
            saveActiveCheckpoint();
        }

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
            rms.setText(String.format(Locale.US, "RMS\n%.3f", Math.sqrt(sumSq / Math.max(1L, n))));
            impact.setText("IMPACT\n" + impactCount);

            long processSec = getProcessElapsedMs(now) / 1000L;

            if (isAutoMode()) {
                double unitElapsed = recipeUnitProgressSec(now);
                double unitDuration = recipeUnitDurationSec(now);
                double unitPct = unitDuration > 0.0
                        ? Math.min(100.0, unitElapsed / unitDuration * 100.0)
                        : 0.0;

                processClock.setText(String.format(
                        Locale.US,
                        "REAL %02d:%02d · PROCESS %02d:%02d\n%s · %.1f / %.1fs · %.0f%% %s",
                        sec / 60,
                        sec % 60,
                        processSec / 60,
                        processSec % 60,
                        selectedUnit,
                        unitElapsed,
                        unitDuration,
                        unitPct,
                        processPaused ? "· PAUSED" : ""
                ));
            } else {
                processClock.setText(String.format(
                        Locale.US,
                        "REAL %02d:%02d · MANUAL · %s",
                        sec / 60,
                        sec % 60,
                        selectedUnit
                ));
            }

            long segmentSec = segmentStartMs > 0L
                    ? Math.max(
                            0L,
                            (
                                    isAutoMode()
                                            ? getProcessElapsedMs(now) - segmentProcessStartMs
                                            : now - segmentStartMs
                            ) / 1000L
                    )
                    : 0L;
            double segmentRms = segmentCount > 0L
                    ? Math.sqrt(segmentSumSq / segmentCount)
                    : 0.0;
            int segmentImpacts = Math.max(0, impactCount - segmentImpactStartCount);

            segmentInfo.setText(String.format(
                    Locale.US,
                    "UNIT SEGMENT · %s > %s\n%02d:%02d · Peak %.3f · RMS %.3f · Impact %d",
                    blank(segmentProcess),
                    blank(segmentUnit),
                    segmentSec / 60,
                    segmentSec % 60,
                    segmentPeak,
                    segmentRms,
                    segmentImpacts
            ));

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

        eventLine = lineInput.getText().toString().trim();
        eventEquipment = equipmentInput.getText().toString().trim();
        ep = String.valueOf(process.getSelectedItem());
        eu = currentTaggedUnit(now);
        eventProcessOffsetSec = isAutoMode()
                ? getProcessElapsedMs(now) / 1000L
                : Math.max(0L, (now - startMs) / 1000L);

        base = String.format(
                Locale.US,
                "IMPACT_%03d_%s",
                impactCount,
                new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new java.util.Date())
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
        final String ln = eventLine;
        final String eq = eventEquipment;
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

        EventRecord record = new EventRecord();
        record.no = no;
        record.timeMs = System.currentTimeMillis();
        record.line = ln;
        record.equipment = eq;
        record.process = pp;
        record.unit = uu;
        record.peak = st.pk;
        record.rms = st.rms;
        record.duration = st.dur;
        record.axis = st.axis;
        record.mx = st.mx;
        record.my = st.my;
        record.mz = st.mz;
        record.base = bn;
        record.offsetSec = eventProcessOffsetSec;
        sessionEvents.add(record);

        long timelineDuration = isAutoMode()
                ? Math.max(1L, Math.round(recipeTotalSec()))
                : (
                        startMs > 0L
                                ? Math.max(1L, (SystemClock.elapsedRealtime() - startMs) / 1000L)
                                : 1L
                );
        timeline.refresh(sessionEvents, timelineDuration);
        saveActiveCheckpoint();

        last.setText(String.format(
                Locale.US,
                "LAST IMPACT #%03d · %s > %s\nPEAK %.3f | RMS %.3f | %.2fs | %s AXIS\n사진 · 그래프 · CSV · 요약 저장 중...",
                no, pp, uu, st.pk, st.rms, st.dur, st.axis
        ));

        if (running && !calibrating) {
            status.setText("● MONITORING · BACKGROUND READY");
            status.setTextColor(Color.rgb(80, 220, 150));
        }

        new Thread(() -> saveEvent(no, ln, eq, pp, uu, bn, ph, d, st)).start();
    }

    private Stats stats(ArrayList<P> d, long es, long la) {
        Stats s = new Stats();
        double q = 0.0;

        for (P p : d) {
            s.pk = Math.max(s.pk, p.t);
            q += p.t * p.t;
            s.mx = Math.max(s.mx, Math.abs(p.x));
            s.my = Math.max(s.my, Math.abs(p.y));
            s.mz = Math.max(s.mz, Math.abs(p.z));
        }

        s.rms = d.isEmpty() ? 0.0 : Math.sqrt(q / d.size());
        s.dur = Math.max(0.0, (la - es) / 1000.0);
        s.axis = s.mx >= s.my && s.mx >= s.mz ? "X" : (s.my >= s.mz ? "Y" : "Z");
        return s;
    }

    private void saveEvent(
            int no,
            String ln,
            String eq,
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

        writeCsv(csv, ln, eq, pp, uu, d);
        writeGraph(png, d);
        writeSummary(txt, no, ln, eq, pp, uu, ph, png, csv, st);

        android.content.SharedPreferences sp = getSharedPreferences("TelegramSettings", MODE_PRIVATE);
        String token = sp.getString("bot_token", "");
        String ids = sp.getString("chat_id", "");

        String cap = String.format(
                Locale.US,
                "⚡ PROCESS SHOCK IMPACT #%03d\n"
                        + "Line : %s\nEquipment : %s\nProcess : %s\nUnit / Action : %s\n"
                        + "PEAK : %.3f m/s²\nRMS : %.3f m/s²\nDuration : %.2f s\n"
                        + "Main Direction : %s\nX/Y/Z Peak : %.3f / %.3f / %.3f m/s²\n"
                        + "SPEC : %.3f m/s²\nAttached : photo + trend graph + CSV",
                no, blank(ln), blank(eq), pp, uu,
                st.pk, st.rms, st.dur, st.axis, st.mx, st.my, st.mz, spec
        );

        if (token != null && ids != null && !token.trim().isEmpty() && !ids.trim().isEmpty()) {
            TelegramSender.sendEventBundleMulti(
                    token.trim(),
                    ids.trim(),
                    cap,
                    ph,
                    png,
                    csv,
                    (ok, msg) -> runOnUiThread(() ->
                            last.append(ok ? "\nTelegram : SENT" : "\nTelegram : FAILED (" + msg + ")")
                    )
            );
        } else {
            runOnUiThread(() -> last.append("\nTelegram : 설정 없음 · Local 저장 완료"));
        }

        runOnUiThread(() ->
                Toast.makeText(this, "Impact #" + no + " 블랙박스 저장 완료", Toast.LENGTH_SHORT).show()
        );
    }

    private String blank(String s) {
        return s == null || s.trim().isEmpty() ? "-" : s.trim();
    }

    private java.io.File eventDir() {
        java.io.File d = new java.io.File(getExternalFilesDir(null), "ShockEvents");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private String safe(String s) {
        return s == null ? "" : s.replace(",", " ").replace("\n", " ").replace("\r", " ");
    }

    private void writeCsv(
            java.io.File f,
            String ln,
            String eq,
            String pp,
            String uu,
            ArrayList<P> d
    ) {
        try (java.io.PrintWriter o = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(f),
                        java.nio.charset.StandardCharsets.UTF_8
                )
        )) {
            o.println("DateTime,ElapsedMs,Line,Equipment,Process,UnitAction,X,Y,Z,Total,Spec");
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

            long zero = d.isEmpty() ? 0L : d.get(0).mono;

            for (P p : d) {
                o.println(String.format(
                        Locale.US,
                        "%s,%d,%s,%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f",
                        fmt.format(new java.util.Date(p.wall)),
                        p.mono - zero,
                        safe(ln), safe(eq), safe(pp), safe(uu),
                        p.x, p.y, p.z, p.t, spec
                ));
            }
        } catch (Exception ignored) {}
    }

    private void writeSummary(
            java.io.File f,
            int no,
            String ln,
            String eq,
            String pp,
            String uu,
            java.io.File ph,
            java.io.File png,
            java.io.File csv,
            Stats s
    ) {
        try (java.io.PrintWriter o = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(f),
                        java.nio.charset.StandardCharsets.UTF_8
                )
        )) {
            o.println("PROCESS SHOCK EVENT #" + String.format(Locale.US, "%03d", no));
            o.println("Time : " + new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.US
            ).format(new java.util.Date()));
            o.println("Line : " + blank(ln));
            o.println("Equipment : " + blank(eq));
            o.println("Process : " + pp);
            o.println("Unit / Action : " + uu);
            o.println(String.format(
                    Locale.US,
                    "Peak : %.3f m/s²\nRMS : %.3f m/s²\nImpact Duration : %.2f sec\n"
                            + "Main Direction : %s AXIS\nX / Y / Z Peak : %.3f / %.3f / %.3f m/s²\nSPEC : %.3f m/s²",
                    s.pk, s.rms, s.dur, s.axis, s.mx, s.my, s.mz, spec
            ));
            o.println("Photo : " + (ph == null ? "-" : ph.getName()));
            o.println("Graph : " + png.getName());
            o.println("CSV : " + csv.getName());
            o.println("Window : PRE 3 sec + IMPACT + POST 3 sec");
        } catch (Exception ignored) {}
    }

    private void writeGraph(java.io.File f, ArrayList<P> d) {
        if (d.size() < 2) return;

        try {
            int W = 1200, H = 700, L = 90, R = 1160, T = 60, B = 610;

            Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
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
                        Math.max(p.t, Math.max(Math.abs(p.x), Math.max(Math.abs(p.y), Math.abs(p.z)))) * 1.15
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
                double[] vv = {Math.abs(p.x), Math.abs(p.y), Math.abs(p.z), p.t};
                float x = L + (R - L) * (i / (float) (d.size() - 1));

                for (int k = 0; k < 4; k++) {
                    float y = B - (float) (vv[k] / m * (B - T));
                    if (i == 0) path[k].moveTo(x, y);
                    else path[k].lineTo(x, y);
                }
            }

            for (int k = 0; k < 4; k++) c.drawPath(path[k], ps[k]);

            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                bm.compress(Bitmap.CompressFormat.PNG, 100, o);
            }

            bm.recycle();
        } catch (Exception ignored) {}
    }

    private void startUnitSegment(long now) {
        segmentStartMs = now;
        segmentCount = 0L;
        segmentSumSq = 0.0;
        segmentPeak = 0.0;
        segmentMaxX = 0.0;
        segmentMaxY = 0.0;
        segmentMaxZ = 0.0;
        segmentImpactStartCount = impactCount;
        segmentProcess = String.valueOf(process.getSelectedItem());
        segmentUnit = currentTaggedUnit(now);
        segmentProcessStartMs = isAutoMode()
                ? getProcessElapsedMs(now)
                : now;

        if (segmentInfo != null) {
            segmentInfo.setText(
                    "UNIT SEGMENT · "
                            + blank(segmentProcess)
                            + " > "
                            + blank(segmentUnit)
            );
        }
    }

    private void finalizeUnitSegment(long now) {
        if (segmentStartMs <= 0L || segmentCount <= 0L) {
            segmentStartMs = 0L;
            return;
        }

        UnitSegment r = new UnitSegment();
        r.process = segmentProcess;
        r.unit = segmentUnit;
        long segmentElapsedMs = isAutoMode()
                ? Math.max(0L, getProcessElapsedMs(now) - segmentProcessStartMs)
                : Math.max(0L, now - segmentStartMs);
        r.durationSec = segmentElapsedMs / 1000.0;
        r.peak = segmentPeak;
        r.rms = Math.sqrt(segmentSumSq / Math.max(1L, segmentCount));
        r.impactCount = Math.max(0, impactCount - segmentImpactStartCount);
        r.mx = segmentMaxX;
        r.my = segmentMaxY;
        r.mz = segmentMaxZ;
        r.axis = r.mx >= r.my && r.mx >= r.mz
                ? "X"
                : (r.my >= r.mz ? "Y" : "Z");

        if (r.durationSec >= 0.10) {
            unitSegments.add(r);
        }

        segmentStartMs = 0L;
        segmentCount = 0L;
        segmentSumSq = 0.0;
        segmentPeak = 0.0;
        segmentMaxX = 0.0;
        segmentMaxY = 0.0;
        segmentMaxZ = 0.0;
    }

    private void saveUnitSummaryCsv() {
        if (unitSegments.isEmpty()) return;

        String stamp = new java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
        ).format(new java.util.Date());

        java.io.File f = new java.io.File(
                eventDir(),
                "UNIT_SUMMARY_" + stamp + ".csv"
        );

        try (java.io.PrintWriter o = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(f),
                        java.nio.charset.StandardCharsets.UTF_8
                )
        )) {
            o.println(
                    "Segment,Process,UnitAction,DurationSec,Peak,RMS,ImpactCount,Axis,XPeak,YPeak,ZPeak"
            );

            int no = 1;

            for (UnitSegment r : unitSegments) {
                o.println(String.format(
                        Locale.US,
                        "%d,%s,%s,%.3f,%.6f,%.6f,%d,%s,%.6f,%.6f,%.6f",
                        no++,
                        safe(r.process),
                        safe(r.unit),
                        r.durationSec,
                        r.peak,
                        r.rms,
                        r.impactCount,
                        r.axis,
                        r.mx,
                        r.my,
                        r.mz
                ));
            }
        } catch (Exception ignored) {}
    }

    private void saveSessionCsv() {
        if (sessionEvents.isEmpty()) return;

        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new java.util.Date());

        java.io.File f = new java.io.File(eventDir(), "SESSION_" + stamp + ".csv");

        try (java.io.PrintWriter o = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(f),
                        java.nio.charset.StandardCharsets.UTF_8
                )
        )) {
            o.println("Event,Time,Line,Equipment,Process,UnitAction,Peak,RMS,DurationSec,Axis,XPeak,YPeak,ZPeak,Spec");

            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

            for (EventRecord r : sessionEvents) {
                o.println(String.format(
                        Locale.US,
                        "%d,%s,%s,%s,%s,%s,%.6f,%.6f,%.3f,%s,%.6f,%.6f,%.6f,%.6f",
                        r.no,
                        fmt.format(new java.util.Date(r.timeMs)),
                        safe(r.line), safe(r.equipment), safe(r.process), safe(r.unit),
                        r.peak, r.rms, r.duration, r.axis, r.mx, r.my, r.mz, spec
                ));
            }
        } catch (Exception ignored) {}
    }

    private void saveActiveCheckpoint() {
        if (!running || startMs <= 0L) return;

        java.io.File f = new java.io.File(eventDir(), "SESSION_ACTIVE.csv");

        try (java.io.PrintWriter o = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(f),
                        java.nio.charset.StandardCharsets.UTF_8
                )
        )) {
            long now = SystemClock.elapsedRealtime();
            long durationSec = Math.max(0L, (now - startMs) / 1000L);
            double sessionRms = n > 0L ? Math.sqrt(sumSq / n) : 0.0;

            o.println("Status,DurationSec,ImpactCount,SessionPeak,SessionRMS,Spec");
            o.println(String.format(
                    Locale.US,
                    "ACTIVE,%d,%d,%.6f,%.6f,%.6f",
                    durationSec,
                    impactCount,
                    sessionPeak,
                    sessionRms,
                    spec
            ));

            o.println();
            o.println("Event,OffsetSec,Line,Equipment,Process,UnitAction,Peak,RMS,DurationSec,Axis");

            for (EventRecord r : sessionEvents) {
                o.println(String.format(
                        Locale.US,
                        "%d,%d,%s,%s,%s,%s,%.6f,%.6f,%.3f,%s",
                        r.no,
                        r.offsetSec,
                        safe(r.line),
                        safe(r.equipment),
                        safe(r.process),
                        safe(r.unit),
                        r.peak,
                        r.rms,
                        r.duration,
                        r.axis
                ));
            }
        } catch (Exception ignored) {}
    }

    private void clearActiveCheckpoint() {
        try {
            java.io.File f = new java.io.File(eventDir(), "SESSION_ACTIVE.csv");
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}
    }

    private ArrayList<UnitAggregate> buildUnitAggregates() {
        LinkedHashMap<String, UnitAggregate> map = new LinkedHashMap<>();

        for (UnitSegment r : unitSegments) {
            String key = blank(r.process) + " > " + blank(r.unit);
            UnitAggregate a = map.get(key);

            if (a == null) {
                a = new UnitAggregate();
                a.label = key;
                map.put(key, a);
            }

            a.count += r.impactCount;
            a.maxPeak = Math.max(a.maxPeak, r.peak);
            a.rmsWeighted += r.rms * Math.max(0.001, r.durationSec);
            a.durationSec += r.durationSec;
            a.segmentCount++;
        }

        return new ArrayList<>(map.values());
    }

    private void showSessionSummary() {
        long now = SystemClock.elapsedRealtime();
        long durationSec = startMs > 0L ? Math.max(0L, (now - startMs) / 1000L) : 0L;
        double sessionRms = n > 0L ? Math.sqrt(sumSq / n) : 0.0;

        String mainAxis =
                sessionMaxX >= sessionMaxY && sessionMaxX >= sessionMaxZ
                        ? "X"
                        : (sessionMaxY >= sessionMaxZ ? "Y" : "Z");

        EventRecord top = null;
        for (EventRecord r : sessionEvents) {
            if (top == null || r.peak > top.peak) top = r;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));

        TextView topCard = tv(
                top == null
                        ? "TOP IMPACT : 없음"
                        : String.format(
                                Locale.US,
                                "TOP IMPACT #%03d\n%s > %s\nPEAK %.3f m/s² · %s AXIS",
                                top.no, top.process, top.unit, top.peak, top.axis
                        ),
                16,
                Color.rgb(15, 38, 61)
        );
        topCard.setTypeface(null, 1);
        topCard.setBackground(bg(Color.rgb(238, 243, 248), 12));
        box.addView(topCard);

        String summary = String.format(
                Locale.US,
                "측정시간  %02d:%02d:%02d\n"
                        + "Impact  %d회\n"
                        + "최대 Peak  %.3f m/s²\n"
                        + "Session RMS  %.3f m/s²\n"
                        + "주 충격 방향  %s AXIS\n"
                        + "X / Y / Z Peak  %.3f / %.3f / %.3f m/s²\n"
                        + "SPEC  %.3f m/s²",
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

        box.addView(tv(summary, 15, Color.DKGRAY));

        if (!sessionEvents.isEmpty()) {
            TextView chartTitle = tv("EVENT PEAK COMPARISON", 13, Color.rgb(15, 38, 61));
            chartTitle.setTypeface(null, 1);
            box.addView(chartTitle);
            int eventChartHeight = Math.max(
                    92,
                    Math.min(245, sessionEvents.size() * 58 + 28)
            );
            box.addView(
                    new SessionBarsView(this, sessionEvents),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(eventChartHeight)
                    )
            );
        }

        if (!unitSegments.isEmpty()) {
            ArrayList<UnitAggregate> aggregates = buildUnitAggregates();
            TextView unitTitle = tv("UNIT SUMMARY · PEAK / RMS / IMPACT / TIME", 13, Color.rgb(15, 38, 61));
            unitTitle.setTypeface(null, 1);
            box.addView(unitTitle);
            int unitChartHeight = Math.max(
                    98,
                    Math.min(360, aggregates.size() * 72 + 24)
            );
            box.addView(
                    new UnitSummaryView(this, aggregates),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(unitChartHeight)
                    )
            );
        }

        TextView scrollHint = tv(
                "↑ ↓  스크롤하여 전체 Unit 비교",
                11,
                Color.rgb(120, 130, 140)
        );
        scrollHint.setGravity(Gravity.CENTER);
        scrollHint.setPadding(dp(4), dp(8), dp(4), dp(4));
        box.addView(scrollHint);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(false);
        sv.addView(box);

        new android.app.AlertDialog.Builder(this)
                .setTitle("SESSION SUMMARY")
                .setView(sv)
                .setPositiveButton("확인", null)
                .show();
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

        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        String[] names = new String[files.length];

        for (int i = 0; i < files.length; i++) {
            names[i] = historyLabel(files[i]);
        }

        final java.io.File[] list = files;

        new android.app.AlertDialog.Builder(this)
                .setTitle("IMPACT EVENT HISTORY")
                .setItems(names, (dialog, which) -> showDetailedHistory(list[which]))
                .setNegativeButton("닫기", null)
                .show();
    }

    private String historyLabel(java.io.File file) {
        String processName = "-";
        String unitName = "-";
        String peakValue = "-";

        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        new java.io.FileInputStream(file),
                        java.nio.charset.StandardCharsets.UTF_8
                )
        )) {
            String line;

            while ((line = br.readLine()) != null) {
                if (line.startsWith("Process : ")) processName = line.substring(10).trim();
                else if (line.startsWith("Unit / Action : ")) unitName = line.substring(16).trim();
                else if (line.startsWith("Peak : ")) peakValue = line.substring(7).trim();
            }
        } catch (Exception ignored) {}

        String time = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                .format(new java.util.Date(file.lastModified()));

        return time + " · " + processName + " · " + unitName + "\nPeak " + peakValue;
    }

    private void showDetailedHistory(java.io.File summaryFile) {
        String name = summaryFile.getName();
        String baseName = name.replace("_summary.txt", "");

        java.io.File dir = summaryFile.getParentFile();
        java.io.File photoFile = new java.io.File(dir, baseName + ".jpg");
        java.io.File graphFile = new java.io.File(dir, baseName + "_graph.png");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));

        if (photoFile.exists() && photoFile.length() > 0) {
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(BitmapFactory.decodeFile(photoFile.getAbsolutePath()));
            box.addView(image, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(220)
            ));
        }

        if (graphFile.exists() && graphFile.length() > 0) {
            TextView gt = tv("IMPACT TREND", 13, Color.rgb(15, 38, 61));
            gt.setTypeface(null, 1);
            box.addView(gt);

            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageBitmap(BitmapFactory.decodeFile(graphFile.getAbsolutePath()));
            box.addView(image, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(220)
            ));
        }

        box.addView(tv(readText(summaryFile), 14, Color.DKGRAY));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new android.app.AlertDialog.Builder(this)
                .setTitle("IMPACT DETAIL")
                .setView(sv)
                .setPositiveButton("닫기", null)
                .show();
    }

    private String readText(java.io.File file) {
        StringBuilder sb = new StringBuilder();

        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        new java.io.FileInputStream(file),
                        java.nio.charset.StandardCharsets.UTF_8
                )
        )) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception e) {
            sb.append("읽기 오류 : ").append(e.getMessage());
        }

        return sb.toString();
    }

    private void setupCamera() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {

            cam.setText("CAMERA : PERMISSION REQUIRED");
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 2202);
            return;
        }

        openCamera();
    }

    private void cameraThread() {
        if (camThread != null) return;

        camThread = new android.os.HandlerThread("ShockCamera");
        camThread.start();
        camHandler = new android.os.Handler(camThread.getLooper());
    }

    private void openCamera() {
        if (camera != null || cameraOpening) return;

        cameraOpening = true;
        cameraThread();

        try {
            android.hardware.camera2.CameraManager m =
                    (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);

            cameraId = null;

            for (String id : m.getCameraIdList()) {
                Integer face = m.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);

                if (face != null
                        && face == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
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
                    1280, 720, android.graphics.ImageFormat.JPEG, 2
            );

            reader.setOnImageAvailableListener(
                    r -> {
                        android.media.Image im = null;

                        try {
                            im = r.acquireLatestImage();
                            java.io.File f = photoTarget;

                            if (im == null || f == null) return;

                            java.nio.ByteBuffer b = im.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[b.remaining()];
                            b.get(bytes);

                            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                                o.write(bytes);
                            }

                            photoTarget = null;

                            runOnUiThread(() -> cam.setText("CAMERA : READY · EVENT PHOTO SAVED"));

                        } catch (Exception ignored) {
                        } finally {
                            if (im != null) im.close();
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
                            runOnUiThread(() -> cam.setText("CAMERA : READY"));
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
                            runOnUiThread(() -> cam.setText("CAMERA : ERROR " + e));
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
            if (session != null) session.close();

            android.view.Surface s = reader.getSurface();

            camera.createCaptureSession(
                    java.util.Collections.singletonList(s),
                    new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(android.hardware.camera2.CameraCaptureSession ss) {
                            session = ss;

                            try {
                                android.hardware.camera2.CaptureRequest.Builder b =
                                        camera.createCaptureRequest(
                                                android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE
                                        );

                                b.addTarget(s);
                                ss.capture(b.build(), null, camHandler);

                            } catch (Exception e) {
                                photoTarget = null;
                            }
                        }

                        @Override
                        public void onConfigureFailed(android.hardware.camera2.CameraCaptureSession ss) {
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

        try { if (session != null) session.close(); } catch (Exception ignored) {}
        session = null;

        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        camera = null;

        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        reader = null;

        if (camThread != null) {
            camThread.quitSafely();
            camThread = null;
            camHandler = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);

        if (r == 2202) {
            if (g.length > 0
                    && g[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
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
        // Keep the sensor listener alive while measurement is running.
        // The foreground service keeps the process awake for screen-off/background monitoring.
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (running && acc != null) {
            sm.unregisterListener(this);
            sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
        }

        if (cam != null && camera == null) setupCamera();
    }

    @Override
    public void onBackPressed() {
        if (running) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("측정 중")
                    .setMessage("연속 측정 중에는 화면을 종료하지 마세요.\n먼저 STOP & ANALYZE를 눌러 측정을 종료해주세요.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }

        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeContinuousCsv();

        if (!running) {
            sm.unregisterListener(this);
            closeCamera();
        }
    }

    static class RecipeUnit {
        String name = "U";
        double seconds = 10.0;
    }

    static class P {
        long wall, mono;
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

    static class EventRecord {
        int no;
        long timeMs;
        long offsetSec;
        String line, equipment, process, unit, axis, base;
        double peak, rms, duration, mx, my, mz;
    }

    static class UnitSegment {
        String process = "-";
        String unit = "-";
        String axis = "-";
        double durationSec = 0.0;
        double peak = 0.0;
        double rms = 0.0;
        double mx = 0.0;
        double my = 0.0;
        double mz = 0.0;
        int impactCount = 0;
    }

    static class UnitAggregate {
        String label = "-";
        int count = 0;
        int segmentCount = 0;
        double maxPeak = 0.0;
        double rmsWeighted = 0.0;
        double durationSec = 0.0;

        double avgRms() {
            return durationSec > 0.0
                    ? rmsWeighted / durationSec
                    : 0.0;
        }
    }

    static class ImpactTimelineView extends View {
        private final ArrayList<EventRecord> data = new ArrayList<>();
        private final ArrayList<RecipeUnit> recipe = new ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long durationSec = 1L;
        private double processSec = 0.0;
        private boolean paused = false;

        ImpactTimelineView(android.content.Context c) {
            super(c);
            setBackgroundColor(Color.WHITE);
        }

        void clear() {
            data.clear();
            processSec = 0.0;
            paused = false;
            invalidate();
        }

        void setRecipe(ArrayList<RecipeUnit> source) {
            recipe.clear();

            if (source != null) {
                recipe.addAll(source);
            }

            double total = 0.0;
            for (RecipeUnit r : recipe) total += r.seconds;

            if (total > 0.0) {
                durationSec = Math.max(1L, Math.round(total));
            }

            invalidate();
        }

        void setDuration(long sec) {
            durationSec = Math.max(1L, sec);
            invalidate();
        }

        void setProcessPosition(double sec, boolean isPaused) {
            processSec = Math.max(0.0, sec);
            paused = isPaused;
            invalidate();
        }

        void refresh(ArrayList<EventRecord> source, long sec) {
            data.clear();
            data.addAll(source);
            durationSec = Math.max(1L, sec);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);

            float left = 18f;
            float right = getWidth() - 18f;
            float top = 22f;
            float bottom = getHeight() - 24f;
            float mid = (top + bottom) / 2f;

            double total = 0.0;
            for (RecipeUnit r : recipe) total += r.seconds;

            if (!recipe.isEmpty() && total > 0.0) {
                double acc = 0.0;

                for (int i = 0; i < recipe.size(); i++) {
                    RecipeUnit r = recipe.get(i);
                    double start = acc;
                    acc += r.seconds;

                    float x1 = left + (right - left) * (float) (start / total);
                    float x2 = left + (right - left) * (float) (acc / total);

                    boolean active = processSec >= start && processSec < acc;

                    p.setColor(
                            active
                                    ? Color.rgb(202, 226, 241)
                                    : (i % 2 == 0
                                            ? Color.rgb(243, 247, 250)
                                            : Color.rgb(232, 239, 244))
                    );
                    c.drawRect(x1, top, x2, bottom, p);

                    p.setColor(Color.rgb(145, 160, 172));
                    p.setStrokeWidth(1.5f);
                    c.drawLine(x1, top, x1, bottom, p);

                    String label = r.name == null ? ("U" + (i + 1)) : r.name;
                    if (label.length() > 4) label = label.substring(0, 4);

                    p.setTextSize(12f);
                    p.setColor(Color.rgb(55, 75, 92));
                    float tw = p.measureText(label);
                    float cx = (x1 + x2) / 2f;
                    c.drawText(
                            label,
                            Math.max(x1 + 1f, cx - tw / 2f),
                            mid + 4f,
                            p
                    );
                }

                p.setColor(Color.rgb(145, 160, 172));
                p.setStrokeWidth(1.5f);
                c.drawLine(right, top, right, bottom, p);
            } else {
                p.setColor(Color.rgb(240, 244, 247));
                c.drawRect(left, top, right, bottom, p);
            }

            float px = left + (right - left)
                    * (float) Math.min(
                            1.0,
                            processSec / Math.max(1.0, durationSec)
                    );

            p.setColor(
                    paused
                            ? Color.rgb(230, 150, 45)
                            : Color.rgb(0, 125, 110)
            );
            p.setStrokeWidth(4f);
            c.drawLine(px, top - 5f, px, bottom + 5f, p);

            for (EventRecord r : data) {
                float x = left + (right - left)
                        * Math.min(
                                1f,
                                r.offsetSec / (float) Math.max(1L, durationSec)
                        );

                p.setColor(Color.rgb(205, 68, 72));
                p.setStrokeWidth(4f);
                c.drawLine(x, top - 10f, x, bottom + 2f, p);

                p.setTextSize(14f);
                p.setColor(Color.rgb(175, 45, 50));
                c.drawText("#" + r.no, Math.max(1f, x - 9f), top - 12f, p);
            }

            p.setTextSize(13f);
            p.setColor(Color.DKGRAY);
            c.drawText("0s", left, getHeight() - 4f, p);

            String endText = durationSec + "s";
            c.drawText(
                    endText,
                    Math.max(left, right - p.measureText(endText)),
                    getHeight() - 4f,
                    p
            );
        }
    }

    static class UnitSummaryView extends View {
        private final ArrayList<UnitAggregate> data = new ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        UnitSummaryView(android.content.Context c, ArrayList<UnitAggregate> source) {
            super(c);
            data.addAll(source);
            Collections.sort(data, (a, b) -> Double.compare(b.maxPeak, a.maxPeak));
            setBackgroundColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (data.isEmpty()) return;

            float left = 14f;
            float right = getWidth() - 14f;
            float row = getHeight() / (float) data.size();

            double max = 1.0;
            for (UnitAggregate a : data) max = Math.max(max, a.maxPeak);

            for (int i = 0; i < data.size(); i++) {
                UnitAggregate a = data.get(i);
                float y = i * row + 19f;

                p.setColor(Color.DKGRAY);
                p.setTextSize(18f);
                c.drawText(a.label, left, y, p);

                p.setTextSize(15f);
                c.drawText(
                        String.format(
                                Locale.US,
                                "MAX %.2f · RMS %.2f · %d events · %.1fs",
                                a.maxPeak,
                                a.avgRms(),
                                a.count,
                                a.durationSec
                        ),
                        left,
                        y + 24f,
                        p
                );

                float barTop = y + 30f;
                float barBottom = Math.min(getHeight() - 3f, barTop + 10f);
                float barWidth = (float) ((right - left) * a.maxPeak / max);

                p.setColor(Color.rgb(35, 105, 170));
                c.drawRoundRect(left, barTop, left + barWidth, barBottom, 6f, 6f, p);
            }
        }
    }

    static class SessionBarsView extends View {
        private final ArrayList<EventRecord> data = new ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SessionBarsView(android.content.Context c, ArrayList<EventRecord> source) {
            super(c);

            data.addAll(source);

            Collections.sort(
                    data,
                    (a, b) -> Double.compare(b.peak, a.peak)
            );

            if (data.size() > 6) {
                while (data.size() > 6) data.remove(data.size() - 1);
            }

            setBackgroundColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);

            if (data.isEmpty()) return;

            float left = 18f;
            float right = getWidth() - 18f;
            float top = 18f;
            float row = (getHeight() - 24f) / data.size();

            double max = 1.0;
            for (EventRecord r : data) max = Math.max(max, r.peak);

            p.setTextSize(19f);
            p.setColor(Color.DKGRAY);

            for (int i = 0; i < data.size(); i++) {
                EventRecord r = data.get(i);
                float y = top + i * row;
                float labelY = y + 19f;

                String label = "#" + r.no + " " + r.process + " / " + r.unit;
                c.drawText(label, left, labelY, p);

                float barTop = y + 25f;
                float barBottom = Math.min(getHeight() - 4f, barTop + 12f);
                float barWidth = (float) ((right - left) * r.peak / max);

                p.setColor(Color.rgb(110, 75, 190));
                c.drawRoundRect(left, barTop, left + barWidth, barBottom, 8f, 8f, p);

                p.setColor(Color.DKGRAY);
                c.drawText(
                        String.format(Locale.US, "%.2f", r.peak),
                        Math.min(right - 60f, left + barWidth + 8f),
                        barBottom,
                        p
                );
            }
        }
    }

    static class ShockGraph extends View {
        ArrayList<Float> x = new ArrayList<>();
        ArrayList<Float> y = new ArrayList<>();
        ArrayList<Float> z = new ArrayList<>();
        ArrayList<Float> t = new ArrayList<>();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        double spec = 2.0;
        double fixedMax = 0.0;

        ShockGraph(android.content.Context c) {
            super(c);
            setBackgroundColor(Color.WHITE);
        }

        void put(ArrayList<Float> a, float q) {
            a.add(q);
            if (a.size() > 240) a.remove(0);
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

        void setScale(double value) {
            fixedMax = Math.max(0.0, value);
            invalidate();
        }

        void line(Canvas c, ArrayList<Float> a, int col, float m) {
            if (a.size() < 2) return;

            p.setColor(col);
            p.setStrokeWidth(col == Color.rgb(110, 75, 190) ? 5f : 3f);

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

            float m;

            if (fixedMax > 0.0) {
                m = (float) fixedMax;
            } else {
                m = (float) Math.max(3.0, spec * 1.5);
                for (float q : t) m = Math.max(m, q * 1.15f);
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

            float sy = (float) (getHeight() - spec / m * getHeight());

            p.setColor(Color.RED);
            p.setStrokeWidth(2f);
            c.drawLine(0, sy, getWidth(), sy, p);

            line(c, x, Color.rgb(0, 130, 220), m);
            line(c, y, Color.rgb(0, 170, 110), m);
            line(c, z, Color.rgb(230, 145, 20), m);
            line(c, t, Color.rgb(110, 75, 190), m);
        }
    }
}
