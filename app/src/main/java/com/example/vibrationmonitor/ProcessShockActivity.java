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
    private Button recipeButton, pauseButton, remoteButton;
    private long lastCheckpointMs = 0L;

    private RemoteMonitorServer remoteServer = null;
    private final ArrayDeque<double[]> remotePoints = new ArrayDeque<>();
    private long remoteLastPointMs = 0L;

    private volatile boolean remoteRunning = false;
    private volatile boolean remoteCalibrating = false;
    private volatile boolean remotePaused = false;
    private volatile double remoteX = 0.0;
    private volatile double remoteY = 0.0;
    private volatile double remoteZ = 0.0;
    private volatile double remoteTotal = 0.0;
    private volatile double remotePeak = 0.0;
    private volatile double remoteRms = 0.0;
    private volatile double remoteProcessSec = 0.0;
    private volatile double remoteMcscTotal = 0.0;
    private volatile double remoteSpec = 2.0;
    private volatile String remoteLine = "-";
    private volatile String remoteEquipment = "-";
    private volatile String remoteProcess = "-";
    private volatile String remoteUnit = "-";
    private volatile String remoteTimelineState = "READY";
    private volatile String remoteMcscJson = "[]";
    private volatile String remoteLastImpact = "-";
    private volatile String remoteEventsJson = "[]";

    private final ArrayList<RecipeUnit> recipe = new ArrayList<>();
    private boolean processPaused = false;
    private long processPauseStartMs = 0L;
    private long totalProcessPausedMs = 0L;
    private long segmentProcessStartMs = 0L;
    private double eventProcessOffsetSec = 0.0;

    private boolean autoCorrectionEnabled = true;
    private boolean autoPauseActive = false;
    private long autoStopCandidateStartMs = 0L;
    private long autoResumeCandidateStartMs = 0L;
    private long autoCorrectionSuppressUntilMs = 0L;
    private long autoResumeImpactUntilMs = 0L;
    private long autoPausedTotalMs = 0L;
    private int autoCorrectionCount = 0;

    private long calibrationSampleCount = 0L;
    private double calibrationSumSq = 0.0;
    private double autoQuietThreshold = 0.15;
    private double autoResumeThreshold = 0.40;

    private static final long AUTO_STOP_CONFIRM_MS = 3000L;
    private static final long AUTO_STOP_CANDIDATE_UI_MS = 1200L;
    private static final long AUTO_RESUME_CONFIRM_MS = 400L;
    private static final long AUTO_MANUAL_SUPPRESS_MS = 5000L;

    private java.io.BufferedWriter continuousWriter = null;
    private java.io.File continuousFile = null;
    private java.io.File currentRunSummaryFile = null;
    private java.io.File currentUnitSummaryFile = null;
    private java.io.File currentImpactSummaryFile = null;
    private long runStartWallMs = 0L;
    private long stoppedDurationSec = 0L;
    private long stoppedProcessDurationSec = 0L;
    private int continuousSinceFlush = 0;

    private final ArrayList<UnitSegment> unitSegments = new ArrayList<>();
    private long segmentStartMs = 0L;
    private long segmentCount = 0L;
    private double segmentSum = 0.0;
    private double segmentSumSq = 0.0;
    private double segmentMin = Double.POSITIVE_INFINITY;
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
    private long eventCoreEnd = 0L;
    private boolean eventCoreClosed = false;

    private static final long IMPACT_QUIET_MS = 600L;
    private static final long IMPACT_POST_MS = 3000L;
    private static final double IMPACT_RELEASE_FACTOR = 0.65;

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

        TextView bb = tv("SMART SPLIT 0.6s · PRE 3s + IMPACT CORE + POST 3s · PHOTO · GRAPH · CSV", 10, Color.rgb(70, 90, 105));
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
                new String[]{"STACK", "PACKAGE", "FORMATION"}
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

        recipeButton = btn("MCSC", Color.rgb(67, 88, 108));
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

        LinearLayout dataActionRow = new LinearLayout(this);
        dataActionRow.setOrientation(LinearLayout.HORIZONTAL);

        Button history = btn("EVENT HISTORY", Color.rgb(42, 91, 126));
        history.setTextSize(12);
        LinearLayout.LayoutParams historyLp =
                new LinearLayout.LayoutParams(0, dp(50), 1f);
        historyLp.setMargins(0, dp(9), dp(3), 0);
        dataActionRow.addView(history, historyLp);
        history.setOnClickListener(v -> showHistory());

        Button dataDownload = btn("DATA DOWNLOAD", Color.rgb(40, 112, 145));
        dataDownload.setTextSize(12);
        LinearLayout.LayoutParams downloadLp =
                new LinearLayout.LayoutParams(0, dp(50), 1f);
        downloadLp.setMargins(dp(3), dp(9), 0, 0);
        dataActionRow.addView(dataDownload, downloadLp);
        dataDownload.setOnClickListener(v -> showExportDialog());

        remoteButton = btn("REMOTE VIEW", Color.rgb(0, 125, 110));
        remoteButton.setTextSize(10);
        LinearLayout.LayoutParams remoteLp =
                new LinearLayout.LayoutParams(0, dp(50), 1f);
        remoteLp.setMargins(dp(3), dp(9), 0, 0);
        dataActionRow.addView(remoteButton, remoteLp);
        remoteButton.setOnClickListener(v -> showRemoteMonitorDialog());

        root.addView(dataActionRow);

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
                sec = Math.round(sec * 10.0) / 10.0;

                if (!name.isEmpty() && sec >= 0.1) {
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

        android.content.SharedPreferences mcscPrefs =
                getSharedPreferences("ShockRecipe", MODE_PRIVATE);

        String key = recipeKey();
        String currentProcess = String.valueOf(process.getSelectedItem());

        String text;

        if ("FORMATION".equals(currentProcess)
                && !mcscPrefs.contains(key)
                && mcscPrefs.contains("recipe_ACTIVATION")) {
            text = mcscPrefs.getString("recipe_ACTIVATION", defaultRecipeText());
        } else {
            text = mcscPrefs.getString(key, defaultRecipeText());
        }

        ArrayList<RecipeUnit> parsed = parseRecipe(text);

        recipe.clear();

        if (parsed.isEmpty()) {
            parsed = parseRecipe(defaultRecipeText());
        }

        recipe.addAll(parsed);

        if ("FORMATION".equals(currentProcess)
                && !mcscPrefs.contains(key + "_auto_corr")
                && mcscPrefs.contains("recipe_ACTIVATION_auto_corr")) {
            autoCorrectionEnabled =
                    mcscPrefs.getBoolean("recipe_ACTIVATION_auto_corr", true);
        } else {
            autoCorrectionEnabled =
                    mcscPrefs.getBoolean(key + "_auto_corr", true);
        }

        if (timeline != null) {
            timeline.setRecipe(recipe);
        }
    }

    private String mcscText(ArrayList<RecipeUnit> list) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append("\n");

            RecipeUnit r = list.get(i);
            sb.append(r.name)
                    .append(",")
                    .append(String.format(Locale.US, "%.1f", r.seconds));
        }

        return sb.toString();
    }

    private String mcscStatusText(String text) {
        ArrayList<RecipeUnit> list = parseRecipe(text);
        double total = 0.0;

        for (RecipeUnit r : list) total += r.seconds;

        return String.format(
                Locale.US,
                "MCSC UNIT COUNT : %d · TOTAL %.1fs · STEP 0.1s",
                list.size(),
                total
        );
    }

    private void updateMcscCount(EditText editor, TextView countLabel) {
        countLabel.setText(mcscStatusText(editor.getText().toString()));
    }

    private void changeMcscCount(EditText editor, TextView countLabel, int delta) {
        ArrayList<RecipeUnit> list = parseRecipe(editor.getText().toString());

        if (list.isEmpty()) {
            list = parseRecipe(defaultRecipeText());
        }

        if (delta > 0) {
            RecipeUnit r = new RecipeUnit();
            r.name = "U" + (list.size() + 1);
            r.seconds = list.isEmpty() ? 10.0 : list.get(list.size() - 1).seconds;
            list.add(r);

        } else if (delta < 0 && list.size() > 1) {
            list.remove(list.size() - 1);
        }

        editor.setText(mcscText(list));
        editor.setSelection(editor.getText().length());
        updateMcscCount(editor, countLabel);
    }

    private void resetMcsc10(EditText editor, TextView countLabel) {
        editor.setText(defaultRecipeText());
        editor.setSelection(editor.getText().length());
        updateMcscCount(editor, countLabel);
    }

    private void showRecipeDialog() {
        if (running) {
            Toast.makeText(
                    this,
                    "측정 중에는 MCSC를 변경할 수 없습니다.",
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

        TextView mcscCount = tv(
                mcscStatusText(e.getText().toString()),
                12,
                Color.rgb(55, 75, 92)
        );
        mcscCount.setTypeface(null, 1);

        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(
                    CharSequence s,
                    int start,
                    int count,
                    int after
            ) {}

            @Override
            public void onTextChanged(
                    CharSequence s,
                    int start,
                    int before,
                    int count
            ) {}

            @Override
            public void afterTextChanged(android.text.Editable editable) {
                updateMcscCount(e, mcscCount);
            }
        });

        LinearLayout mcscButtons = new LinearLayout(this);
        mcscButtons.setOrientation(LinearLayout.HORIZONTAL);

        Button minusUnit = btn("- UNIT", Color.rgb(90, 105, 118));
        Button plusUnit = btn("+ UNIT", Color.rgb(0, 135, 105));
        Button reset10 = btn("RESET 10", Color.rgb(67, 88, 108));

        minusUnit.setTextSize(11);
        plusUnit.setTextSize(11);
        reset10.setTextSize(11);

        mcscButtons.addView(
                minusUnit,
                new LinearLayout.LayoutParams(0, dp(40), 1f)
        );

        LinearLayout.LayoutParams plusLp =
                new LinearLayout.LayoutParams(0, dp(40), 1f);
        plusLp.setMargins(dp(5), 0, dp(5), 0);
        mcscButtons.addView(plusUnit, plusLp);

        mcscButtons.addView(
                reset10,
                new LinearLayout.LayoutParams(0, dp(40), 1f)
        );

        minusUnit.setOnClickListener(v -> changeMcscCount(e, mcscCount, -1));
        plusUnit.setOnClickListener(v -> changeMcscCount(e, mcscCount, 1));
        reset10.setOnClickListener(v -> resetMcsc10(e, mcscCount));

        CheckBox autoCorrCheck = new CheckBox(this);
        autoCorrCheck.setText("AUTO STOP CORRECTION");
        autoCorrCheck.setTextSize(14);
        autoCorrCheck.setChecked(autoCorrectionEnabled);

        TextView autoCorrHelp = tv(
                "저진동 상태가 3초 이상 지속되면 설비 정지 후보로 보고 PROCESS 시간만 자동 보정합니다. "
                        + "공정 자체에 3초 이상의 정숙 구간이 많으면 OFF로 사용하세요.",
                11,
                Color.rgb(85, 100, 112)
        );

        LinearLayout recipeDialogBox = new LinearLayout(this);
        recipeDialogBox.setOrientation(LinearLayout.VERTICAL);
        recipeDialogBox.setPadding(dp(8), dp(2), dp(8), dp(2));
        recipeDialogBox.addView(mcscCount);
        recipeDialogBox.addView(mcscButtons);
        recipeDialogBox.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(230)
        ));
        recipeDialogBox.addView(autoCorrCheck);
        recipeDialogBox.addView(autoCorrHelp);

        new android.app.AlertDialog.Builder(this)
                .setTitle("MCSC · UNIT TIME MAP")
                .setMessage(
                        "ENGINEER MCSC · 0.1초 단위 설정\n"
                                + "한 줄에 Unit 이름, 기준시간(초)\n"
                                + "예: Loader,0.4\nTransfer,0.8\nPress,1.3\n\n"
                                + "설비가 멈추면 PROCESS PAUSE를 누르면\n"
                                + "진동 측정은 계속하고 공정시간만 멈춥니다."
                )
                .setView(recipeDialogBox)
                .setPositiveButton("저장", (d, w) -> {
                    ArrayList<RecipeUnit> parsed =
                            parseRecipe(e.getText().toString());

                    if (parsed.isEmpty()) {
                        Toast.makeText(
                                this,
                                "MCSC 형식을 확인해주세요.",
                                Toast.LENGTH_LONG
                        ).show();
                        return;
                    }

                    recipe.clear();
                    recipe.addAll(parsed);
                    autoCorrectionEnabled = autoCorrCheck.isChecked();

                    getSharedPreferences("ShockRecipe", MODE_PRIVATE)
                            .edit()
                            .putString(recipeKey(), recipeToText())
                            .putBoolean(recipeKey() + "_auto_corr", autoCorrectionEnabled)
                            .apply();

                    Toast.makeText(
                            this,
                            "MCSC " + recipe.size() + "개 Unit 저장 완료",
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
            return autoPauseActive ? "AUTO STOP" : "LINE STOP";
        }

        int idx = activeRecipeIndex(getProcessElapsedMs(now));

        if (idx >= 0 && idx < recipe.size()) {
            return recipe.get(idx).name;
        }

        return "AFTER MCSC";
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
                                + " · CORR "
                                + (autoCorrectionEnabled ? "ON" : "OFF")
                            : "MANUAL UNIT · READY"
            );
        }
    }

    private void toggleProcessPause() {
        if (!running || !isAutoMode() || calibrating) return;

        long now = SystemClock.elapsedRealtime();

        if (!processPaused) {
            autoPauseActive = false;
            autoStopCandidateStartMs = 0L;
            autoResumeCandidateStartMs = 0L;

            processPaused = true;
            processPauseStartMs = now;
            pauseButton.setText("RESUME");
            status.setText("● MANUAL LINE STOP · PROCESS TIME PAUSED");
            status.setTextColor(Color.rgb(255, 185, 70));
        } else {
            long pausedMs = Math.max(0L, now - processPauseStartMs);
            totalProcessPausedMs += pausedMs;

            if (autoPauseActive) {
                autoPausedTotalMs += pausedMs;
            }

            processPauseStartMs = 0L;
            processPaused = false;
            autoPauseActive = false;
            autoStopCandidateStartMs = 0L;
            autoResumeCandidateStartMs = 0L;
            autoCorrectionSuppressUntilMs = now + AUTO_MANUAL_SUPPRESS_MS;

            pauseButton.setText("PAUSE");
            status.setText("● MONITORING · AUTO TIMELINE · MANUAL OVERRIDE");
            status.setTextColor(Color.rgb(80, 220, 150));
        }
    }

    private void resetAutoCorrectionCandidates() {
        autoStopCandidateStartMs = 0L;
        autoResumeCandidateStartMs = 0L;
    }

    private String timelineState() {
        if (processPaused) {
            return autoPauseActive ? "AUTO_HOLD" : "MANUAL_HOLD";
        }

        if (autoStopCandidateStartMs > 0L) {
            return "STOP_CANDIDATE";
        }

        return "RUN";
    }

    private void beginAutoTimelineHold(long candidateStartMs) {
        if (processPaused || !autoCorrectionEnabled) return;

        processPaused = true;
        autoPauseActive = true;
        processPauseStartMs = candidateStartMs;
        autoCorrectionCount++;
        autoStopCandidateStartMs = 0L;
        autoResumeCandidateStartMs = 0L;

        pauseButton.setText("RESUME");
        status.setText("● AUTO LINE STOP #" + autoCorrectionCount + " · TIMELINE HOLD");
        status.setTextColor(Color.rgb(255, 185, 70));
    }

    private void endAutoTimelineHold(long now) {
        if (!processPaused || !autoPauseActive) return;

        long pausedMs = Math.max(0L, now - processPauseStartMs);
        totalProcessPausedMs += pausedMs;
        autoPausedTotalMs += pausedMs;

        processPauseStartMs = 0L;
        processPaused = false;
        autoPauseActive = false;
        autoStopCandidateStartMs = 0L;
        autoResumeCandidateStartMs = 0L;

        autoResumeImpactUntilMs = now + 1500L;

        pauseButton.setText("PAUSE");
        status.setText(String.format(
                Locale.US,
                "● AUTO RESUME · CORRECTED %.1fs",
                pausedMs / 1000.0
        ));
        status.setTextColor(Color.rgb(80, 220, 150));
    }

    private void updateAutoTimelineCorrection(long now, double vibration) {
        if (!running
                || calibrating
                || !isAutoMode()
                || !autoCorrectionEnabled
                || startMs <= 0L) {
            resetAutoCorrectionCandidates();
            return;
        }

        if (processPaused) {
            if (!autoPauseActive) {
                autoResumeCandidateStartMs = 0L;
                return;
            }

            if (vibration >= autoResumeThreshold) {
                if (autoResumeCandidateStartMs <= 0L) {
                    autoResumeCandidateStartMs = now;
                }

                if (now - autoResumeCandidateStartMs >= AUTO_RESUME_CONFIRM_MS) {
                    endAutoTimelineHold(now);
                }
            } else {
                autoResumeCandidateStartMs = 0L;
            }
            return;
        }

        if (now < autoCorrectionSuppressUntilMs) {
            autoStopCandidateStartMs = 0L;
            return;
        }

        if (vibration <= autoQuietThreshold) {
            if (autoStopCandidateStartMs <= 0L) {
                autoStopCandidateStartMs = now;
            }

            long quietMs = now - autoStopCandidateStartMs;

            if (quietMs >= AUTO_STOP_CANDIDATE_UI_MS
                    && quietMs < AUTO_STOP_CONFIRM_MS
                    && !eventOn) {
                status.setText(String.format(
                        Locale.US,
                        "● STOP CANDIDATE %.1fs · AUTO CORR ARMED",
                        quietMs / 1000.0
                ));
                status.setTextColor(Color.rgb(255, 185, 70));
            }

            if (quietMs >= AUTO_STOP_CONFIRM_MS) {
                beginAutoTimelineHold(autoStopCandidateStartMs);
            }
        } else {
            if (autoStopCandidateStartMs > 0L && !eventOn) {
                status.setText("● MONITORING · AUTO TIMELINE");
                status.setTextColor(Color.rgb(80, 220, 150));
            }
            autoStopCandidateStartMs = 0L;
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
                    "DateTime,RealElapsedMs,ProcessElapsedMs,ProcessPaused,TimelineState,"
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
                    "%s,%d,%d,%s,%s,%s,%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                    ts,
                    Math.max(0L, now - startMs),
                    getProcessElapsedMs(now),
                    processPaused ? "Y" : "N",
                    timelineState(),
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
                Toast.makeText(this, "AUTO TIMELINE MCSC가 없습니다.", Toast.LENGTH_LONG).show();
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
        segmentSum = 0.0;
        segmentSumSq = 0.0;
        segmentMin = Double.POSITIVE_INFINITY;
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

        long stopNow = SystemClock.elapsedRealtime();

        stoppedDurationSec = startMs > 0L
                ? Math.max(0L, (stopNow - startMs) / 1000L)
                : 0L;

        stoppedProcessDurationSec = startMs > 0L
                ? (
                        isAutoMode()
                                ? Math.max(0L, getProcessElapsedMs(stopNow) / 1000L)
                                : stoppedDurationSec
                )
                : 0L;

        if (eventOn) finishEvent();
        finalizeUnitSegment(stopNow);

        if (processPaused && processPauseStartMs > 0L) {
            long pausedMs = Math.max(0L, stopNow - processPauseStartMs);
            totalProcessPausedMs += pausedMs;
            if (autoPauseActive) {
                autoPausedTotalMs += pausedMs;
            }
        }

        processPauseStartMs = 0L;
        processPaused = false;
        autoPauseActive = false;
        resetAutoCorrectionCandidates();
        closeContinuousCsv();

        running = false;
        calibrating = false;

        remoteRunning = false;
        remoteCalibrating = false;
        remotePaused = false;
        remoteProcessSec = stoppedProcessDurationSec;
        remoteTimelineState = "STOPPED";
        refreshRemoteMetadata();

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
        saveRunSummaryCsv();
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

        updateRemoteSnapshot(now, x, y, z, t);

        if (calibrating) {
            calibrationSampleCount++;
            calibrationSumSq += t * t;

            long remain = Math.max(0L, calibrationEndMs - now);

            if (now - lastUi > 80L) {
                lastUi = now;
                elapsed.setText(String.format(Locale.US, "CALIBRATING %.1fs", remain / 1000.0));
                total.setText(String.format(Locale.US, "%.3f m/s²", t));
            }

            if (now >= calibrationEndMs) {
                double calibrationRms = Math.sqrt(
                        calibrationSumSq / Math.max(1L, calibrationSampleCount)
                );

                autoQuietThreshold = Math.max(
                        0.10,
                        Math.min(spec * 0.20, calibrationRms * 3.0 + 0.03)
                );

                autoResumeThreshold = Math.max(
                        autoQuietThreshold * 2.2,
                        Math.min(spec * 0.35, autoQuietThreshold + 0.25)
                );

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

        updateAutoTimelineCorrection(now, t);

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
            segmentSum += t;
            segmentSumSq += t * t;
            segmentMin = Math.min(segmentMin, t);
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

            if (!eventCoreClosed) {
                if (t >= spec * IMPACT_RELEASE_FACTOR) {
                    lastAbove = now;
                }

                if (now - lastAbove >= IMPACT_QUIET_MS) {
                    eventCoreClosed = true;
                    eventCoreEnd = lastAbove;

                    status.setText(
                            "● IMPACT #"
                                    + impactCount
                                    + " CORE CLOSED · POST 3s"
                    );
                    status.setTextColor(Color.rgb(255, 185, 70));
                }
            } else {
                if (now - eventCoreEnd >= IMPACT_POST_MS) {
                    finishEvent();
                }
            }
        }

        graph.add(x, y, z, t, spec);

        if (startMs > 0L) {
            timeline.setDuration(
                    isAutoMode()
                            ? Math.max(0.1, recipeTotalSec())
                            : Math.max(0.1, (now - startMs) / 1000.0)
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

            double processExactSec = getProcessElapsedMs(now) / 1000.0;
            long processSec = (long) processExactSec;

            if (isAutoMode()) {
                double unitElapsed = recipeUnitProgressSec(now);
                double unitDuration = recipeUnitDurationSec(now);
                double unitPct = unitDuration > 0.0
                        ? Math.min(100.0, unitElapsed / unitDuration * 100.0)
                        : 0.0;

                double mcscTotal = recipeTotalSec();
                String unitPosition;

                if (mcscTotal > 0.0 && processExactSec >= mcscTotal) {
                    unitPosition = String.format(
                            Locale.US,
                            "OVER MCSC +%.1fs",
                            processExactSec - mcscTotal
                    );
                } else {
                    unitPosition = String.format(
                            Locale.US,
                            "%s · %.1f / %.1fs · %.0f%%",
                            selectedUnit,
                            unitElapsed,
                            unitDuration,
                            unitPct
                    );
                }

                String processTimeText = String.format(
                        Locale.US,
                        "%02d:%04.1f",
                        (int) (processExactSec / 60.0),
                        processExactSec % 60.0
                );

                processClock.setText(String.format(
                        Locale.US,
                        "REAL %02d:%02d · PROCESS %s\n%s %s",
                        sec / 60,
                        sec % 60,
                        processTimeText,
                        unitPosition,
                        processPaused
                                ? (autoPauseActive ? "· AUTO HOLD" : "· PAUSED")
                                : (autoCorrectionEnabled ? "· CORR ON" : "· CORR OFF")
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
        eventCoreEnd = 0L;
        eventCoreClosed = false;
        impactCount++;

        eventLine = lineInput.getText().toString().trim();
        eventEquipment = equipmentInput.getText().toString().trim();
        ep = String.valueOf(process.getSelectedItem());

        if (isAutoMode() && autoPauseActive) {
            eu = "AUTO STOP";
        } else if (isAutoMode() && now <= autoResumeImpactUntilMs) {
            eu = "AUTO RESUME";
        } else {
            eu = currentTaggedUnit(now);
        }

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
        eventCoreClosed = false;
        eventCoreEnd = 0L;
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

        remoteLastImpact = String.format(
                Locale.US,
                "#%03d · %s / %s · PEAK %.3f · RMS %.3f",
                record.no,
                blank(record.process),
                blank(record.unit),
                record.peak,
                record.rms
        );
        refreshRemoteEventsJson();

        double timelineDuration = isAutoMode()
                ? Math.max(0.1, recipeTotalSec())
                : (
                        startMs > 0L
                                ? Math.max(0.1, (SystemClock.elapsedRealtime() - startMs) / 1000.0)
                                : 0.1
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
        long coreCount = 0L;

        for (P p : d) {
            if (p.mono < es || p.mono > la) {
                continue;
            }

            s.pk = Math.max(s.pk, p.t);
            q += p.t * p.t;
            s.mx = Math.max(s.mx, Math.abs(p.x));
            s.my = Math.max(s.my, Math.abs(p.y));
            s.mz = Math.max(s.mz, Math.abs(p.z));
            coreCount++;
        }

        if (coreCount == 0L && !d.isEmpty()) {
            P best = d.get(d.size() - 1);

            for (P p : d) {
                if (Math.abs(p.mono - es) < Math.abs(best.mono - es)) {
                    best = p;
                }
            }

            s.pk = best.t;
            q = best.t * best.t;
            s.mx = Math.abs(best.x);
            s.my = Math.abs(best.y);
            s.mz = Math.abs(best.z);
            coreCount = 1L;
        }

        s.rms = coreCount == 0L ? 0.0 : Math.sqrt(q / coreCount);
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
                    "Peak (CORE) : %.3f m/s²\nRMS (CORE) : %.3f m/s²\nImpact Duration (CORE) : %.2f sec\n"
                            + "Main Direction : %s AXIS\nX / Y / Z Peak : %.3f / %.3f / %.3f m/s²\nSPEC : %.3f m/s²",
                    s.pk, s.rms, s.dur, s.axis, s.mx, s.my, s.mz, spec
            ));
            o.println("Photo : " + (ph == null ? "-" : ph.getName()));
            o.println("Graph : " + png.getName());
            o.println("CSV : " + csv.getName());
            o.println("Window : PRE 3 sec + IMPACT CORE + POST 3 sec");
            o.println(
                    "Detection : release "
                            + String.format(Locale.US, "%.0f", IMPACT_RELEASE_FACTOR * 100.0)
                            + "% SPEC / quiet "
                            + String.format(Locale.US, "%.2f", IMPACT_QUIET_MS / 1000.0)
                            + " sec"
            );
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
        segmentSum = 0.0;
        segmentSumSq = 0.0;
        segmentMin = Double.POSITIVE_INFINITY;
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
        r.sampleCount = segmentCount;
        r.sum = segmentSum;
        r.sumSq = segmentSumSq;
        r.avg = segmentCount > 0L ? segmentSum / segmentCount : 0.0;
        r.min = segmentCount > 0L && Double.isFinite(segmentMin) ? segmentMin : 0.0;
        r.peak = segmentPeak;
        r.rms = Math.sqrt(segmentSumSq / Math.max(1L, segmentCount));
        r.impactCount = Math.max(0, impactCount - segmentImpactStartCount);
        r.mx = segmentMaxX;
        r.my = segmentMaxY;
        r.mz = segmentMaxZ;
        r.axis = r.mx >= r.my && r.mx >= r.mz
                ? "X"
                : (r.my >= r.mz ? "Y" : "Z");

        if (r.sampleCount > 0L) {
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
        currentUnitSummaryFile = f;

        try (java.io.PrintWriter o = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(f),
                        java.nio.charset.StandardCharsets.UTF_8
                )
        )) {
            o.println(
                    "Segment,Process,UnitAction,DurationSec,Avg,Min,Max,RMS,ImpactCount,Axis,XPeak,YPeak,ZPeak"
            );

            int no = 1;

            for (UnitSegment r : unitSegments) {
                o.println(String.format(
                        Locale.US,
                        "%d,%s,%s,%.3f,%.6f,%.6f,%.6f,%.6f,%d,%s,%.6f,%.6f,%.6f",
                        no++,
                        safe(r.process),
                        safe(r.unit),
                        r.durationSec,
                        r.avg,
                        r.min,
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
        currentImpactSummaryFile = f;

        try (java.io.PrintWriter o = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(f),
                        java.nio.charset.StandardCharsets.UTF_8
                )
        )) {
            o.println("Event,Category,Time,Line,Equipment,Process,UnitAction,Peak,RMS,DurationSec,Axis,XPeak,YPeak,ZPeak,Spec");

            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

            for (EventRecord r : sessionEvents) {
                o.println(String.format(
                        Locale.US,
                        "%d,%s,%s,%s,%s,%s,%s,%.6f,%.6f,%.3f,%s,%.6f,%.6f,%.6f,%.6f",
                        r.no,
                        eventCategory(r.unit),
                        fmt.format(new java.util.Date(r.timeMs)),
                        safe(r.line), safe(r.equipment), safe(r.process), safe(r.unit),
                        r.peak, r.rms, r.duration, r.axis, r.mx, r.my, r.mz, spec
                ));
            }
        } catch (Exception ignored) {}
    }

    private void saveRunSummaryCsv() {
        String stamp = new java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
        ).format(new java.util.Date(
                runStartWallMs > 0L ? runStartWallMs : System.currentTimeMillis()
        ));

        currentRunSummaryFile = new java.io.File(
                eventDir(),
                "RUN_SUMMARY_" + stamp + ".csv"
        );

        long now = SystemClock.elapsedRealtime();
        long realDurationSec = running
                ? (
                        startMs > 0L
                                ? Math.max(0L, (now - startMs) / 1000L)
                                : 0L
                )
                : stoppedDurationSec;

        long processDurationSec = running
                ? (
                        startMs > 0L
                                ? Math.max(0L, getProcessElapsedMs(now) / 1000L)
                                : 0L
                )
                : stoppedProcessDurationSec;
        double sessionRms = n > 0L ? Math.sqrt(sumSq / n) : 0.0;

        String mainAxis =
                sessionMaxX >= sessionMaxY && sessionMaxX >= sessionMaxZ
                        ? "X"
                        : (sessionMaxY >= sessionMaxZ ? "Y" : "Z");

        try (java.io.PrintWriter o = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(currentRunSummaryFile),
                        java.nio.charset.StandardCharsets.UTF_8
                )
        )) {
            o.println(
                    "RunStart,Line,Equipment,Process,Mode,RealDurationSec,"
                            + "ProcessDurationSec,ImpactCount,Peak,RMS,MainAxis,"
                            + "XPeak,YPeak,ZPeak,Spec,RawFile"
            );

            String startText = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.US
            ).format(new java.util.Date(
                    runStartWallMs > 0L ? runStartWallMs : System.currentTimeMillis()
            ));

            o.println(String.format(
                    Locale.US,
                    "%s,%s,%s,%s,%s,%d,%d,%d,%.6f,%.6f,%s,"
                            + "%.6f,%.6f,%.6f,%.6f,%s",
                    startText,
                    safe(lineInput.getText().toString().trim()),
                    safe(equipmentInput.getText().toString().trim()),
                    safe(String.valueOf(process.getSelectedItem())),
                    safe(isAutoMode() ? "AUTO_TIMELINE" : "MANUAL_UNIT"),
                    realDurationSec,
                    processDurationSec,
                    impactCount,
                    sessionPeak,
                    sessionRms,
                    mainAxis,
                    sessionMaxX,
                    sessionMaxY,
                    sessionMaxZ,
                    spec,
                    continuousFile == null ? "" : safe(continuousFile.getName())
            ));

            o.println();
            o.println("MCSCOrder,UnitAction,TargetSec");

            for (int i = 0; i < recipe.size(); i++) {
                RecipeUnit r = recipe.get(i);
                o.println(String.format(
                        Locale.US,
                        "%d,%s,%.3f",
                        i + 1,
                        safe(r.name),
                        r.seconds
                ));
            }
        } catch (Exception ignored) {}
    }

    private String exportPrefix() {
        String stamp = new java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
        ).format(new java.util.Date(
                runStartWallMs > 0L ? runStartWallMs : System.currentTimeMillis()
        ));

        String ln = exportFilePart(lineInput.getText().toString().trim());
        String eq = exportFilePart(equipmentInput.getText().toString().trim());
        String pp = exportFilePart(String.valueOf(process.getSelectedItem()));

        return "VM_" + stamp
                + "_" + (ln.isEmpty() ? "Line" : ln)
                + "_" + (eq.isEmpty() ? "Equip" : eq)
                + "_" + (pp.isEmpty() ? "Process" : pp);
    }

    private String exportFilePart(String value) {
        if (value == null) return "";

        return value.trim()
                .replaceAll("[^A-Za-z0-9._-]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private void showExportDialog() {
        if (running) {
            Toast.makeText(
                    this,
                    "측정을 종료한 뒤 DATA DOWNLOAD를 실행해주세요.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        if (continuousFile == null || !continuousFile.exists()) {
            Toast.makeText(
                    this,
                    "다운로드할 측정 데이터가 없습니다.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        final String[] titles = {
                "전체 데이터 ZIP",
                "전체 RAW CSV",
                "RUN SUMMARY CSV",
                "UNIT SUMMARY CSV",
                "IMPACT SUMMARY CSV",
                "EVENT BLACKBOX ZIP"
        };

        final String[] desc = {
                "RAW + 전체 평가 + 사진 + 그래프 + Event CSV",
                "측정 시작부터 종료까지 모든 X / Y / Z / Total",
                "전체 측정시간 / Peak / RMS / 방향 / MCSC",
                "MCSC Unit별 시간 / Avg / Min / Max / RMS / Impact",
                "충격 이벤트별 Peak / RMS / 방향 / 시간",
                "충격 당시 사진 + 그래프 + Event CSV + 요약"
        };

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(6), dp(12), dp(8));

        TextView savePath = tv(
                "저장 위치 : Download / VibrationMonitor",
                12,
                Color.rgb(80, 95, 108)
        );
        savePath.setPadding(dp(4), dp(2), dp(4), dp(8));
        box.addView(savePath);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        final android.app.AlertDialog dialog =
                new android.app.AlertDialog.Builder(this)
                        .setTitle("MEASUREMENT DATA DOWNLOAD")
                        .setView(scroll)
                        .setNegativeButton("취소", null)
                        .create();

        for (int i = 0; i < titles.length; i++) {
            final int index = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(7), dp(10), dp(7));
            row.setBackground(bg(
                    i == 0
                            ? Color.rgb(232, 244, 249)
                            : Color.rgb(246, 248, 250),
                    10
            ));

            TextView title = tv(
                    titles[i],
                    i == 0 ? 14 : 13,
                    Color.rgb(15, 38, 61)
            );
            title.setTypeface(null, 1);
            row.addView(title);

            TextView detail = tv(
                    desc[i],
                    11,
                    Color.rgb(95, 110, 122)
            );
            detail.setPadding(0, dp(2), 0, 0);
            row.addView(detail);

            LinearLayout.LayoutParams rp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
            rp.setMargins(0, 0, 0, dp(7));
            box.addView(row, rp);

            row.setOnClickListener(v -> {
                dialog.dismiss();
                exportCurrentData(index);
            });
        }

        dialog.show();
    }

    private void exportCurrentData(int which) {
        new Thread(() -> {
            boolean ok = false;
            String savedName = "";

            try {
                try { Thread.sleep(500L); } catch (Exception ignored) {}

                String prefix = exportPrefix();

                if (which == 0) {
                    java.io.File z = buildExportZip(false);
                    savedName = prefix + "_ALL_DATA.zip";
                    ok = copyToPublicDownloads(z, savedName, "application/zip");
                    if (z != null && z.exists()) z.delete();

                } else if (which == 1) {
                    savedName = prefix + "_FULL_RAW.csv";
                    ok = copyToPublicDownloads(continuousFile, savedName, "text/csv");

                } else if (which == 2) {
                    savedName = prefix + "_RUN_SUMMARY.csv";
                    ok = copyToPublicDownloads(currentRunSummaryFile, savedName, "text/csv");

                } else if (which == 3) {
                    savedName = prefix + "_UNIT_SUMMARY.csv";
                    ok = copyToPublicDownloads(currentUnitSummaryFile, savedName, "text/csv");

                } else if (which == 4) {
                    savedName = prefix + "_IMPACT_SUMMARY.csv";
                    ok = copyToPublicDownloads(currentImpactSummaryFile, savedName, "text/csv");

                } else if (which == 5) {
                    java.io.File z = buildExportZip(true);
                    savedName = prefix + "_EVENT_BLACKBOX.zip";
                    ok = copyToPublicDownloads(z, savedName, "application/zip");
                    if (z != null && z.exists()) z.delete();
                }

            } catch (Exception ignored) {
                ok = false;
            }

            final boolean result = ok;
            final String name = savedName;

            runOnUiThread(() -> Toast.makeText(
                    this,
                    result
                            ? "다운로드 완료\nDownload/VibrationMonitor/" + name
                            : "다운로드 실패 또는 해당 데이터가 없습니다.",
                    Toast.LENGTH_LONG
            ).show());
        }).start();
    }

    private java.io.File buildExportZip(boolean eventsOnly) throws Exception {
        java.io.File z = new java.io.File(
                getCacheDir(),
                "vm_export_" + System.currentTimeMillis() + ".zip"
        );

        try (java.util.zip.ZipOutputStream out =
                     new java.util.zip.ZipOutputStream(
                             new java.io.BufferedOutputStream(
                                     new java.io.FileOutputStream(z)
                             )
                     )) {

            if (!eventsOnly) {
                zipAddText(out, "00_README.txt", buildExportManifest());

                zipAddFile(
                        out,
                        continuousFile,
                        "RAW/" + (
                                continuousFile == null
                                        ? "FULL_RUN.csv"
                                        : continuousFile.getName()
                        )
                );
                zipAddFile(out, currentRunSummaryFile, "SUMMARY/RUN_SUMMARY.csv");
                zipAddFile(out, currentUnitSummaryFile, "SUMMARY/UNIT_SUMMARY.csv");
                zipAddFile(out, currentImpactSummaryFile, "SUMMARY/IMPACT_SUMMARY.csv");
            }

            for (EventRecord r : sessionEvents) {
                String bn = r.base;
                if (bn == null || bn.trim().isEmpty()) continue;

                zipAddFile(out, new java.io.File(eventDir(), bn + ".csv"), "EVENTS/" + bn + ".csv");
                zipAddFile(out, new java.io.File(eventDir(), bn + ".jpg"), "EVENTS/" + bn + ".jpg");
                zipAddFile(out, new java.io.File(eventDir(), bn + "_graph.png"), "EVENTS/" + bn + "_graph.png");
                zipAddFile(out, new java.io.File(eventDir(), bn + "_summary.txt"), "EVENTS/" + bn + "_summary.txt");
            }

            if (eventsOnly && sessionEvents.isEmpty()) {
                zipAddText(out, "NO_EVENT.txt", "No impact event was recorded in this session.");
            }
        }

        return z;
    }

    private String buildExportManifest() {
        StringBuilder b = new StringBuilder();
        b.append("VIBRATION MONITOR - PROCESS SHOCK DATA PACKAGE\n\n");
        b.append("Line : ").append(blank(lineInput.getText().toString())).append("\n");
        b.append("Equipment : ").append(blank(equipmentInput.getText().toString())).append("\n");
        b.append("Process : ").append(String.valueOf(process.getSelectedItem())).append("\n");
        b.append("Mode : ").append(isAutoMode() ? "AUTO TIMELINE" : "MANUAL UNIT").append("\n");
        b.append(String.format(Locale.US, "SPEC : %.3f m/s²\n", spec));
        b.append("Impact Count : ").append(impactCount).append("\n\n");
        b.append("FOLDERS\n");
        b.append("RAW      : every measured X/Y/Z/Total sample\n");
        b.append("SUMMARY  : run / unit / impact evaluation CSV\n");
        b.append("EVENTS   : impact photo / graph / event CSV / text summary\n\n");
        b.append("MCSC\n");

        for (int i = 0; i < recipe.size(); i++) {
            RecipeUnit r = recipe.get(i);
            b.append(i + 1)
                    .append(". ")
                    .append(r.name)
                    .append(" : ")
                    .append(String.format(Locale.US, "%.3f sec", r.seconds))
                    .append("\n");
        }

        return b.toString();
    }

    private void zipAddText(
            java.util.zip.ZipOutputStream out,
            String entryName,
            String text
    ) throws Exception {
        out.putNextEntry(new java.util.zip.ZipEntry(entryName));
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.write(bytes);
        out.closeEntry();
    }

    private void zipAddFile(
            java.util.zip.ZipOutputStream out,
            java.io.File source,
            String entryName
    ) throws Exception {
        if (source == null || !source.exists() || !source.isFile()) return;

        out.putNextEntry(new java.util.zip.ZipEntry(entryName));

        try (java.io.BufferedInputStream in =
                     new java.io.BufferedInputStream(
                             new java.io.FileInputStream(source)
                     )) {
            byte[] buf = new byte[64 * 1024];
            int nRead;

            while ((nRead = in.read(buf)) > 0) {
                out.write(buf, 0, nRead);
            }
        }

        out.closeEntry();
    }

    private boolean copyToPublicDownloads(
            java.io.File source,
            String displayName,
            String mime
    ) {
        if (source == null || !source.exists() || !source.isFile()) {
            return false;
        }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues values =
                        new android.content.ContentValues();

                values.put(
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        displayName
                );
                values.put(
                        android.provider.MediaStore.MediaColumns.MIME_TYPE,
                        mime
                );
                values.put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/VibrationMonitor"
                );

                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                );

                if (uri == null) return false;

                try (
                        java.io.InputStream in =
                                new java.io.BufferedInputStream(
                                        new java.io.FileInputStream(source)
                                );
                        java.io.OutputStream out =
                                new java.io.BufferedOutputStream(
                                        getContentResolver().openOutputStream(uri)
                                )
                ) {
                    byte[] buf = new byte[64 * 1024];
                    int nRead;

                    while ((nRead = in.read(buf)) > 0) {
                        out.write(buf, 0, nRead);
                    }
                    out.flush();
                }

                return true;
            }

            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                    ),
                    "VibrationMonitor"
            );

            if (!dir.exists()) dir.mkdirs();

            java.io.File dest = new java.io.File(dir, displayName);

            try (
                    java.io.InputStream in =
                            new java.io.BufferedInputStream(
                                    new java.io.FileInputStream(source)
                            );
                    java.io.OutputStream out =
                            new java.io.BufferedOutputStream(
                                    new java.io.FileOutputStream(dest)
                            )
            ) {
                byte[] buf = new byte[64 * 1024];
                int nRead;

                while ((nRead = in.read(buf)) > 0) {
                    out.write(buf, 0, nRead);
                }
            }

            return true;

        } catch (Exception e) {
            return false;
        }
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
                        "%d,%.3f,%s,%s,%s,%s,%.6f,%.6f,%.3f,%s",
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

    private boolean isStopResumeUnit(String unit) {
        if (unit == null) return false;

        String u = unit.trim().toUpperCase(Locale.US);

        return u.equals("AUTO STOP")
                || u.equals("AUTO RESUME")
                || u.equals("LINE STOP")
                || u.equals("MANUAL STOP")
                || u.equals("MANUAL RESUME");
    }

    private String eventCategory(String unit) {
        return isStopResumeUnit(unit) ? "STOP_RESUME" : "PRODUCTION";
    }

    private ArrayList<EventRecord> filteredEvents(boolean stopResume) {
        ArrayList<EventRecord> out = new ArrayList<>();

        for (EventRecord r : sessionEvents) {
            if (isStopResumeUnit(r.unit) == stopResume) {
                out.add(r);
            }
        }

        return out;
    }

    private EventRecord topEvent(ArrayList<EventRecord> source) {
        EventRecord top = null;

        for (EventRecord r : source) {
            if (top == null || r.peak > top.peak) {
                top = r;
            }
        }

        return top;
    }

    private ArrayList<UnitAggregate> buildUnitAggregates() {
        LinkedHashMap<String, UnitAggregate> map = new LinkedHashMap<>();

        for (UnitSegment r : unitSegments) {
            if (isStopResumeUnit(r.unit)) {
                continue;
            }

            String key = blank(r.process) + " > " + blank(r.unit);
            UnitAggregate a = map.get(key);

            if (a == null) {
                a = new UnitAggregate();
                a.label = key;
                map.put(key, a);
            }

            a.unit = r.unit;
            a.count += r.impactCount;
            a.sampleCount += r.sampleCount;
            a.sum += r.sum;
            a.sumSq += r.sumSq;
            a.min = Math.min(a.min, r.min);
            a.maxPeak = Math.max(a.maxPeak, r.peak);
            a.durationSec += r.durationSec;
            a.segmentCount++;
        }

        return new ArrayList<>(map.values());
    }

    private String mcscUnitAtOffset(double offsetSec) {
        int idx = activeRecipeIndex(
                Math.round(Math.max(0.0, offsetSec) * 1000.0)
        );

        if (idx >= 0 && idx < recipe.size()) {
            return recipe.get(idx).name;
        }

        return "AFTER MCSC";
    }

    private void reclassifyProductionEventsByMcsc() {
        for (EventRecord r : sessionEvents) {
            if (!isStopResumeUnit(r.unit)) {
                r.unit = mcscUnitAtOffset(r.offsetSec);
            }
        }

        if (timeline != null) {
            timeline.setRecipe(recipe);
            timeline.refresh(
                    sessionEvents,
                    Math.max(1L, Math.round(recipeTotalSec()))
            );
        }
    }

    private int csvColumn(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (name.equals(header[i].trim())) return i;
        }
        return -1;
    }

    private void rebuildProductionUnitSummaryFromRaw() {
        if (continuousFile == null || !continuousFile.exists() || recipe.isEmpty()) {
            return;
        }

        final int size = recipe.size();

        long[] count = new long[size];
        long[] firstMs = new long[size];
        long[] lastMs = new long[size];
        double[] sum = new double[size];
        double[] sumSq = new double[size];
        double[] min = new double[size];
        double[] pk = new double[size];
        double[] mx = new double[size];
        double[] my = new double[size];
        double[] mz = new double[size];

        Arrays.fill(firstMs, -1L);
        Arrays.fill(lastMs, -1L);
        Arrays.fill(min, Double.POSITIVE_INFINITY);

        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        new java.io.FileInputStream(continuousFile),
                        java.nio.charset.StandardCharsets.UTF_8
                )
        )) {
            String headerLine = br.readLine();
            if (headerLine == null) return;

            String[] h = headerLine.split(",", -1);

            int processMsCol = csvColumn(h, "ProcessElapsedMs");
            int stateCol = csvColumn(h, "TimelineState");
            int processCol = csvColumn(h, "Process");
            int xCol = csvColumn(h, "X");
            int yCol = csvColumn(h, "Y");
            int zCol = csvColumn(h, "Z");
            int tCol = csvColumn(h, "Total");

            if (processMsCol < 0
                    || processCol < 0
                    || xCol < 0
                    || yCol < 0
                    || zCol < 0
                    || tCol < 0) {
                return;
            }

            String selectedProcess = String.valueOf(process.getSelectedItem());
            String line;

            while ((line = br.readLine()) != null) {
                String[] p = line.split(",", -1);

                int need = Math.max(
                        Math.max(processMsCol, processCol),
                        Math.max(
                                Math.max(xCol, yCol),
                                Math.max(zCol, tCol)
                        )
                );

                if (p.length <= need) continue;
                if (!selectedProcess.equals(p[processCol].trim())) continue;

                if (stateCol >= 0 && p.length > stateCol) {
                    String state = p[stateCol].trim();

                    if ("AUTO_HOLD".equals(state)
                            || "MANUAL_HOLD".equals(state)) {
                        continue;
                    }
                }

                try {
                    long processMs = Long.parseLong(p[processMsCol].trim());
                    int idx = activeRecipeIndex(processMs);

                    if (idx < 0 || idx >= size) continue;

                    double x = Math.abs(Double.parseDouble(p[xCol].trim()));
                    double y = Math.abs(Double.parseDouble(p[yCol].trim()));
                    double z = Math.abs(Double.parseDouble(p[zCol].trim()));
                    double t = Math.abs(Double.parseDouble(p[tCol].trim()));

                    if (firstMs[idx] < 0L) firstMs[idx] = processMs;
                    lastMs[idx] = processMs;

                    count[idx]++;
                    sum[idx] += t;
                    sumSq[idx] += t * t;
                    min[idx] = Math.min(min[idx], t);
                    pk[idx] = Math.max(pk[idx], t);
                    mx[idx] = Math.max(mx[idx], x);
                    my[idx] = Math.max(my[idx], y);
                    mz[idx] = Math.max(mz[idx], z);

                } catch (Exception ignored) {}
            }

        } catch (Exception ignored) {
            return;
        }

        int[] eventCount = new int[size];

        for (EventRecord r : sessionEvents) {
            if (isStopResumeUnit(r.unit)) continue;

            for (int i = 0; i < recipe.size(); i++) {
                if (recipe.get(i).name.equals(r.unit)) {
                    eventCount[i]++;
                    break;
                }
            }
        }

        unitSegments.clear();

        for (int i = 0; i < size; i++) {
            if (count[i] <= 0L) continue;

            UnitSegment u = new UnitSegment();
            u.process = String.valueOf(process.getSelectedItem());
            u.unit = recipe.get(i).name;
            u.durationSec = firstMs[i] >= 0L && lastMs[i] >= firstMs[i]
                    ? Math.max(0.10, (lastMs[i] - firstMs[i]) / 1000.0)
                    : recipe.get(i).seconds;
            u.sampleCount = count[i];
            u.sum = sum[i];
            u.sumSq = sumSq[i];
            u.avg = count[i] > 0L ? sum[i] / count[i] : 0.0;
            u.min = count[i] > 0L && Double.isFinite(min[i]) ? min[i] : 0.0;
            u.peak = pk[i];
            u.rms = Math.sqrt(sumSq[i] / Math.max(1L, count[i]));
            u.impactCount = eventCount[i];
            u.mx = mx[i];
            u.my = my[i];
            u.mz = mz[i];
            u.axis = u.mx >= u.my && u.mx >= u.mz
                    ? "X"
                    : (u.my >= u.mz ? "Y" : "Z");

            unitSegments.add(u);
        }
    }

    private void showMcscReviewDialog() {
        if (running) {
            Toast.makeText(
                    this,
                    "측정 종료 후 MCSC Timeline Review를 실행해주세요.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        EditText editor = new EditText(this);
        editor.setText(recipeToText());
        editor.setTextSize(14);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );

        TextView countLabel = tv(
                "MCSC UNIT COUNT : " + recipe.size(),
                12,
                Color.rgb(55, 75, 92)
        );
        countLabel.setTypeface(null, 1);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button minus = btn("- UNIT", Color.rgb(90, 105, 118));
        Button plus = btn("+ UNIT", Color.rgb(0, 135, 105));
        Button reset = btn("RESET 10", Color.rgb(67, 88, 108));

        minus.setTextSize(11);
        plus.setTextSize(11);
        reset.setTextSize(11);

        buttons.addView(minus, new LinearLayout.LayoutParams(0, dp(40), 1f));

        LinearLayout.LayoutParams plusLp2 =
                new LinearLayout.LayoutParams(0, dp(40), 1f);
        plusLp2.setMargins(dp(5), 0, dp(5), 0);
        buttons.addView(plus, plusLp2);

        buttons.addView(reset, new LinearLayout.LayoutParams(0, dp(40), 1f));

        minus.setOnClickListener(v -> changeMcscCount(editor, countLabel, -1));
        plus.setOnClickListener(v -> changeMcscCount(editor, countLabel, 1));
        reset.setOnClickListener(v -> resetMcsc10(editor, countLabel));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(4), dp(10), dp(6));

        TextView help = tv(
                "측정 후 실제 설비 진행시간에 맞게 Unit 이름/시간을 0.1초 단위로 수정하세요. "
                        + "적용 시 Production Impact의 Unit 위치와 Unit Summary를 RAW 데이터 기준으로 다시 계산합니다. "
                        + "원본 RAW/Event Blackbox는 변경하지 않습니다.",
                11,
                Color.rgb(80, 95, 108)
        );

        box.addView(help);
        box.addView(countLabel);
        box.addView(buttons);
        box.addView(
                editor,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(300)
                )
        );

        new android.app.AlertDialog.Builder(this)
                .setTitle("MCSC TIMELINE REVIEW")
                .setView(box)
                .setPositiveButton("APPLY & RECALCULATE", (d, w) -> {
                    ArrayList<RecipeUnit> parsed =
                            parseRecipe(editor.getText().toString());

                    if (parsed.isEmpty()) {
                        Toast.makeText(
                                this,
                                "MCSC 형식을 확인해주세요.",
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

                    reclassifyProductionEventsByMcsc();
                    rebuildProductionUnitSummaryFromRaw();

                    saveUnitSummaryCsv();
                    saveSessionCsv();
                    saveRunSummaryCsv();

                    Toast.makeText(
                            this,
                            "MCSC Timeline 재계산 완료 · 원본 RAW/Blackbox 보존",
                            Toast.LENGTH_LONG
                    ).show();

                    showSessionSummary();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private int mcscOrderOf(String unitName) {
        if (unitName == null) return Integer.MAX_VALUE;

        for (int i = 0; i < recipe.size(); i++) {
            if (unitName.equals(recipe.get(i).name)) return i;
        }

        return Integer.MAX_VALUE;
    }

    private String mcscRangeForUnit(String unitName) {
        double start = 0.0;

        for (RecipeUnit r : recipe) {
            double end = start + r.seconds;

            if (r.name != null && r.name.equals(unitName)) {
                return String.format(Locale.US, "%.1f-%.1fs", start, end);
            }

            start = end;
        }

        return "-";
    }

    private LinearLayout buildMcscVibrationTable(ArrayList<UnitAggregate> source) {
        ArrayList<UnitAggregate> rows = new ArrayList<>(source);

        Collections.sort(rows, (a, b) -> {
            int oa = mcscOrderOf(a.unit);
            int ob = mcscOrderOf(b.unit);
            if (oa != ob) return Integer.compare(oa, ob);
            return a.label.compareTo(b.label);
        });

        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setPadding(dp(4), dp(4), dp(4), dp(6));
        table.setBackground(bg(Color.rgb(247, 249, 251), 10));

        TextView guide = tv(
                "AVG=평균 · MIN=최소 · MAX=최대 · RMS=진동수준 · IMP=충격횟수 · SMP=샘플수",
                10,
                Color.rgb(90, 105, 118)
        );
        guide.setPadding(dp(4), dp(2), dp(4), dp(5));
        table.addView(guide);

        for (int i = 0; i < rows.size(); i++) {
            UnitAggregate a = rows.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(7), dp(5), dp(7), dp(5));
            row.setBackground(bg(
                    i % 2 == 0 ? Color.WHITE : Color.rgb(240, 245, 248),
                    7
            ));

            TextView line1 = tv(
                    String.format(
                            Locale.US,
                            "%s   MCSC %s   ACTIVE %.1fs",
                            a.unit,
                            mcscRangeForUnit(a.unit),
                            a.durationSec
                    ),
                    11,
                    Color.rgb(15, 38, 61)
            );
            line1.setTypeface(null, 1);
            line1.setPadding(0, 0, 0, dp(1));

            TextView line2 = tv(
                    String.format(
                            Locale.US,
                            "AVG %.3f   MIN %.3f   MAX %.3f   RMS %.3f   IMP %d   SMP %d",
                            a.avg(),
                            a.minValue(),
                            a.maxPeak,
                            a.avgRms(),
                            a.count,
                            a.sampleCount
                    ),
                    10,
                    Color.DKGRAY
            );
            line2.setTypeface(android.graphics.Typeface.MONOSPACE);
            line2.setPadding(0, 0, 0, 0);

            row.addView(line1);
            row.addView(line2);

            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rp.setMargins(0, 0, 0, dp(4));
            table.addView(row, rp);
        }

        return table;
    }

    private void showSessionSummary() {
        long now = SystemClock.elapsedRealtime();
        long durationSec = running
                ? (startMs > 0L ? Math.max(0L, (now - startMs) / 1000L) : 0L)
                : stoppedDurationSec;
        double sessionRms = n > 0L ? Math.sqrt(sumSq / n) : 0.0;

        String mainAxis =
                sessionMaxX >= sessionMaxY && sessionMaxX >= sessionMaxZ
                        ? "X"
                        : (sessionMaxY >= sessionMaxZ ? "Y" : "Z");

        ArrayList<EventRecord> productionEvents = filteredEvents(false);
        ArrayList<EventRecord> stopResumeEvents = filteredEvents(true);

        EventRecord top = topEvent(productionEvents);
        EventRecord topStopResume = topEvent(stopResumeEvents);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));

        TextView topCard = tv(
                top == null
                        ? "TOP PRODUCTION IMPACT : 없음"
                        : String.format(
                                Locale.US,
                                "TOP PRODUCTION IMPACT #%03d\n%s > %s\nPEAK %.3f m/s² · %s AXIS",
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
                        + "Impact  %d회 · Production %d · Stop/Resume %d\n"
                        + "최대 Peak  %.3f m/s²\n"
                        + "Session RMS  %.3f m/s²\n"
                        + "주 충격 방향  %s AXIS\n"
                        + "X / Y / Z Peak  %.3f / %.3f / %.3f m/s²\n"
                        + "SPEC  %.3f m/s²\n"
                        + "Timeline Correction  %s · %d회 · %.1fs",
                durationSec / 3600,
                (durationSec / 60) % 60,
                durationSec % 60,
                impactCount,
                productionEvents.size(),
                stopResumeEvents.size(),
                sessionPeak,
                sessionRms,
                mainAxis,
                sessionMaxX,
                sessionMaxY,
                sessionMaxZ,
                spec,
                autoCorrectionEnabled ? "ON" : "OFF",
                autoCorrectionCount,
                autoPausedTotalMs / 1000.0
        );

        box.addView(tv(summary, 15, Color.DKGRAY));

        if (!productionEvents.isEmpty()) {
            TextView chartTitle = tv(
                    "PRODUCTION EVENT PEAK COMPARISON",
                    13,
                    Color.rgb(15, 38, 61)
            );
            chartTitle.setTypeface(null, 1);
            box.addView(chartTitle);

            int eventChartHeight = Math.max(
                    92,
                    Math.min(245, productionEvents.size() * 58 + 28)
            );

            box.addView(
                    new SessionBarsView(this, productionEvents),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(eventChartHeight)
                    )
            );
        }

        if (!stopResumeEvents.isEmpty()) {
            TextView stopTitle = tv(
                    "STOP / RESUME IMPACTS · SEPARATE FROM UNIT RANKING",
                    13,
                    Color.rgb(155, 95, 25)
            );
            stopTitle.setTypeface(null, 1);
            box.addView(stopTitle);

            if (topStopResume != null) {
                TextView stopCard = tv(
                        String.format(
                                Locale.US,
                                "TOP STOP/RESUME #%03d · %s\nPEAK %.3f m/s² · %s AXIS",
                                topStopResume.no,
                                topStopResume.unit,
                                topStopResume.peak,
                                topStopResume.axis
                        ),
                        13,
                        Color.rgb(110, 75, 35)
                );
                stopCard.setBackground(bg(Color.rgb(252, 244, 229), 10));
                stopCard.setPadding(dp(8), dp(6), dp(8), dp(6));
                box.addView(stopCard);
            }

            int stopChartHeight = Math.max(
                    82,
                    Math.min(190, stopResumeEvents.size() * 52 + 24)
            );

            box.addView(
                    new SessionBarsView(this, stopResumeEvents),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(stopChartHeight)
                    )
            );
        }

        if (!unitSegments.isEmpty()) {
            ArrayList<UnitAggregate> aggregates = buildUnitAggregates();

            if (!aggregates.isEmpty()) {
                TextView vibrationTitle = tv(
                        "MCSC UNIT VIBRATION ANALYSIS · AVG / MIN / MAX / RMS",
                        13,
                        Color.rgb(15, 38, 61)
                );
                vibrationTitle.setTypeface(null, 1);
                box.addView(vibrationTitle);
                box.addView(buildMcscVibrationTable(aggregates));

                TextView unitTitle = tv(
                        "PRODUCTION UNIT RANKING · MAX / RMS / IMPACT / TIME",
                        13,
                        Color.rgb(15, 38, 61)
                );
                unitTitle.setTypeface(null, 1);
                box.addView(unitTitle);

                int unitChartHeight = Math.max(
                        108,
                        Math.min(420, aggregates.size() * 82 + 24)
                );

                box.addView(
                        new UnitSummaryView(this, aggregates),
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                dp(unitChartHeight)
                        )
                );
            }
        }

        Button mcscReview = btn(
                "MCSC TIMELINE REVIEW / CORRECTION",
                Color.rgb(67, 88, 108)
        );
        mcscReview.setTextSize(12);

        LinearLayout.LayoutParams reviewLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48)
                );
        reviewLp.setMargins(0, dp(8), 0, dp(4));

        box.addView(mcscReview, reviewLp);
        mcscReview.setOnClickListener(v -> showMcscReviewDialog());

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
                .setNeutralButton(
                        "DATA DOWNLOAD",
                        (d, w) -> showExportDialog()
                )
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
                else if (line.startsWith("Peak (CORE) : ")) peakValue = line.substring(14).trim();
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

    private String remoteJsonEscape(String value) {
        if (value == null) return "";

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private void refreshRemoteMetadata() {
        try {
            remoteLine = lineInput == null
                    ? "-"
                    : blank(lineInput.getText().toString().trim());

            remoteEquipment = equipmentInput == null
                    ? "-"
                    : blank(equipmentInput.getText().toString().trim());

            remoteProcess = process == null || process.getSelectedItem() == null
                    ? "-"
                    : String.valueOf(process.getSelectedItem());

            remoteMcscTotal = recipeTotalSec();

            StringBuilder m = new StringBuilder("[");
            for (int i = 0; i < recipe.size(); i++) {
                if (i > 0) m.append(",");

                RecipeUnit r = recipe.get(i);

                m.append("{\"name\":\"")
                        .append(remoteJsonEscape(r.name))
                        .append("\",\"sec\":")
                        .append(String.format(Locale.US, "%.3f", r.seconds))
                        .append("}");
            }
            m.append("]");
            remoteMcscJson = m.toString();

        } catch (Exception ignored) {}
    }

    private void refreshRemoteEventsJson() {
        try {
            StringBuilder b = new StringBuilder("[");
            int from = Math.max(0, sessionEvents.size() - 20);

            for (int i = from; i < sessionEvents.size(); i++) {
                if (b.length() > 1) b.append(",");

                EventRecord r = sessionEvents.get(i);

                b.append("{\"no\":")
                        .append(r.no)
                        .append(",\"t\":")
                        .append(String.format(Locale.US, "%.3f", r.offsetSec))
                        .append("}");
            }

            b.append("]");
            remoteEventsJson = b.toString();

        } catch (Exception ignored) {}
    }

    private void updateRemoteSnapshot(
            long now,
            float x,
            float y,
            float z,
            double t
    ) {
        remoteRunning = running;
        remoteCalibrating = calibrating;
        remotePaused = processPaused;
        remoteX = x;
        remoteY = y;
        remoteZ = z;
        remoteTotal = t;
        remotePeak = sessionPeak;
        remoteRms = n > 0L ? Math.sqrt(sumSq / n) : 0.0;
        remoteSpec = spec;

        remoteProcessSec = startMs > 0L
                ? (
                        isAutoMode()
                                ? getProcessElapsedMs(now) / 1000.0
                                : Math.max(0.0, (now - startMs) / 1000.0)
                )
                : 0.0;

        remoteUnit = currentTaggedUnit(now);
        remoteTimelineState = calibrating ? "CALIBRATING" : timelineState();

        refreshRemoteMetadata();

        if (now - remoteLastPointMs >= 50L) {
            remoteLastPointMs = now;

            synchronized (remotePoints) {
                remotePoints.addLast(new double[]{x, y, z, t});

                while (remotePoints.size() > 160) {
                    remotePoints.removeFirst();
                }
            }
        }
    }

    private String buildRemoteStateJson() {
        StringBuilder p = new StringBuilder("[");

        synchronized (remotePoints) {
            int i = 0;

            for (double[] q : remotePoints) {
                if (i++ > 0) p.append(",");

                p.append("[")
                        .append(String.format(Locale.US, "%.5f", q[0]))
                        .append(",")
                        .append(String.format(Locale.US, "%.5f", q[1]))
                        .append(",")
                        .append(String.format(Locale.US, "%.5f", q[2]))
                        .append(",")
                        .append(String.format(Locale.US, "%.5f", q[3]))
                        .append(",")
                        .append(String.format(Locale.US, "%.5f", q[3]))
                        .append("]");
            }
        }

        p.append("]");

        return "{"
                + "\"running\":" + remoteRunning + ","
                + "\"calibrating\":" + remoteCalibrating + ","
                + "\"paused\":" + remotePaused + ","
                + "\"line\":\"" + remoteJsonEscape(remoteLine) + "\","
                + "\"equipment\":\"" + remoteJsonEscape(remoteEquipment) + "\","
                + "\"process\":\"" + remoteJsonEscape(remoteProcess) + "\","
                + "\"unit\":\"" + remoteJsonEscape(remoteUnit) + "\","
                + "\"timelineState\":\"" + remoteJsonEscape(remoteTimelineState) + "\","
                + "\"x\":" + String.format(Locale.US, "%.6f", remoteX) + ","
                + "\"y\":" + String.format(Locale.US, "%.6f", remoteY) + ","
                + "\"z\":" + String.format(Locale.US, "%.6f", remoteZ) + ","
                + "\"total\":" + String.format(Locale.US, "%.6f", remoteTotal) + ","
                + "\"peak\":" + String.format(Locale.US, "%.6f", remotePeak) + ","
                + "\"rms\":" + String.format(Locale.US, "%.6f", remoteRms) + ","
                + "\"spec\":" + String.format(Locale.US, "%.6f", remoteSpec) + ","
                + "\"processSec\":" + String.format(Locale.US, "%.3f", remoteProcessSec) + ","
                + "\"mcscTotal\":" + String.format(Locale.US, "%.3f", remoteMcscTotal) + ","
                + "\"lastImpact\":\"" + remoteJsonEscape(remoteLastImpact) + "\","
                + "\"mcsc\":" + remoteMcscJson + ","
                + "\"events\":" + remoteEventsJson + ","
                + "\"points\":" + p
                + "}";
    }

    private void showRemoteMonitorDialog() {
        refreshRemoteMetadata();

        if (remoteServer == null) {
            remoteServer = new RemoteMonitorServer(this::buildRemoteStateJson);
        }

        if (!remoteServer.isRunning()) {
            boolean ok = remoteServer.start();

            if (!ok) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("REMOTE MONITOR")
                        .setMessage(
                                "Remote Monitor 서버를 시작하지 못했습니다.\n"
                                        + "Wi-Fi/Hotspot 상태를 확인한 뒤 다시 시도해주세요."
                        )
                        .setPositiveButton("확인", null)
                        .show();
                return;
            }
        }

        if (remoteButton != null) {
            remoteButton.setText("REMOTE ON");
            remoteButton.setBackground(bg(Color.rgb(0, 145, 105), 14));
        }

        java.util.List<String> urls = remoteServer.getAccessUrls();
        String primary = remoteServer.getPrimaryUrl();

        StringBuilder msg = new StringBuilder();
        msg.append("READ ONLY · 같은 Wi-Fi 또는 Hotspot에서 사용\n\n");

        if (urls.isEmpty()) {
            msg.append("IP 주소를 찾지 못했습니다.\n")
                    .append("두 폰을 같은 Wi-Fi/Hotspot에 연결한 뒤 다시 열어주세요.");
        } else {
            msg.append("다른 폰의 Chrome/Samsung Internet에서 아래 주소를 여세요.\n\n");

            for (String u : urls) {
                msg.append(u).append("\n");
            }
        }

        msg.append("\nAccess Code : ")
                .append(remoteServer.getToken())
                .append("\n\n실시간 화면은 보기 전용입니다.");

        android.app.AlertDialog dialog =
                new android.app.AlertDialog.Builder(this)
                        .setTitle("REMOTE MONITOR · LIVE")
                        .setMessage(msg.toString())
                        .setPositiveButton(
                                "COPY URL",
                                (d, w) -> {
                                    if (primary == null || primary.isEmpty()) return;

                                    android.content.ClipboardManager cm =
                                            (android.content.ClipboardManager)
                                                    getSystemService(CLIPBOARD_SERVICE);

                                    cm.setPrimaryClip(
                                            android.content.ClipData.newPlainText(
                                                    "Remote Monitor URL",
                                                    primary
                                            )
                                    );

                                    Toast.makeText(
                                            this,
                                            "Remote URL 복사 완료",
                                            Toast.LENGTH_SHORT
                                    ).show();
                                }
                        )
                        .setNeutralButton(
                                "STOP REMOTE",
                                (d, w) -> stopRemoteMonitor()
                        )
                        .setNegativeButton("닫기", null)
                        .create();

        dialog.show();
    }

    private void stopRemoteMonitor() {
        if (remoteServer != null) {
            remoteServer.stop();
        }

        if (remoteButton != null) {
            remoteButton.setText("REMOTE VIEW");
            remoteButton.setBackground(bg(Color.rgb(0, 125, 110), 14));
        }

        Toast.makeText(
                this,
                "Remote Monitor 종료",
                Toast.LENGTH_SHORT
        ).show();
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
        stopRemoteMonitor();
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
        double offsetSec;
        String line, equipment, process, unit, axis, base;
        double peak, rms, duration, mx, my, mz;
    }

    static class UnitSegment {
        String process = "-";
        String unit = "-";
        String axis = "-";
        long sampleCount = 0L;
        double durationSec = 0.0;
        double sum = 0.0;
        double sumSq = 0.0;
        double avg = 0.0;
        double min = 0.0;
        double peak = 0.0;
        double rms = 0.0;
        double mx = 0.0;
        double my = 0.0;
        double mz = 0.0;
        int impactCount = 0;
    }

    static class UnitAggregate {
        String label = "-";
        String unit = "-";
        int count = 0;
        int segmentCount = 0;
        long sampleCount = 0L;
        double sum = 0.0;
        double sumSq = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double maxPeak = 0.0;
        double durationSec = 0.0;

        double avg() {
            return sampleCount > 0L ? sum / sampleCount : 0.0;
        }

        double minValue() {
            return sampleCount > 0L && Double.isFinite(min) ? min : 0.0;
        }

        double avgRms() {
            return sampleCount > 0L
                    ? Math.sqrt(sumSq / sampleCount)
                    : 0.0;
        }
    }

    static class ImpactTimelineView extends View {
        private final ArrayList<EventRecord> data = new ArrayList<>();
        private final ArrayList<RecipeUnit> recipe = new ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double durationSec = 1.0;
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
                durationSec = Math.max(0.1, total);
            }

            invalidate();
        }

        void setDuration(double sec) {
            durationSec = Math.max(0.1, sec);
            invalidate();
        }

        void setProcessPosition(double sec, boolean isPaused) {
            processSec = Math.max(0.0, sec);
            paused = isPaused;
            invalidate();
        }

        void refresh(ArrayList<EventRecord> source, double sec) {
            data.clear();
            data.addAll(source);
            durationSec = Math.max(0.1, sec);
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

            double displayEnd = Math.max(0.1, durationSec);

            if (total > 0.0) {
                displayEnd = Math.max(displayEnd, total);

                if (processSec > total) {
                    double over = processSec - total;
                    double reserve = Math.max(0.5, Math.max(total * 0.12, over * 1.15));
                    displayEnd = Math.max(displayEnd, total + reserve);
                }
            } else {
                displayEnd = Math.max(displayEnd, processSec * 1.05);
            }

            if (!recipe.isEmpty() && total > 0.0) {
                double acc = 0.0;

                for (int i = 0; i < recipe.size(); i++) {
                    RecipeUnit r = recipe.get(i);
                    double start = acc;
                    acc += r.seconds;

                    float x1 = left + (right - left) * (float) (start / displayEnd);
                    float x2 = left + (right - left) * (float) (acc / displayEnd);

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

                float mcscEndX = left + (right - left) * (float) (total / displayEnd);

                if (processSec > total && displayEnd > total) {
                    p.setColor(Color.rgb(255, 238, 205));
                    c.drawRect(mcscEndX, top, right, bottom, p);

                    p.setColor(Color.rgb(210, 135, 25));
                    p.setStrokeWidth(2f);
                    c.drawLine(mcscEndX, top, mcscEndX, bottom, p);

                    p.setTextSize(11f);
                    p.setColor(Color.rgb(170, 100, 10));
                    String overText = String.format(
                            Locale.US,
                            "OVER +%.1fs",
                            Math.max(0.0, processSec - total)
                    );
                    c.drawText(
                            overText,
                            Math.min(
                                    right - p.measureText(overText) - 2f,
                                    mcscEndX + 4f
                            ),
                            top + 14f,
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
                            processSec / Math.max(0.1, displayEnd)
                    );

            p.setColor(
                    paused
                            ? Color.rgb(230, 150, 45)
                            : (total > 0.0 && processSec > total
                                    ? Color.rgb(230, 150, 45)
                                    : Color.rgb(0, 125, 110))
            );
            p.setStrokeWidth(4f);
            c.drawLine(px, top - 5f, px, bottom + 5f, p);

            for (EventRecord r : data) {
                float x = left + (right - left)
                        * (float) Math.min(
                                1.0,
                                r.offsetSec / Math.max(0.1, displayEnd)
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

            String endText = String.format(Locale.US, "%.1fs", displayEnd);
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
                                "AVG %.2f · MIN %.2f · MAX %.2f · RMS %.2f · %d events · %.1fs",
                                a.avg(),
                                a.minValue(),
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
