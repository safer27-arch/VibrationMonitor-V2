
package com.example.vibrationmonitor;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private TextView currentText;
    private TextView avgText;
    private TextView maxText;
    private TextView minText;
    private TextView alarmText;
    private TextView eventText;
    private TextView locationText;
    private TextView cameraText;
    private TextView axisSummaryText;
    private TextView directionText;
    private TextView samplingText;
    private long sampleWindowStartMs = 0L;
    private int sampleWindowCount = 0;
    private double samplingHz = 0.0;

    private android.location.LocationManager locationManager;
    private android.location.LocationListener locationListener;

    private double currentLatitude = 0.0;
    private double currentLongitude = 0.0;
    private boolean hasLocation = false;

    private double eventLatitude = 0.0;
    private double eventLongitude = 0.0;
    private boolean eventHasLocation = false;

    // 자동 사진 촬영
    private android.hardware.camera2.CameraDevice cameraDevice;
    private android.hardware.camera2.CameraCaptureSession cameraSession;
    private android.media.ImageReader imageReader;
    private android.os.HandlerThread cameraThread;
    private android.os.Handler cameraHandler;
    private String cameraId;
    private boolean cameraReady = false;

    private java.io.File photoCaptureTarget;
    private String eventPhotoFileName = null;

    private EditText thresholdInput;
    private EditText emailInput;
    private android.widget.Spinner buildingSpinner;
    private String selectedBuilding = "WA5";
    private String eventBuilding = "WA5";
    private VibrationGraph graph;

    private boolean measuring = false;

    private double sum = 0.0;
    private long count = 0;
    private double maxValue = 0.0;
    private double minValue = Double.MAX_VALUE;

    private double sumX=0, sumY=0, sumZ=0;
    private double maxX=0, maxY=0, maxZ=0;
    private double minX=Double.MAX_VALUE, minY=Double.MAX_VALUE, minZ=Double.MAX_VALUE;

    // 최근 3초 데이터
    private final ArrayDeque<DataPoint> preBuffer = new ArrayDeque<>();

    // SPEC 초과 이벤트 데이터
    private final ArrayList<DataPoint> eventBuffer = new ArrayList<>();

    private boolean eventRecording = false;
    private long lastThresholdExceededTime = 0L;

    private long eventStartMs = 0;
    private int eventCount = 0;

    // 중력 제거용 Low Pass Filter
    private float gravityX = 0;
    private float gravityY = 0;
    private float gravityZ = 0;

    private static final float ALPHA = 0.90f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sensorManager =
                (SensorManager) getSystemService(SENSOR_SERVICE);

        accelerometer =
                sensorManager.getDefaultSensor(
                        Sensor.TYPE_ACCELEROMETER
                );

        ScrollView scroll = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 25, 30, 30);
        root.setBackgroundColor(Color.rgb(244,247,250));

        TextView title = new TextView(this);
        title.setText("Vibration Monitor");
        title.setTextSize(28);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);

        TextView sensorStatus = new TextView(this);
        sensorStatus.setText(
                accelerometer != null
                        ? "가속도 센서 준비 완료"
                        : "가속도 센서를 찾을 수 없습니다."
        );
        sensorStatus.setTextSize(16);
        sensorStatus.setGravity(Gravity.CENTER);
        sensorStatus.setPadding(0, 5, 0, 10);

        locationText = new TextView(this);
        locationText.setText("GPS : 위치 확인 대기 중");
        locationText.setTextSize(15);
        locationText.setGravity(Gravity.CENTER);
        locationText.setTextColor(Color.DKGRAY);
        locationText.setPadding(0, 4, 0, 10);

        cameraText = new TextView(this);
        cameraText.setText("카메라 : 준비 중...");
        cameraText.setTextSize(15);
        cameraText.setGravity(Gravity.CENTER);
        cameraText.setTextColor(Color.DKGRAY);
        cameraText.setPadding(0, 2, 0, 10);

        // 사용 건물 선택
        TextView buildingLabel = new TextView(this);
        buildingLabel.setText("사용 건물");
        buildingLabel.setTextSize(18);
        buildingLabel.setTextColor(Color.DKGRAY);
        buildingLabel.setPadding(0, 12, 0, 4);

        buildingSpinner = new android.widget.Spinner(this);

        String[] buildings = {
                "WA3",
                "WA5",
                "WA6",
                "WA7",
                "WA5+6+7",
                "WA8",
                "WA9",
                "WA8+9"
        };

        android.widget.ArrayAdapter<String> buildingAdapter =
                new android.widget.ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_item,
                        buildings
                );

        buildingAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );

        buildingSpinner.setAdapter(buildingAdapter);

        selectedBuilding =
                getSharedPreferences(
                        "VibrationSettings",
                        MODE_PRIVATE
                ).getString(
                        "selected_building",
                        "WA5"
                );

        int buildingIndex = 1;
        for (int i = 0; i < buildings.length; i++) {
            if (buildings[i].equals(selectedBuilding)) {
                buildingIndex = i;
                break;
            }
        }

        buildingSpinner.setSelection(buildingIndex);

        buildingSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id
                    ) {
                        selectedBuilding =
                                parent.getItemAtPosition(position).toString();

                        getSharedPreferences(
                                "VibrationSettings",
                                MODE_PRIVATE
                        ).edit()
                                .putString(
                                        "selected_building",
                                        selectedBuilding
                                )
                                .apply();
                    }

                    @Override
                    public void onNothingSelected(
                            android.widget.AdapterView<?> parent
                    ) {
                    }
                }
        );

        root.addView(buildingLabel);
        root.addView(
                buildingSpinner,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        // 실시간 그래프
        graph = new VibrationGraph(this);

        root.addView(
                graph,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        420
                )
        );

        currentText = createValueText(
                "현재값 : 0.000 m/s²"
        );
        avgText = createValueText(
                "평균값 : 0.000 m/s²"
        );
        maxText = createValueText(
                "최대값 : 0.000 m/s²"
        );
        minText = createValueText(
                "최소값 : 0.000 m/s²"
        );

        axisSummaryText = createValueText(
                "X  0.000   Y  0.000   Z  0.000   TOTAL  0.000 m/s²"
        );
        axisSummaryText.setTextSize(18);
        directionText = createValueText("주 진동 방향 : -");
        directionText.setTextSize(18);
        samplingText = createValueText("Sampling : 0.0 Hz");
        samplingText.setTextSize(15);

        TextView thresholdLabel =
                createValueText("SPEC / Threshold");

        thresholdInput = new EditText(this);
        float savedSpec = getSharedPreferences("VibrationSettings", MODE_PRIVATE).getFloat("spec", 2.0f);
        thresholdInput.setText(String.valueOf(savedSpec));
        thresholdInput.setTextSize(20);
        thresholdInput.setHint("예: 2.0");

        thresholdInput.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER |
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        );

        alarmText = new TextView(this);
        alarmText.setText("상태 : 정상");
        alarmText.setTextSize(22);
        alarmText.setGravity(Gravity.CENTER);
        alarmText.setTextColor(Color.rgb(0, 130, 0));
        alarmText.setPadding(10, 20, 10, 20);

        eventText = new TextView(this);
        eventText.setText(
                "이벤트 : 0회\n최근 3초 데이터 대기 중"
        );
        eventText.setTextSize(16);
        eventText.setGravity(Gravity.CENTER);
        eventText.setTextColor(Color.DKGRAY);
        eventText.setPadding(10, 10, 10, 20);

        Button specSaveButton = new Button(this);
        specSaveButton.setText("SPEC 저장 / 적용");
        specSaveButton.setOnClickListener(v -> {
            try {
                double value = Double.parseDouble(thresholdInput.getText().toString().trim());
                if (value <= 0) throw new Exception();
                getSharedPreferences("VibrationSettings", MODE_PRIVATE)
                        .edit().putFloat("spec", (float)value).apply();
                graph.setThreshold(value);
                android.widget.Toast.makeText(
                        this,
                        String.format(Locale.US, "SPEC %.2f m/s² 저장 완료", value),
                        android.widget.Toast.LENGTH_SHORT
                ).show();
            } catch (Exception e) {
                float oldValue = getSharedPreferences("VibrationSettings", MODE_PRIVATE)
                        .getFloat("spec", 2.0f);
                thresholdInput.setText(String.valueOf(oldValue));
                android.widget.Toast.makeText(
                        this,
                        "0보다 큰 숫자를 입력하세요.",
                        android.widget.Toast.LENGTH_SHORT
                ).show();
            }
        });

        TextView emailLabel = createValueText("알람 수신 이메일");

        emailInput = new EditText(this);
        emailInput.setText(
                getSharedPreferences(
                        "VibrationSettings",
                        MODE_PRIVATE
                ).getString(
                        "notify_email",
                        ""
                )
        );
        emailInput.setHint("예: name@gmail.com");
        emailInput.setTextSize(18);
        emailInput.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        );

        Button emailSaveButton = new Button(this);
        emailSaveButton.setText("이메일 저장");

        emailSaveButton.setOnClickListener(v -> {

            String email =
                    emailInput.getText()
                            .toString()
                            .trim();

            getSharedPreferences(
                    "VibrationSettings",
                    MODE_PRIVATE
            )
            .edit()
            .putString(
                    "notify_email",
                    email
            )
            .apply();

            android.widget.Toast.makeText(
                    this,
                    email.isEmpty()
                            ? "이메일 주소가 비어 있습니다."
                            : "알람 이메일 저장 완료",
                    android.widget.Toast.LENGTH_SHORT
            ).show();
        });

        Button startButton = new Button(this);
        startButton.setText("측정 시작");

        Button stopButton = new Button(this);
        stopButton.setText("측정 중지");

        Button resetButton = new Button(this);
        resetButton.setText("값 초기화");

        Button csvButton = new Button(this);
        csvButton.setText("저장된 CSV 파일");

        Button processShockButton = new Button(this);
        processShockButton.setText("공정 충격 분석 / PROCESS SHOCK");
        processShockButton.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, ProcessShockActivity.class)
        ));

        Button historyButton = new Button(this);
        historyButton.setText("진동 이력 관리");
        historyButton.setOnClickListener(v -> {
            android.content.Intent intent =
                    new android.content.Intent(this, HistoryActivity.class);
            intent.putExtra("spec", getThreshold());
            startActivity(intent);
        });
        csvButton.setOnClickListener(v -> {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "VibrationData");
            java.io.File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                new android.app.AlertDialog.Builder(this).setTitle("저장된 CSV 파일").setMessage("저장된 CSV 파일이 없습니다.").setPositiveButton("확인", null).show();
                return;
            }
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            for (java.io.File f : files) if (f.getName().endsWith(".csv")) names.add(f.getName());
            if (names.isEmpty()) {
                new android.app.AlertDialog.Builder(this).setTitle("저장된 CSV 파일").setMessage("저장된 CSV 파일이 없습니다.").setPositiveButton("확인", null).show();
                return;
            }
            java.util.Collections.sort(names, java.util.Collections.reverseOrder());
            new android.app.AlertDialog.Builder(this).setTitle("저장된 CSV 파일").setItems(names.toArray(new String[0]), (dialog, which) -> showCsvGraph(new java.io.File(dir, names.get(which)))).setNegativeButton("닫기", null).show();
        });

        startButton.setOnClickListener(
                v -> startMeasurement()
        );

        stopButton.setOnClickListener(
                v -> stopMeasurement()
        );

        resetButton.setOnClickListener(
                v -> resetValues()
        );

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        header.setPadding(12, 8, 12, 8);
        header.setBackgroundColor(Color.WHITE);
        title.setTextColor(Color.rgb(25,45,65));
        title.setGravity(Gravity.CENTER); title.setSingleLine(true); title.setText("VIBRATION MONITOR"); title.setTextSize(22);
        header.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(com.example.vibrationmonitor.R.drawable.lges_logo);
        logo.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams logoLp0=new LinearLayout.LayoutParams((int)(170*getResources().getDisplayMetrics().density),(int)(34*getResources().getDisplayMetrics().density)); logoLp0.gravity=Gravity.CENTER; header.addView(logo,logoLp0);
        root.addView(header);
        root.addView(sensorStatus);
        root.addView(locationText);
        root.addView(cameraText);

        // 그래프가 제목 바로 아래 보이도록 순서 조정
        root.removeView(graph);
        root.addView(
                graph,
                4,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        600
                )
        );

        root.addView(currentText);
        root.addView(avgText);
        root.addView(maxText);
        root.addView(minText);
        root.addView(axisSummaryText);
        root.addView(directionText);
        root.addView(samplingText);

        LinearLayout graphModes = new LinearLayout(this);
        graphModes.setOrientation(LinearLayout.HORIZONTAL);
        String[] modeNames = {"ALL","X","Y","Z","TOTAL"};
        for (String m : modeNames) {
            Button b = new Button(this);
            b.setText(m);
            b.setTextSize(12);
            b.setAllCaps(false);
            b.setOnClickListener(v -> graph.setMode(m));
            graphModes.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(graphModes);
        root.addView(thresholdLabel);
        root.addView(thresholdInput);
        root.addView(specSaveButton);

        root.addView(emailLabel);
        root.addView(emailInput);
        root.addView(emailSaveButton);

        // ===== Telegram 설정 =====
        android.widget.TextView telegramTitle = new android.widget.TextView(this);
        telegramTitle.setText("Telegram 자동 알림");
        telegramTitle.setTextSize(18f);
        telegramTitle.setPadding(0, 24, 0, 8);

        android.widget.EditText telegramTokenInput = new android.widget.EditText(this);
        telegramTokenInput.setHint("Telegram Bot Token");
        telegramTokenInput.setSingleLine(true);
        telegramTokenInput.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        android.widget.EditText telegramChatIdInput = new android.widget.EditText(this);
        telegramChatIdInput.setHint("Telegram Chat ID 여러 개 입력 (쉼표 또는 줄바꿈 구분)");
        telegramChatIdInput.setSingleLine(false);
        telegramChatIdInput.setMinLines(2);

        android.widget.Button telegramSaveButton = new android.widget.Button(this);
        telegramSaveButton.setText("Telegram 저장");

        android.widget.Button telegramTestButton = new android.widget.Button(this);
        telegramTestButton.setText("Telegram 테스트 전송");

        android.widget.TextView telegramStatus = new android.widget.TextView(this);
        telegramStatus.setText("Telegram : 설정 필요");
        telegramStatus.setPadding(0, 6, 0, 12);

        android.content.SharedPreferences telegramPrefs =
                getSharedPreferences("TelegramSettings", MODE_PRIVATE);

        telegramTokenInput.setText(telegramPrefs.getString("bot_token", ""));
        telegramChatIdInput.setText(telegramPrefs.getString("chat_id", ""));

        if (!telegramTokenInput.getText().toString().trim().isEmpty()
                && !telegramChatIdInput.getText().toString().trim().isEmpty()) {
            telegramStatus.setText("Telegram : 설정 저장됨");
        }

        telegramSaveButton.setOnClickListener(v -> {
            String token = telegramTokenInput.getText().toString().trim();
            String chatId = telegramChatIdInput.getText().toString().trim();

            if (token.isEmpty() || chatId.isEmpty()) {
                android.widget.Toast.makeText(this,
                        "Bot Token과 Chat ID를 입력해주세요.",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            telegramPrefs.edit()
                    .putString("bot_token", token)
                    .putString("chat_id", chatId)
                    .apply();

            telegramStatus.setText("Telegram : 설정 저장됨");
            android.widget.Toast.makeText(this,
                    "Telegram 설정을 저장했습니다.",
                    android.widget.Toast.LENGTH_SHORT).show();
        });

        telegramTestButton.setOnClickListener(v -> {
            String token = telegramTokenInput.getText().toString().trim();
            String chatId = telegramChatIdInput.getText().toString().trim();

            if (token.isEmpty() || chatId.isEmpty()) {
                android.widget.Toast.makeText(this,
                        "먼저 Bot Token과 Chat ID를 입력해주세요.",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            telegramStatus.setText("Telegram : 테스트 전송 중...");

            TelegramSender.sendMessageMulti(
                    token,
                    chatId,
                    "✅ Vibration Monitor 연결 테스트\nTelegram 다중 자동 알림 연결이 정상입니다.",
                    (success, message) -> runOnUiThread(() -> {
                        if (success) {
                            telegramStatus.setText("Telegram : 테스트 전송 성공");
                            android.widget.Toast.makeText(this,
                                    "Telegram 테스트 메시지 전송 성공",
                                    android.widget.Toast.LENGTH_LONG).show();
                        } else {
                            telegramStatus.setText("Telegram : 전송 실패 (" + message + ")");
                            android.widget.Toast.makeText(this,
                                    "Telegram 전송 실패: " + message,
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    })
            );
        });

        root.addView(telegramTitle);
        root.addView(telegramTokenInput);
        root.addView(telegramChatIdInput);
        root.addView(telegramSaveButton);
        root.addView(telegramTestButton);
        root.addView(telegramStatus);

        root.addView(alarmText);
        root.addView(eventText);
        root.addView(startButton);
        root.addView(stopButton);
        root.addView(resetButton);
        root.addView(csvButton);
        root.addView(processShockButton);\n        root.addView(historyButton);

        // ===== V2.1 INDUSTRIAL DASHBOARD =====
        final float den = getResources().getDisplayMetrics().density;
        final int navy=Color.rgb(18,35,52), ink=Color.rgb(28,42,55), muted=Color.rgb(103,119,133);
        final int green=Color.rgb(0,153,112), blue=Color.rgb(35,105,170), red=Color.rgb(205,68,72);
        final int purple=Color.rgb(111,71,170), soft=Color.rgb(238,243,247);

        root.removeAllViews();
        root.setPadding((int)(12*den),(int)(12*den),(int)(12*den),(int)(70*den));
        root.setBackgroundColor(Color.rgb(241,245,248));

        android.graphics.drawable.GradientDrawable headerBg=new android.graphics.drawable.GradientDrawable();
        headerBg.setColor(navy); headerBg.setCornerRadius(22*den);
        header.setBackground(headerBg);
        header.setPadding((int)(18*den),(int)(12*den),(int)(12*den),(int)(12*den));
        title.setText("VIBRATION MONITOR"); title.setTextSize(20); title.setTextColor(Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        logo.setBackgroundColor(Color.WHITE); logo.setPadding((int)(8*den),(int)(5*den),(int)(8*den),(int)(5*den));
        LinearLayout.LayoutParams finalHeaderLp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,(int)(100*den)); finalHeaderLp.setMargins(0,(int)(4*den),0,0); root.addView(header,finalHeaderLp);

        TextView sub=new TextView(this);
        sub.setText("REAL-TIME 3-AXIS CONDITION MONITORING"); sub.setTextSize(11); sub.setTextColor(muted);
        sub.setLetterSpacing(0.08f); sub.setPadding((int)(4*den),(int)(10*den),0,(int)(8*den)); root.addView(sub);

        LinearLayout statusPanel=new LinearLayout(this); statusPanel.setOrientation(LinearLayout.VERTICAL);
        statusPanel.setPadding((int)(14*den),(int)(10*den),(int)(14*den),(int)(10*den));
        android.graphics.drawable.GradientDrawable statusBg=new android.graphics.drawable.GradientDrawable();
        statusBg.setColor(Color.WHITE); statusBg.setCornerRadius(18*den); statusBg.setStroke((int)(1*den),Color.rgb(220,228,234));
        statusPanel.setBackground(statusBg);
        TextView[] statuses={sensorStatus,locationText,cameraText,samplingText};
        for(TextView st:statuses){st.setGravity(Gravity.START);st.setTextSize(13);st.setTextColor(muted);st.setPadding(0,(int)(2*den),0,(int)(3*den));}
        sensorStatus.setTextColor(ink);
        statusPanel.addView(sensorStatus);statusPanel.addView(locationText);statusPanel.addView(cameraText);statusPanel.addView(samplingText);
        LinearLayout.LayoutParams statusLp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0,0,0,(int)(10*den));root.addView(statusPanel,statusLp);

        LinearLayout buildingRow=new LinearLayout(this);buildingRow.setOrientation(LinearLayout.HORIZONTAL);buildingRow.setGravity(Gravity.CENTER_VERTICAL);
        buildingLabel.setText("AREA");buildingLabel.setTextSize(12);buildingLabel.setTextColor(muted);buildingLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        buildingLabel.setPadding((int)(4*den),0,(int)(12*den),0);buildingRow.addView(buildingLabel);
        buildingRow.addView(buildingSpinner,new LinearLayout.LayoutParams(0,(int)(48*den),1f));root.addView(buildingRow);

        LinearLayout graphCard=new LinearLayout(this);graphCard.setOrientation(LinearLayout.VERTICAL);
        graphCard.setPadding((int)(10*den),(int)(10*den),(int)(10*den),(int)(8*den));
        android.graphics.drawable.GradientDrawable graphBg=new android.graphics.drawable.GradientDrawable();
        graphBg.setColor(Color.WHITE);graphBg.setCornerRadius(20*den);graphBg.setStroke((int)(1*den),Color.rgb(218,226,233));graphCard.setBackground(graphBg);
        TextView graphTitle=new TextView(this);graphTitle.setText("LIVE VIBRATION");graphTitle.setTextSize(13);graphTitle.setTextColor(ink);
        graphTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);graphTitle.setPadding((int)(5*den),(int)(2*den),0,(int)(6*den));graphCard.addView(graphTitle);
        graphCard.addView(graph,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,(int)(230*den)));
        graphModes.setPadding(0,(int)(6*den),0,0);
        for(int i=0;i<graphModes.getChildCount();i++){Button bm=(Button)graphModes.getChildAt(i);bm.setTextSize(11);bm.setTextColor(ink);bm.setAllCaps(false);bm.setMinHeight((int)(40*den));bm.setBackgroundTintList(android.content.res.ColorStateList.valueOf(soft));}
        graphCard.addView(graphModes);
        LinearLayout.LayoutParams graphLp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        graphLp.setMargins(0,(int)(8*den),0,(int)(10*den));root.addView(graphCard,graphLp);

        LinearLayout totalCard=new LinearLayout(this);totalCard.setOrientation(LinearLayout.VERTICAL);
        totalCard.setPadding((int)(18*den),(int)(12*den),(int)(18*den),(int)(12*den));
        android.graphics.drawable.GradientDrawable totalBg=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,new int[]{Color.rgb(27,52,72),Color.rgb(37,83,105)});
        totalBg.setCornerRadius(20*den);totalCard.setBackground(totalBg);
        TextView totalLabel=new TextView(this);totalLabel.setText("TOTAL VIBRATION");totalLabel.setTextSize(12);totalLabel.setTextColor(Color.rgb(190,211,223));totalLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);totalCard.addView(totalLabel);
        currentText.setTextSize(27);currentText.setTextColor(Color.WHITE);currentText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);currentText.setPadding(0,(int)(2*den),0,(int)(2*den));totalCard.addView(currentText);
        LinearLayout totalStats=new LinearLayout(this);totalStats.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] ts={avgText,maxText,minText};for(TextView tv:ts){tv.setTextSize(12);tv.setTextColor(Color.rgb(220,232,239));tv.setGravity(Gravity.CENTER);tv.setPadding((int)(2*den),(int)(5*den),(int)(2*den),(int)(5*den));totalStats.addView(tv,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));}
        totalCard.addView(totalStats);root.addView(totalCard);

        LinearLayout axisCard=new LinearLayout(this);axisCard.setOrientation(LinearLayout.VERTICAL);axisCard.setPadding((int)(14*den),(int)(12*den),(int)(14*den),(int)(12*den));
        android.graphics.drawable.GradientDrawable axisBg=new android.graphics.drawable.GradientDrawable();axisBg.setColor(Color.WHITE);axisBg.setCornerRadius(20*den);axisBg.setStroke((int)(1*den),Color.rgb(218,226,233));axisCard.setBackground(axisBg);
        axisSummaryText.setTextSize(14); axisSummaryText.setTypeface(android.graphics.Typeface.MONOSPACE);axisSummaryText.setTextColor(ink);axisSummaryText.setTypeface(android.graphics.Typeface.MONOSPACE);axisSummaryText.setPadding(0,0,0,(int)(5*den));
        directionText.setTextSize(15);directionText.setTextColor(purple);directionText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);directionText.setGravity(Gravity.CENTER);
        axisCard.addView(axisSummaryText);axisCard.addView(directionText);
        LinearLayout.LayoutParams axisLp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);axisLp.setMargins(0,(int)(10*den),0,(int)(10*den));root.addView(axisCard,axisLp);

        LinearLayout specCard=new LinearLayout(this);specCard.setOrientation(LinearLayout.VERTICAL);specCard.setPadding((int)(14*den),(int)(10*den),(int)(14*den),(int)(12*den));
        android.graphics.drawable.GradientDrawable specBg=new android.graphics.drawable.GradientDrawable();specBg.setColor(Color.WHITE);specBg.setCornerRadius(18*den);specBg.setStroke((int)(1*den),Color.rgb(218,226,233));specCard.setBackground(specBg);
        thresholdLabel.setTextSize(12);thresholdLabel.setTextColor(muted);thresholdInput.setTextSize(19);thresholdInput.setTextColor(ink);thresholdInput.setSingleLine(true);
        specSaveButton.setText("SPEC APPLY");specSaveButton.setTextColor(Color.WHITE);specSaveButton.setTextSize(13);specSaveButton.setAllCaps(false);specSaveButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(purple));
        specCard.addView(thresholdLabel);LinearLayout specRow=new LinearLayout(this);specRow.setOrientation(LinearLayout.HORIZONTAL);specRow.setGravity(Gravity.CENTER_VERTICAL);
        specRow.addView(thresholdInput,new LinearLayout.LayoutParams(0,(int)(50*den),1f));LinearLayout.LayoutParams applyLp=new LinearLayout.LayoutParams((int)(125*den),(int)(50*den));applyLp.setMargins((int)(8*den),0,0,0);specRow.addView(specSaveButton,applyLp);specCard.addView(specRow);root.addView(specCard);

        alarmText.setTextSize(17);alarmText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);alarmText.setGravity(Gravity.CENTER);
        eventText.setTextSize(13);eventText.setTextColor(muted);root.addView(alarmText);root.addView(eventText);

        LinearLayout row1=new LinearLayout(this);row1.setOrientation(LinearLayout.HORIZONTAL);LinearLayout row2=new LinearLayout(this);row2.setOrientation(LinearLayout.HORIZONTAL);
        Button[] mains={startButton,stopButton,resetButton,csvButton,historyButton};for(Button mb:mains){mb.setTextColor(Color.WHITE);mb.setTextSize(14);mb.setAllCaps(false);mb.setMinHeight((int)(52*den));}
        startButton.setText("▶  측정 시작");stopButton.setText("■  측정 중지");resetButton.setText("↻  값 초기화");csvButton.setText("CSV 데이터");historyButton.setText("진동 이력 관리");
        startButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(green));stopButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(red));resetButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(91,108,123)));csvButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(blue));historyButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(navy));
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,(int)(54*den),1f);half.setMargins((int)(3*den),(int)(3*den),(int)(3*den),(int)(3*den));
        row1.addView(startButton,new LinearLayout.LayoutParams(half));row1.addView(stopButton,new LinearLayout.LayoutParams(half));row2.addView(resetButton,new LinearLayout.LayoutParams(half));row2.addView(csvButton,new LinearLayout.LayoutParams(half));root.addView(row1);root.addView(row2);
        LinearLayout.LayoutParams historyLp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,(int)(54*den));historyLp.setMargins((int)(3*den),(int)(3*den),(int)(3*den),(int)(8*den));root.addView(historyButton,historyLp);

        Button settingsToggle=new Button(this);settingsToggle.setText("⚙  알림 / Telegram 설정");settingsToggle.setTextColor(ink);settingsToggle.setTextSize(13);settingsToggle.setAllCaps(false);settingsToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(225,232,237)));
        LinearLayout settingsPanel=new LinearLayout(this);settingsPanel.setOrientation(LinearLayout.VERTICAL);settingsPanel.setPadding((int)(12*den),(int)(8*den),(int)(12*den),(int)(12*den));settingsPanel.setVisibility(View.GONE);
        settingsPanel.addView(emailLabel);settingsPanel.addView(emailInput);settingsPanel.addView(emailSaveButton);settingsPanel.addView(telegramTitle);settingsPanel.addView(telegramTokenInput);settingsPanel.addView(telegramChatIdInput);settingsPanel.addView(telegramSaveButton);settingsPanel.addView(telegramTestButton);settingsPanel.addView(telegramStatus);
        Button[] sbs={emailSaveButton,telegramSaveButton,telegramTestButton};for(Button sb:sbs){sb.setTextColor(Color.WHITE);sb.setTextSize(13);sb.setAllCaps(false);sb.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(75,94,110)));}
        settingsToggle.setOnClickListener(v->{boolean show=settingsPanel.getVisibility()!=View.VISIBLE;settingsPanel.setVisibility(show?View.VISIBLE:View.GONE);settingsToggle.setText(show?"▲  알림 / Telegram 설정 닫기":"⚙  알림 / Telegram 설정");});
        root.addView(settingsToggle);root.addView(settingsPanel);

        scroll.addView(root);
        setContentView(scroll);

        // GPS -> Camera sequential permission flow
        setupLocation();
    }

    private void setupCamera() {

        if (
                android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(
                        android.Manifest.permission.CAMERA
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            cameraText.setText("카메라 : 권한 필요");

            requestPermissions(
                    new String[] {
                            android.Manifest.permission.CAMERA
                    },
                    1002
            );
            return;
        }

        openEventCamera();
    }

    private void startCameraThread() {

        if (cameraThread != null) return;

        cameraThread =
                new android.os.HandlerThread(
                        "VibrationCamera"
                );

        cameraThread.start();

        cameraHandler =
                new android.os.Handler(
                        cameraThread.getLooper()
                );
    }

    private void openEventCamera() {

        if (cameraDevice != null) return;

        runOnUiThread(() ->
                cameraText.setText("카메라 : 준비 중...")
        );

        startCameraThread();

        try {
            android.hardware.camera2.CameraManager manager =
                    (android.hardware.camera2.CameraManager)
                            getSystemService(CAMERA_SERVICE);

            cameraId = null;

            for (String id : manager.getCameraIdList()) {

                android.hardware.camera2.CameraCharacteristics c =
                        manager.getCameraCharacteristics(id);

                Integer facing =
                        c.get(
                                android.hardware.camera2.CameraCharacteristics
                                        .LENS_FACING
                        );

                if (
                        facing != null &&
                        facing ==
                                android.hardware.camera2.CameraCharacteristics
                                        .LENS_FACING_BACK
                ) {
                    cameraId = id;
                    break;
                }
            }

            if (cameraId == null) {
                return;
            }

            android.hardware.camera2.CameraCharacteristics c =
                    manager.getCameraCharacteristics(cameraId);

            android.hardware.camera2.params.StreamConfigurationMap map =
                    c.get(
                            android.hardware.camera2.CameraCharacteristics
                                    .SCALER_STREAM_CONFIGURATION_MAP
                    );

            android.util.Size chosen =
                    new android.util.Size(1280, 720);

            if (map != null) {

                android.util.Size[] sizes =
                        map.getOutputSizes(
                                android.graphics.ImageFormat.JPEG
                        );

                if (sizes != null && sizes.length > 0) {

                    chosen = sizes[0];

                    long targetArea = 1280L * 720L;
                    long bestDiff = Long.MAX_VALUE;

                    for (android.util.Size size : sizes) {

                        long area =
                                (long) size.getWidth() *
                                (long) size.getHeight();

                        long diff =
                                Math.abs(area - targetArea);

                        if (diff < bestDiff) {
                            chosen = size;
                            bestDiff = diff;
                        }
                    }
                }
            }

            if (imageReader != null) {
                imageReader.close();
            }

            imageReader =
                    android.media.ImageReader.newInstance(
                            chosen.getWidth(),
                            chosen.getHeight(),
                            android.graphics.ImageFormat.JPEG,
                            2
                    );

            imageReader.setOnImageAvailableListener(
                    reader -> {

                        android.media.Image image = null;

                        try {
                            image = reader.acquireLatestImage();

                            if (
                                    image == null ||
                                    photoCaptureTarget == null
                            ) {
                                return;
                            }

                            java.nio.ByteBuffer buffer =
                                    image.getPlanes()[0]
                                            .getBuffer();

                            byte[] bytes =
                                    new byte[buffer.remaining()];

                            buffer.get(bytes);

                            try (
                                    java.io.FileOutputStream out =
                                            new java.io.FileOutputStream(
                                                    photoCaptureTarget
                                            )
                            ) {
                                out.write(bytes);
                                out.flush();
                            }

                            final java.io.File savedPhoto =
                                    photoCaptureTarget;

                            runOnUiThread(() -> {
                                if (
                                        savedPhoto != null &&
                                        savedPhoto.exists() &&
                                        savedPhoto.length() > 0
                                ) {
                                    cameraText.setText(
                                            "카메라 : 촬영 완료"
                                    );
                                } else {
                                    cameraText.setText(
                                            "카메라 : 파일 저장 실패"
                                    );
                                }
                            });

                        } catch (Exception e) {

                            runOnUiThread(() ->
                                    cameraText.setText(
                                            "카메라 : 파일 저장 오류"
                                    )
                            );

                        } finally {

                            if (image != null) {
                                image.close();
                            }

                            photoCaptureTarget = null;
                        }
                    },
                    cameraHandler
            );

            if (
                    android.os.Build.VERSION.SDK_INT >= 23 &&
                    checkSelfPermission(
                            android.Manifest.permission.CAMERA
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return;
            }

            manager.openCamera(
                    cameraId,
                    new android.hardware.camera2.CameraDevice.StateCallback() {

                        @Override
                        public void onOpened(
                                android.hardware.camera2.CameraDevice camera
                        ) {
                            cameraDevice = camera;

                            try {
                                java.util.ArrayList<android.view.Surface>
                                        surfaces =
                                        new java.util.ArrayList<>();

                                surfaces.add(
                                        imageReader.getSurface()
                                );

                                camera.createCaptureSession(
                                        surfaces,
                                        new android.hardware.camera2
                                                .CameraCaptureSession
                                                .StateCallback() {

                                            @Override
                                            public void onConfigured(
                                                    android.hardware.camera2
                                                            .CameraCaptureSession
                                                            session
                                            ) {
                                                cameraSession = session;
                                                cameraReady = true;

                                                runOnUiThread(() ->
                                                        cameraText.setText(
                                                                "카메라 : 준비 완료"
                                                        )
                                                );
                                            }

                                            @Override
                                            public void onConfigureFailed(
                                                    android.hardware.camera2
                                                            .CameraCaptureSession
                                                            session
                                            ) {
                                                cameraReady = false;

                                                runOnUiThread(() ->
                                                        cameraText.setText(
                                                                "카메라 : 준비 실패"
                                                        )
                                                );
                                            }
                                        },
                                        cameraHandler
                                );

                            } catch (Exception ignored) {
                                cameraReady = false;
                            }
                        }

                        @Override
                        public void onDisconnected(
                                android.hardware.camera2.CameraDevice camera
                        ) {
                            cameraReady = false;
                            camera.close();
                            cameraDevice = null;
                        }

                        @Override
                        public void onError(
                                android.hardware.camera2.CameraDevice camera,
                                int error
                        ) {
                            cameraReady = false;
                            camera.close();
                            cameraDevice = null;
                        }
                    },
                    cameraHandler
            );

        } catch (Exception ignored) {
            cameraReady = false;
        }
    }

    private void captureEventPhoto() {
        captureEventPhotoWithRetry(0);
    }

    private void captureEventPhotoWithRetry(int retry) {

        if (
                !cameraReady ||
                cameraDevice == null ||
                cameraSession == null ||
                imageReader == null
        ) {

            runOnUiThread(() ->
                    cameraText.setText(
                            "카메라 : 촬영 준비 대기..."
                    )
            );

            if (retry < 10) {

                if (cameraHandler == null) {
                    openEventCamera();
                }

                new android.os.Handler(
                        android.os.Looper.getMainLooper()
                ).postDelayed(
                        () -> captureEventPhotoWithRetry(retry + 1),
                        300
                );

            } else {

                runOnUiThread(() ->
                        cameraText.setText(
                                "카메라 : 촬영 실패(준비 안됨)"
                        )
                );
            }

            return;
        }

        try {

            java.io.File dir =
                    new java.io.File(
                            getExternalFilesDir(null),
                            "VibrationData"
                    );

            if (!dir.exists()) {
                dir.mkdirs();
            }

            eventPhotoFileName =
                    "photo_" +
                    new java.text.SimpleDateFormat(
                            "yyyyMMdd_HHmmss_SSS",
                            Locale.US
                    ).format(new java.util.Date()) +
                    ".jpg";

            photoCaptureTarget =
                    new java.io.File(
                            dir,
                            eventPhotoFileName
                    );

            runOnUiThread(() ->
                    cameraText.setText("카메라 : 촬영 중...")
            );

            android.hardware.camera2.CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(
                            android.hardware.camera2.CameraDevice
                                    .TEMPLATE_STILL_CAPTURE
                    );

            builder.addTarget(
                    imageReader.getSurface()
            );

            builder.set(
                    android.hardware.camera2.CaptureRequest.CONTROL_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO
            );

            builder.set(
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                    android.hardware.camera2.CaptureRequest
                            .CONTROL_AF_MODE_CONTINUOUS_PICTURE
            );

            builder.set(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CaptureRequest
                            .CONTROL_AE_MODE_ON
            );

            cameraSession.capture(
                    builder.build(),
                    new android.hardware.camera2.CameraCaptureSession
                            .CaptureCallback() {

                        @Override
                        public void onCaptureCompleted(
                                android.hardware.camera2.CameraCaptureSession session,
                                android.hardware.camera2.CaptureRequest request,
                                android.hardware.camera2.TotalCaptureResult result
                        ) {
                            runOnUiThread(() ->
                                    cameraText.setText(
                                            "카메라 : 사진 처리 중..."
                                    )
                            );
                        }

                        @Override
                        public void onCaptureFailed(
                                android.hardware.camera2.CameraCaptureSession session,
                                android.hardware.camera2.CaptureRequest request,
                                android.hardware.camera2.CaptureFailure failure
                        ) {
                            runOnUiThread(() ->
                                    cameraText.setText(
                                            "카메라 : 촬영 실패"
                                    )
                            );
                        }
                    },
                    cameraHandler
            );

        } catch (Exception e) {

            eventPhotoFileName = null;
            photoCaptureTarget = null;

            runOnUiThread(() ->
                    cameraText.setText(
                            "카메라 : 촬영 오류"
                    )
            );
        }
    }

    private void closeEventCamera() {

        cameraReady = false;

        try {
            if (cameraSession != null) {
                cameraSession.close();
            }
        } catch (Exception ignored) {}

        cameraSession = null;

        try {
            if (cameraDevice != null) {
                cameraDevice.close();
            }
        } catch (Exception ignored) {}

        cameraDevice = null;

        try {
            if (imageReader != null) {
                imageReader.close();
            }
        } catch (Exception ignored) {}

        imageReader = null;

        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void setupLocation() {

        locationManager =
                (android.location.LocationManager)
                        getSystemService(LOCATION_SERVICE);

        locationListener =
                new android.location.LocationListener() {
                    @Override
                    public void onLocationChanged(android.location.Location location) {
                        updateLocation(location);
                    }
                };

        if (
                android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                    new String[] {
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    1001
            );

            locationText.setText("GPS : 위치 권한 허용 필요");
        } else {
            startLocationUpdates();
            setupCamera();
        }
    }

    private void startLocationUpdates() {

        if (locationManager == null) return;

        if (
                android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return;
        }

        try {
            android.location.Location last = null;

            if (locationManager.isProviderEnabled(
                    android.location.LocationManager.GPS_PROVIDER)) {

                locationManager.requestLocationUpdates(
                        android.location.LocationManager.GPS_PROVIDER,
                        1000L,
                        0.5f,
                        locationListener
                );

                last = locationManager.getLastKnownLocation(
                        android.location.LocationManager.GPS_PROVIDER
                );
            }

            if (locationManager.isProviderEnabled(
                    android.location.LocationManager.NETWORK_PROVIDER)) {

                locationManager.requestLocationUpdates(
                        android.location.LocationManager.NETWORK_PROVIDER,
                        1000L,
                        0.5f,
                        locationListener
                );

                if (last == null) {
                    last = locationManager.getLastKnownLocation(
                            android.location.LocationManager.NETWORK_PROVIDER
                    );
                }
            }

            if (last != null) {
                updateLocation(last);
            } else {
                locationText.setText("GPS : 위치 신호 검색 중...");
            }

        } catch (Exception e) {
            locationText.setText("GPS : 위치 확인 오류");
        }
    }

    private void updateLocation(android.location.Location location) {

        if (location == null) return;

        currentLatitude = location.getLatitude();
        currentLongitude = location.getLongitude();
        hasLocation = true;

        locationText.setText(
                String.format(
                        Locale.US,
                        "GPS : %.6f, %.6f",
                        currentLatitude,
                        currentLongitude
                )
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == 1001) {
            if (
                    grantResults.length > 0 &&
                    grantResults[0] ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                startLocationUpdates();
            } else {
                locationText.setText("GPS : 위치 권한 거부됨");
            }
            setupCamera();
        }

        if (requestCode == 1002) {
            if (
                    grantResults.length > 0 &&
                    grantResults[0] ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                cameraText.setText("카메라 : 권한 허용됨");
                openEventCamera();
            } else {
                cameraText.setText("카메라 : 권한 거부됨");
                android.widget.Toast.makeText(
                        this,
                        "사진 자동 촬영을 사용하려면 카메라 권한이 필요합니다.",
                        android.widget.Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    private void saveEventLocation(java.io.File csvFile) {

        if (csvFile == null) return;

        java.io.File meta =
                new java.io.File(
                        csvFile.getParentFile(),
                        csvFile.getName().replace(".csv", ".gps")
                );

        try (
                java.io.PrintWriter out =
                        new java.io.PrintWriter(
                                new java.io.FileWriter(meta)
                        )
        ) {
            out.println(
                    String.format(
                            Locale.US,
                            "spec=%.3f",
                            getThreshold()
                    )
            );

            out.println(
                    "building=" +
                    (eventBuilding == null
                            ? "미지정"
                            : eventBuilding)
            );

            if (eventHasLocation) {
                out.println(
                        String.format(
                                Locale.US,
                                "latitude=%.8f",
                                eventLatitude
                        )
                );
                out.println(
                        String.format(
                                Locale.US,
                                "longitude=%.8f",
                                eventLongitude
                        )
                );
            } else {
                out.println("latitude=");
                out.println("longitude=");
            }

            out.println(
                    "photo=" +
                    (eventPhotoFileName == null
                            ? ""
                            : eventPhotoFileName)
            );

        } catch (Exception ignored) {
        }
    }

    private TextView createValueText(String text) {

        TextView view = new TextView(this);

        view.setText(text);
        view.setTextSize(19);
        view.setTextColor(Color.DKGRAY);
        view.setPadding(10, 12, 10, 12);

        return view;
    }

    private void startMeasurement() {

        if (accelerometer == null) {

            alarmText.setText("센서 없음");
            alarmText.setTextColor(Color.RED);

            return;
        }

        measuring = true;

        sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
        );

        alarmText.setText("상태 : 측정 중");
        alarmText.setTextColor(Color.rgb(0, 130, 0));
    }

    private void stopMeasurement() {

        measuring = false;

        sensorManager.unregisterListener(this);

        alarmText.setText("상태 : 정지");
        alarmText.setTextColor(Color.DKGRAY);
    }

    private void resetValues() {

        sum = 0.0;
        count = 0;

        maxValue = 0.0;
        minValue = Double.MAX_VALUE;
        sumX=sumY=sumZ=0; maxX=maxY=maxZ=0;
        minX=minY=minZ=Double.MAX_VALUE;
        sampleWindowStartMs=0; sampleWindowCount=0; samplingHz=0;

        preBuffer.clear();
        eventBuffer.clear();

        eventRecording = false;
        eventCount = 0;

        graph.clear();

        currentText.setText(
                "현재값 : 0.000 m/s²"
        );

        avgText.setText(
                "평균값 : 0.000 m/s²"
        );

        maxText.setText(
                "최대값 : 0.000 m/s²"
        );

        minText.setText(
                "최소값 : 0.000 m/s²"
        );

        alarmText.setText("상태 : 정상");
        alarmText.setTextColor(Color.rgb(0, 130, 0));

        eventText.setText(
                "이벤트 : 0회\n최근 3초 데이터 대기 중"
        );
    }

    @Override
    public void onSensorChanged(SensorEvent event) {

        if (!measuring) {
            return;
        }

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        /*
         * Low-pass filter로 중력 성분 추정
         */
        gravityX =
                ALPHA * gravityX +
                (1.0f - ALPHA) * x;

        gravityY =
                ALPHA * gravityY +
                (1.0f - ALPHA) * y;

        gravityZ =
                ALPHA * gravityZ +
                (1.0f - ALPHA) * z;

        /*
         * 중력 성분 제거
         */
        float linearX = x - gravityX;
        float linearY = y - gravityY;
        float linearZ = z - gravityZ;

        /*
         * 3축 합성 진동값
         */
        double vibration =
                Math.sqrt(
                        linearX * linearX +
                        linearY * linearY +
                        linearZ * linearZ
                );

        long now =
                SystemClock.elapsedRealtime();

        DataPoint point =
                new DataPoint(
                        now,
                        vibration,
                        linearX,
                        linearY,
                        linearZ
                );

        /*
         * 최근 3초 버퍼에 저장
         */
        preBuffer.addLast(point);

        while (
                !preBuffer.isEmpty() &&
                now - preBuffer.peekFirst().timeMs > 3000
        ) {
            preBuffer.removeFirst();
        }

        /*
         * 평균/최대/최소
         */
        sum += vibration;
        count++;

        if (vibration > maxValue) {
            maxValue = vibration;
        }

        if (vibration < minValue) {
            minValue = vibration;
        }

        double average = sum / count;

        double axAbs=Math.abs(linearX), ayAbs=Math.abs(linearY), azAbs=Math.abs(linearZ);
        sumX += axAbs; sumY += ayAbs; sumZ += azAbs;
        if(axAbs>maxX) maxX=axAbs; if(ayAbs>maxY) maxY=ayAbs; if(azAbs>maxZ) maxZ=azAbs;
        if(axAbs<minX) minX=axAbs; if(ayAbs<minY) minY=ayAbs; if(azAbs<minZ) minZ=azAbs;
        String mainAxis = axAbs>=ayAbs && axAbs>=azAbs ? "X" : (ayAbs>=azAbs ? "Y" : "Z");
        axisSummaryText.setText(String.format(Locale.US,
                "          CURRENT     AVG       MAX       MIN\n" +
                "X       %7.3f   %7.3f   %7.3f   %7.3f\n" +
                "Y       %7.3f   %7.3f   %7.3f   %7.3f\n" +
                "Z       %7.3f   %7.3f   %7.3f   %7.3f\n" +
                "TOTAL   %7.3f   %7.3f   %7.3f   %7.3f m/s²",
                linearX,sumX/count,maxX,minX, linearY,sumY/count,maxY,minY, linearZ,sumZ/count,maxZ,minZ, vibration,average,maxValue,minValue));
        directionText.setText("주 진동 방향 : " + mainAxis + "축");
        if(sampleWindowStartMs==0) sampleWindowStartMs=now;
        sampleWindowCount++;
        long sampleElapsed=now-sampleWindowStartMs;
        if(sampleElapsed>=1000){ samplingHz=sampleWindowCount*1000.0/sampleElapsed; sampleWindowCount=0; sampleWindowStartMs=now; }
        samplingText.setText(String.format(Locale.US,"Sampling : %.1f Hz",samplingHz));

        currentText.setText(
                String.format(
                        Locale.US,
                        "현재값 : %.3f m/s²",
                        vibration
                )
        );

        avgText.setText(
                String.format(
                        Locale.US,
                        "평균값 : %.3f m/s²",
                        average
                )
        );

        maxText.setText(
                String.format(
                        Locale.US,
                        "최대값 : %.3f m/s²",
                        maxValue
                )
        );

        minText.setText(
                String.format(
                        Locale.US,
                        "최소값 : %.3f m/s²",
                        minValue
                )
        );

        double threshold = getThreshold();

        graph.setThreshold(threshold);
        graph.addValues(linearX, linearY, linearZ, vibration);

        /*
         * SPEC 초과 이벤트 시작
         */
        if (
                vibration >= threshold &&
                !eventRecording
        ) {

            eventRecording = true;
            eventStartMs = now;
            lastThresholdExceededTime = now;

            eventLatitude = currentLatitude;
            eventLongitude = currentLongitude;
            eventHasLocation = hasLocation;
            eventBuilding =
                    selectedBuilding == null ||
                    selectedBuilding.trim().isEmpty()
                            ? "미지정"
                            : selectedBuilding.trim();

            // SPEC 최초 초과 순간 자동 사진 촬영
            captureEventPhoto();

            eventBuffer.clear();

            /*
             * 초과 전 최근 3초 데이터 복사
             */
            eventBuffer.addAll(preBuffer);

            eventCount++;

            alarmText.setText(
                    String.format(
                            Locale.US,
                            "⚠ SPEC 초과 : %.3f",
                            vibration
                    )
            );

            alarmText.setTextColor(Color.RED);

            eventText.setText(
                    "이벤트 : " + eventCount +
                    "회\n초과 후 3초 데이터 기록 중..."
            );
        }

        /*
         * SPEC 초과 후 3초 데이터
         */
        if (eventRecording) {

            if (
                    eventBuffer.isEmpty() ||
                    eventBuffer.get(eventBuffer.size() - 1).timeMs
                            != point.timeMs
            ) {
                eventBuffer.add(point);
            }

            if (point.value >= threshold) {
                lastThresholdExceededTime = now;
            }

            long elapsed =
                    now - lastThresholdExceededTime;

            if (elapsed >= 3000) {

                eventRecording = false;

                    java.util.ArrayList<float[]> csvValues = new java.util.ArrayList<>();
                    for (DataPoint dp : eventBuffer) csvValues.add(new float[]{dp.x, dp.y, dp.z, (float)dp.value});
                    java.io.File csvFile = CsvSaver.saveXYZ(this, csvValues);
                    saveEventLocation(csvFile);

                    openEventEmail(
                            csvFile,
                            new java.util.ArrayList<>(eventBuffer)
                    );

                eventText.setText(
                        "이벤트 : " + eventCount +
                        "회\n최근 이벤트 데이터 : " +
                        eventBuffer.size() +
                        "개 확보 완료"
                );

                                // ===== Telegram event bundle: 상세정보 + 사진 + CSV =====
                android.content.SharedPreferences tg =
                        getSharedPreferences("TelegramSettings", MODE_PRIVATE);

                String tgToken =
                        tg.getString("bot_token", "").trim();
                String tgChatId =
                        tg.getString("chat_id", "").trim();

                if (!tgToken.isEmpty() && !tgChatId.isEmpty()) {

                    java.util.ArrayList<DataPoint> telegramData =
                            new java.util.ArrayList<>(eventBuffer);

                    double tgSum = 0.0;
                    double tgMax = 0.0;
                    double tgMin = Double.MAX_VALUE;

                    double tgMaxX=0,tgMaxY=0,tgMaxZ=0;
                    for (DataPoint dp : telegramData) {
                        tgSum += dp.value;
                        if (dp.value > tgMax) tgMax = dp.value;
                        if (dp.value < tgMin) tgMin = dp.value;
                        tgMaxX=Math.max(tgMaxX,Math.abs(dp.x));
                        tgMaxY=Math.max(tgMaxY,Math.abs(dp.y));
                        tgMaxZ=Math.max(tgMaxZ,Math.abs(dp.z));
                    }
                    String tgMainAxis = tgMaxX>=tgMaxY && tgMaxX>=tgMaxZ ? "X" : (tgMaxY>=tgMaxZ ? "Y" : "Z");

                    double tgAvg =
                            telegramData.isEmpty()
                                    ? 0.0
                                    : tgSum / telegramData.size();

                    if (tgMin == Double.MAX_VALUE) {
                        tgMin = 0.0;
                    }

                    double tgSpec = getThreshold();
                    double tgOver =
                            Math.max(0.0, tgMax - tgSpec);

                    String tgTime =
                            new java.text.SimpleDateFormat(
                                    "yyyy-MM-dd HH:mm:ss",
                                    Locale.US
                            ).format(new java.util.Date());

                    String tgGps =
                            eventHasLocation
                                    ? String.format(
                                            Locale.US,
                                            "%.6f, %.6f",
                                            eventLatitude,
                                            eventLongitude
                                    )
                                    : "기록 없음";

                    String tgCaption =
                            "🚨 Vibration SPEC 초과 알람\n\n" +
                            "이벤트 : " + eventCount + "회\n" +
                            "건물 : " + eventBuilding + "\n" +
                            "발생시간 : " + tgTime + "\n" +
                            String.format(
                                    Locale.US,
                                    "MAX : %.3f m/s²\n",
                                    tgMax
                            ) +
                            String.format(Locale.US,"X MAX : %.3f m/s²\n",tgMaxX) +
                            String.format(Locale.US,"Y MAX : %.3f m/s²\n",tgMaxY) +
                            String.format(Locale.US,"Z MAX : %.3f m/s²\n",tgMaxZ) +
                            "주 진동 방향 : " + tgMainAxis + "축\n" +
                            String.format(Locale.US,"Sampling : %.1f Hz\n",samplingHz) +
                            String.format(
                                    Locale.US,
                                    "AVG : %.3f m/s²\n",
                                    tgAvg
                            ) +
                            String.format(
                                    Locale.US,
                                    "MIN : %.3f m/s²\n",
                                    tgMin
                            ) +
                            String.format(
                                    Locale.US,
                                    "SPEC : %.3f m/s²\n",
                                    tgSpec
                            ) +
                            String.format(
                                    Locale.US,
                                    "SPEC 초과량 : %.3f m/s²\n",
                                    tgOver
                            ) +
                            "GPS : " + tgGps + "\n" +
                            "데이터 수 : " + telegramData.size() + "개";

                    java.io.File tgPhotoFile = null;

                    if (
                            eventPhotoFileName != null &&
                            csvFile != null &&
                            csvFile.getParentFile() != null
                    ) {
                        tgPhotoFile =
                                new java.io.File(
                                        csvFile.getParentFile(),
                                        eventPhotoFileName
                                );
                    }

                    java.io.File tgGraphFile =
                            createTelegramEventGraph(
                                    csvFile,
                                    telegramData
                            );

                    final java.io.File tgMapFile =
                            (eventHasLocation &&
                             csvFile != null &&
                             csvFile.getParentFile() != null)
                            ? TelegramMapMaker.create(
                                    csvFile.getParentFile(),
                                    csvFile.getName().replace(".csv", ""),
                                    eventLatitude,
                                    eventLongitude,
                                    eventBuilding
                              )
                            : null;

                    TelegramSender.sendEventBundleMulti(
                            tgToken,
                            tgChatId,
                            tgCaption,
                            tgPhotoFile,
                            tgGraphFile,
                            csvFile,
                            (success, message) ->
                                    runOnUiThread(() -> {
                                        if (success) {

                                            if (tgMapFile != null) {
                                                TelegramSender.sendMapPhotoMulti(
                                                        tgToken,
                                                        tgChatId,
                                                        tgMapFile,
                                                        "📍 같은 이벤트의 LGES " + eventBuilding + " GPS 위치",
                                                        (mapOk, mapMsg) ->
                                                                runOnUiThread(() ->
                                                                        android.widget.Toast.makeText(
                                                                                this,
                                                                                mapOk
                                                                                        ? "Telegram 위치 맵 전송 성공"
                                                                                        : "Telegram 위치 맵 전송 실패 : " + mapMsg,
                                                                                android.widget.Toast.LENGTH_SHORT
                                                                        ).show()
                                                                )
                                                );
                                            }

                                            android.widget.Toast.makeText(
                                                    this,
                                                    "Telegram 이벤트 묶음 전송 성공",
                                                    android.widget.Toast.LENGTH_SHORT
                                            ).show();
                                        } else {
                                            android.widget.Toast.makeText(
                                                    this,
                                                    "Telegram 이벤트 묶음 전송 실패: " + message,
                                                    android.widget.Toast.LENGTH_LONG
                                            ).show();
                                        }
                                    })
                    );
                }

                alarmText.setText(
                        "상태 : 이벤트 기록 완료"
                );

                alarmText.setTextColor(
                        Color.rgb(200, 100, 0)
                );
            }

        } else {

            if (vibration < threshold) {

                alarmText.setText(
                        "상태 : 정상"
                );

                alarmText.setTextColor(
                        Color.rgb(0, 130, 0)
                );
            }
        }
    }


    private java.io.File createTelegramEventGraph(java.io.File csvFile, java.util.ArrayList<DataPoint> data) {
        if(csvFile==null||data==null||data.isEmpty())return null;
        try{
            final int width=1200,height=700,left=100,right=1150,top=70,bottom=610;
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE);
            android.graphics.Paint grid=new android.graphics.Paint(1);grid.setColor(android.graphics.Color.LTGRAY);grid.setStrokeWidth(2f);
            android.graphics.Paint specP=new android.graphics.Paint(1);specP.setColor(android.graphics.Color.RED);specP.setStrokeWidth(4f);specP.setTextSize(27f);
            android.graphics.Paint text=new android.graphics.Paint(1);text.setColor(android.graphics.Color.DKGRAY);text.setTextSize(28f);
            android.graphics.Paint[] ps=new android.graphics.Paint[4];int[] cs={android.graphics.Color.rgb(0,130,220),android.graphics.Color.rgb(0,170,110),android.graphics.Color.rgb(230,145,20),android.graphics.Color.rgb(95,55,180)};
            for(int k=0;k<4;k++){ps[k]=new android.graphics.Paint(1);ps[k].setColor(cs[k]);ps[k].setStyle(android.graphics.Paint.Style.STROKE);ps[k].setStrokeWidth(k==3?6f:4f);}
            double spec=getThreshold(),maxY=Math.max(3.0,spec*1.5);for(DataPoint d:data)maxY=Math.max(maxY,Math.max(d.value,Math.max(Math.abs(d.x),Math.max(Math.abs(d.y),Math.abs(d.z))))*1.15);
            for(int i=0;i<=4;i++){float y=top+(bottom-top)*i/4f;canvas.drawLine(left,y,right,y,grid);canvas.drawText(String.format(Locale.US,"%.1f",maxY*(4-i)/4.0),10,y+10,text);}
            float sy=bottom-(float)(spec/maxY*(bottom-top));canvas.drawLine(left,sy,right,sy,specP);canvas.drawText(String.format(Locale.US,"SPEC %.2f",spec),left+10,Math.max(top+30,sy-10),specP);
            android.graphics.Path[] paths={new android.graphics.Path(),new android.graphics.Path(),new android.graphics.Path(),new android.graphics.Path()};int n=data.size();
            for(int i=0;i<n;i++){DataPoint d=data.get(i);double[] vv={Math.abs(d.x),Math.abs(d.y),Math.abs(d.z),d.value};float x=left+(right-left)*(n<=1?0f:i/(float)(n-1));for(int k=0;k<4;k++){float y=bottom-(float)(vv[k]/maxY*(bottom-top));if(i==0)paths[k].moveTo(x,y);else paths[k].lineTo(x,y);}}
            for(int k=0;k<4;k++)canvas.drawPath(paths[k],ps[k]);canvas.drawText("X / Y / Z / TOTAL",left,40,text);canvas.drawText("-3s",left,665,text);canvas.drawText("0s",590,665,text);canvas.drawText("+3s",1080,665,text);
            java.io.File f=new java.io.File(csvFile.getParentFile(),csvFile.getName().replace(".csv","_graph.png"));try(java.io.FileOutputStream out=new java.io.FileOutputStream(f)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();return f;
        }catch(Exception e){return null;}
    }

    private void openEventEmail(
            java.io.File csvFile,
            java.util.ArrayList<DataPoint> data
    ) {

        String email =
                getSharedPreferences(
                        "VibrationSettings",
                        MODE_PRIVATE
                ).getString(
                        "notify_email",
                        ""
                );

        if (email == null || email.trim().isEmpty()) {
            return;
        }

        if (data == null || data.isEmpty()) {
            return;
        }

        double sumValue = 0.0;
        double max = 0.0;
        double min = Double.MAX_VALUE;

        for (DataPoint dp : data) {

            sumValue += dp.value;

            if (dp.value > max) {
                max = dp.value;
            }

            if (dp.value < min) {
                min = dp.value;
            }
        }

        double avg =
                sumValue / data.size();

        double spec =
                getThreshold();

        double over =
                Math.max(
                        0.0,
                        max - spec
                );

        String time =
                new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.US
                ).format(
                        new java.util.Date()
                );

        String gps =
                eventHasLocation
                        ? String.format(
                                Locale.US,
                                "%.6f, %.6f",
                                eventLatitude,
                                eventLongitude
                        )
                        : "기록 없음";

        String photo =
                eventPhotoFileName == null
                        ? "없음"
                        : eventPhotoFileName;

        String csv =
                csvFile == null
                        ? "없음"
                        : csvFile.getName();

        String subject =
                String.format(
                        Locale.US,
                        "[Vibration Alert] SPEC %.2f 초과 / MAX %.3f",
                        spec,
                        max
                );

        String body =
                "Vibration Monitor 이벤트 알람\n\n" +
                "발생시간 : " + time + "\n" +
                String.format(
                        Locale.US,
                        "MAX : %.3f m/s²\n",
                        max
                ) +
                String.format(
                        Locale.US,
                        "AVG : %.3f m/s²\n",
                        avg
                ) +
                String.format(
                        Locale.US,
                        "MIN : %.3f m/s²\n",
                        min
                ) +
                String.format(
                        Locale.US,
                        "SPEC : %.3f m/s²\n",
                        spec
                ) +
                String.format(
                        Locale.US,
                        "SPEC 초과량 : %.3f m/s²\n",
                        over
                ) +
                "GPS : " + gps + "\n" +
                "CSV : " + csv + "\n" +
                "사진 : " + photo + "\n";

        try {

            android.content.Intent intent =
                    new android.content.Intent(
                            android.content.Intent.ACTION_SENDTO
                    );

            intent.setData(
                    android.net.Uri.parse(
                            "mailto:" +
                            android.net.Uri.encode(
                                    email.trim()
                            )
                    )
            );

            intent.putExtra(
                    android.content.Intent.EXTRA_SUBJECT,
                    subject
            );

            intent.putExtra(
                    android.content.Intent.EXTRA_TEXT,
                    body
            );

            startActivity(intent);

        } catch (Exception e) {

            android.widget.Toast.makeText(
                    this,
                    "사용 가능한 이메일 앱을 찾지 못했습니다.",
                    android.widget.Toast.LENGTH_LONG
            ).show();
        }
    }

    private double getThreshold() {

        try {

            return Double.parseDouble(
                    thresholdInput
                            .getText()
                            .toString()
                            .trim()
            );

        } catch (Exception e) {

            return getSharedPreferences("VibrationSettings", MODE_PRIVATE)
                    .getFloat("spec", 2.0f);
        }
    }

    @Override
    public void onAccuracyChanged(
            Sensor sensor,
            int accuracy
    ) {
    }

    @Override
    protected void onPause() {

        super.onPause();

        if (measuring) {

            sensorManager.unregisterListener(this);
        }

        closeEventCamera();
    }

    @Override
    protected void onResume() {

        super.onResume();

        if (
                measuring &&
                accelerometer != null
        ) {

            sensorManager.registerListener(
                    this,
                    accelerometer,
                    SensorManager.SENSOR_DELAY_GAME
            );
        }

        if (
                android.os.Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(
                        android.Manifest.permission.CAMERA
                ) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            openEventCamera();
        }
    }

    /*
     * 데이터 1개
     */
    static class DataPoint {

        long timeMs;

        double value;

        float x;
        float y;
        float z;

        DataPoint(
                long timeMs,
                double value,
                float x,
                float y,
                float z
        ) {

            this.timeMs = timeMs;
            this.value = value;

            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /*
     * 실시간 그래프
     */

    private void showCsvGraph(java.io.File file) {
        java.util.ArrayList<Double> data = new java.util.ArrayList<>();

        try {
            java.io.BufferedReader br =
                    new java.io.BufferedReader(new java.io.FileReader(file));

            String line;
            while ((line = br.readLine()) != null) {
                String[] a = line.split(",");
                if (a.length >= 2) {
                    try {
                        data.add(Double.parseDouble(a[1].trim()));
                    } catch (Exception ignored) {}
                }
            }
            br.close();
        } catch (Exception e) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("CSV 읽기 오류")
                    .setMessage(e.getMessage())
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }

        if (data.isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(file.getName())
                    .setMessage("표시할 데이터가 없습니다.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }

        double sum = 0;
        double max = -Double.MAX_VALUE;
        double min = Double.MAX_VALUE;

        for (double v : data) {
            sum += v;
            if (v > max) max = v;
            if (v < min) min = v;
        }

        double avg = sum / data.size();

        android.widget.LinearLayout box =
                new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);

        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        android.widget.TextView info =
                new android.widget.TextView(this);

        info.setText(
                "파일 : " + file.getName() +
                "\n데이터 수 : " + data.size() + "개" +
                String.format(java.util.Locale.US,
                        "\n평균값 : %.3f m/s²", avg) +
                String.format(java.util.Locale.US,
                        "\n최대값 : %.3f m/s²", max) +
                String.format(java.util.Locale.US,
                        "\n최소값 : %.3f m/s²", min) +
                String.format(java.util.Locale.US,
                        "\nSPEC : %.2f m/s²", getThreshold())
        );

        info.setTextSize(17);
        info.setPadding(0, 0, 0, pad);
        box.addView(info);

        VibrationGraph csvGraph = new VibrationGraph(this);
        csvGraph.setThreshold(getThreshold());

        for (double v : data) {
            csvGraph.addValue(v);
        }

        int h = (int)(300 * getResources().getDisplayMetrics().density);

        csvGraph.setLayoutParams(
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        h
                )
        );

        box.addView(csvGraph);

        new android.app.AlertDialog.Builder(this)
                .setTitle("저장 진동 데이터")
                .setView(box)
                .setPositiveButton("닫기", null)
                .show();
    }

    static class VibrationGraph extends View {
        private final ArrayList<Double> xs=new ArrayList<>(), ys=new ArrayList<>(), zs=new ArrayList<>(), totals=new ArrayList<>();
        private final Paint px=new Paint(Paint.ANTI_ALIAS_FLAG), py=new Paint(Paint.ANTI_ALIAS_FLAG), pz=new Paint(Paint.ANTI_ALIAS_FLAG), pt=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thresholdPaint=new Paint(Paint.ANTI_ALIAS_FLAG), gridPaint=new Paint(Paint.ANTI_ALIAS_FLAG), textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private double threshold=2.0;
        private String mode="ALL";
        private static final int MAX_POINTS=220;
        public VibrationGraph(android.content.Context context){
            super(context);
            px.setColor(Color.rgb(0,130,220)); py.setColor(Color.rgb(0,170,110)); pz.setColor(Color.rgb(230,145,20)); pt.setColor(Color.rgb(95,55,180));
            for(Paint p:new Paint[]{px,py,pz,pt}){p.setStrokeWidth(4f);p.setStyle(Paint.Style.STROKE);}
            pt.setStrokeWidth(6f); thresholdPaint.setColor(Color.RED);thresholdPaint.setStrokeWidth(3f);
            gridPaint.setColor(Color.rgb(215,223,230));gridPaint.setStrokeWidth(1f); textPaint.setColor(Color.DKGRAY);textPaint.setTextSize(26f);
            setBackgroundColor(Color.WHITE);
        }
        private void add(ArrayList<Double> a,double v){a.add(v);if(a.size()>MAX_POINTS)a.remove(0);}
        public void addValue(double v){addValues(0,0,0,v);}
        public void addValues(double x,double y,double z,double total){add(xs,x);add(ys,y);add(zs,z);add(totals,total);invalidate();}
        public void setThreshold(double v){threshold=v;invalidate();}
        public void setMode(String m){mode=m==null?"ALL":m;invalidate();}
        public void clear(){xs.clear();ys.clear();zs.clear();totals.clear();invalidate();}
        private double absMax(ArrayList<Double> a){double m=0;for(double v:a)m=Math.max(m,Math.abs(v));return m;}
        private void drawSeries(Canvas c,ArrayList<Double> a,Paint p,int l,int r,int t,int b,double maxY){
            if(a.size()<2)return; float step=(r-l)/(float)(MAX_POINTS-1);int start=MAX_POINTS-a.size();float px0=0,py0=0;
            for(int i=0;i<a.size();i++){double v=Math.abs(a.get(i));float x=l+(start+i)*step;float y=(float)(b-(v/maxY)*(b-t));if(y<t)y=t;if(i>0)c.drawLine(px0,py0,x,y,p);px0=x;py0=y;}
        }
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);int w=getWidth(),h=getHeight(),l=58,r=w-20,t=48,b=h-50;
            double maxY=Math.max(3.0,threshold*1.5);maxY=Math.max(maxY,Math.max(absMax(totals),Math.max(absMax(xs),Math.max(absMax(ys),absMax(zs))))*1.15);
            for(int i=0;i<=4;i++){float y=t+(b-t)*i/4f;canvas.drawLine(l,y,r,y,gridPaint);}
            float sy=(float)(b-(threshold/maxY)*(b-t));canvas.drawLine(l,sy,r,sy,thresholdPaint);canvas.drawText(String.format(Locale.US,"SPEC %.2f",threshold),l+8,sy-7,textPaint);
            if(mode.equals("ALL")||mode.equals("X"))drawSeries(canvas,xs,px,l,r,t,b,maxY);
            if(mode.equals("ALL")||mode.equals("Y"))drawSeries(canvas,ys,py,l,r,t,b,maxY);
            if(mode.equals("ALL")||mode.equals("Z"))drawSeries(canvas,zs,pz,l,r,t,b,maxY);
            if(mode.equals("ALL")||mode.equals("TOTAL"))drawSeries(canvas,totals,pt,l,r,t,b,maxY);
            canvas.drawText(String.format(Locale.US,"%.1f",maxY),3,t+22,textPaint);canvas.drawText("0",18,b,textPaint);
            canvas.drawText("X  Y  Z  TOTAL",l,b+38,textPaint);
        }
    }
}
