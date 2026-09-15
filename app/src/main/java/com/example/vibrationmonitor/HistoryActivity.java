package com.example.vibrationmonitor;

import android.app.*;
import android.os.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.*;
import java.util.*;

public class HistoryActivity extends Activity {

    private double spec = 2.0;
    private File dataDir;
    private final ArrayList<EventStat> events = new ArrayList<>();

    static class EventStat {
        File file;
        ArrayList<Double> values = new ArrayList<>();
        double avg, max, min, over;
        String dateText;
        String building = "미지정";

        double latitude;
        double longitude;
        boolean hasLocation;

        File photoFile;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        spec = getIntent().getDoubleExtra("spec", 2.0);
        dataDir = new File(getExternalFilesDir(null), "VibrationData");

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        int p = dp(16);
        root.setPadding(p, p, p, dp(100));

        TextView title = new TextView(this);
        title.setText("진동 이력 관리");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        TextView specText = new TextView(this);
        specText.setText(String.format(Locale.US,
                "현재 SPEC : %.2f m/s²", spec));
        specText.setTextSize(18);
        root.addView(specText);

        loadEvents();

        TextView count = new TextView(this);
        count.setText("저장 이벤트 : " + events.size() + "건");
        count.setTextSize(18);
        count.setPadding(0, dp(6), 0, dp(10));
        root.addView(count);

        if (events.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("\n저장된 CSV 이벤트가 없습니다.");
            empty.setTextSize(18);
            root.addView(empty);
        } else {
            TextView trendTitle = new TextView(this);
            trendTitle.setText("이벤트 최대 진동 Trend");
            trendTitle.setTextSize(20);
            trendTitle.setPadding(0, dp(10), 0, dp(4));
            root.addView(trendTitle);

            TrendView trend = new TrendView(this, events, spec);
            trend.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
            root.addView(trend);

            TextView listTitle = new TextView(this);
            listTitle.setText("\n최근 이벤트");
            listTitle.setTextSize(20);
            root.addView(listTitle);

            int eventNo = events.size();

            for (EventStat e : events) {
                Button b = new Button(this);

                b.setText(
                    "이벤트 #" + eventNo +
                    "\n" + e.dateText +
                    String.format(Locale.US,
                        "\nMAX %.3f   AVG %.3f   MIN %.3f" +
                        "\nSPEC %.2f   초과 +%.3f m/s²" +
                        "\n데이터 %,d개",
                        e.max, e.avg, e.min,
                        spec, e.over, e.values.size())
                );

                b.setAllCaps(false);
                b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                b.setPadding(dp(14), dp(10), dp(14), dp(10));
                b.setOnClickListener(v -> showDetail(e));
                root.addView(b);

                eventNo--;
            }
        }

        Button statsButton = new Button(this);
        statsButton.setText("통계 대시보드");
        statsButton.setOnClickListener(v -> {
            android.content.Intent intent =
                    new android.content.Intent(
                            HistoryActivity.this,
                            StatisticsActivity.class);
            intent.putExtra("spec", spec);
            startActivity(intent);
        });
        root.addView(statsButton);

        Button close = new Button(this);
        close.setText("돌아가기");
        close.setOnClickListener(v -> finish());
        root.addView(close);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void loadEvents() {
        events.clear();

        File[] files = dataDir.listFiles((dir, name) ->
                name.toLowerCase(Locale.US).endsWith(".csv"));

        if (files == null) return;

        Arrays.sort(files,
                (a,b) -> Long.compare(b.lastModified(), a.lastModified()));

        for (File f : files) {
            EventStat e = readEvent(f);
            if (e != null) events.add(e);
        }
    }

    private EventStat readEvent(File f) {
        EventStat e = new EventStat();
        e.file = f;

        try (BufferedReader br =
                     new BufferedReader(new FileReader(f))) {

            String line;

            while ((line = br.readLine()) != null) {
                String[] a = line.trim().split(",");

                if (a.length < 2) continue;

                try {
                    e.values.add(
                            Double.parseDouble((a.length >= 5 ? a[4] : a[1]).trim()));
                } catch (Exception ignored) {}
            }

        } catch (Exception ex) {
            return null;
        }

        if (e.values.isEmpty()) return null;

        double sum = 0;
        e.max = -Double.MAX_VALUE;
        e.min = Double.MAX_VALUE;

        for (double v : e.values) {
            sum += v;
            if (v > e.max) e.max = v;
            if (v < e.min) e.min = v;
        }

        e.avg = sum / e.values.size();
        e.over = Math.max(0, e.max - spec);
        e.dateText = formatFileDate(f);

        readGpsData(e);

        return e;
    }

    private void readGpsData(EventStat e) {

        File gpsFile = new File(
                e.file.getParentFile(),
                e.file.getName().replace(".csv", ".gps")
        );

        if (!gpsFile.exists()) {
            e.hasLocation = false;
            return;
        }

        Double lat = null;
        Double lon = null;

        try (BufferedReader br =
                     new BufferedReader(new FileReader(gpsFile))) {

            String line;

            while ((line = br.readLine()) != null) {

                line = line.trim();

                if (line.startsWith("building=")) {
                    String value =
                            line.substring("building=".length()).trim();

                    if (!value.isEmpty()) {
                        e.building = value;
                    }
                }

                if (line.startsWith("latitude=")) {
                    String value =
                            line.substring("latitude=".length()).trim();

                    if (!value.isEmpty()) {
                        lat = Double.parseDouble(value);
                    }
                }

                if (line.startsWith("longitude=")) {
                    String value =
                            line.substring("longitude=".length()).trim();

                    if (!value.isEmpty()) {
                        lon = Double.parseDouble(value);
                    }
                }

                if (line.startsWith("photo=")) {

                    String value =
                            line.substring("photo=".length()).trim();

                    if (!value.isEmpty()) {

                        File candidate =
                                new File(
                                        e.file.getParentFile(),
                                        value
                                );

                        if (candidate.exists()) {
                            e.photoFile = candidate;
                        }
                    }
                }
            }

        } catch (Exception ignored) {
            e.hasLocation = false;
            return;
        }

        if (lat != null && lon != null) {
            e.latitude = lat;
            e.longitude = lon;
            e.hasLocation = true;
        }
    }

    private String formatFileDate(File f) {
        String n = f.getName();

        try {
            if (n.startsWith("event_") && n.length() >= 21) {
                String raw = n.substring(6, 21);

                Date d = new SimpleDateFormat(
                        "yyyyMMdd_HHmmss", Locale.US).parse(raw);

                return new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.US).format(d);
            }
        } catch (Exception ignored) {}

        return n;
    }

    private void showDetail(EventStat e) {

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView info = new TextView(this);
        info.setText(
                e.dateText +
                "\n파일 : " + e.file.getName() +
                "\n건물 : " + e.building +
                "\n데이터 수 : " + e.values.size() + "개" +
                String.format(Locale.US,
                        "\n평균값 : %.3f m/s²", e.avg) +
                String.format(Locale.US,
                        "\n최대값 : %.3f m/s²", e.max) +
                String.format(Locale.US,
                        "\n최소값 : %.3f m/s²", e.min) +
                String.format(Locale.US,
                        "\n현재 SPEC : %.2f m/s²", spec) +
                String.format(Locale.US,
                        "\nSPEC 초과량 : %.3f m/s²", e.over) +
                (e.hasLocation
                        ? String.format(Locale.US,
                            "\nGPS 위치 : %.6f, %.6f",
                            e.latitude,
                            e.longitude)
                        : "\nGPS 위치 : 기록 없음")
        );

        info.setTextSize(16);
        box.addView(info);

        if (
                e.hasLocation &&
                "WA5".equalsIgnoreCase(e.building)
        ) {
            TextView mapTitle = new TextView(this);
            mapTitle.setText("\nLGES WA5 이벤트 위치");
            mapTitle.setTextSize(17);
            box.addView(mapTitle);

            BuildingMapView mapView =
                    new BuildingMapView(
                            this,
                            e.latitude,
                            e.longitude
                    );

            mapView.setLayoutParams(
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(230)
                    )
            );

            box.addView(mapView);
        }

        if (e.photoFile != null && e.photoFile.exists()) {

            TextView photoTitle = new TextView(this);
            photoTitle.setText("\n이벤트 자동 촬영 사진");
            photoTitle.setTextSize(17);
            box.addView(photoTitle);

            android.widget.ImageView photoView =
                    new android.widget.ImageView(this);

            android.graphics.Bitmap bitmap =
                    android.graphics.BitmapFactory.decodeFile(
                            e.photoFile.getAbsolutePath()
                    );

            if (bitmap != null) {
                photoView.setImageBitmap(bitmap);
                photoView.setAdjustViewBounds(true);
                photoView.setScaleType(
                        android.widget.ImageView.ScaleType.CENTER_CROP
                );

                photoView.setLayoutParams(
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                dp(240)
                        )
                );

                box.addView(photoView);
            }
        }

        DetailView graph =
                new DetailView(this, e.values, spec);

        graph.setLayoutParams(
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(300)));

        box.addView(graph);

        ScrollView detailScroll = new ScrollView(this);
        detailScroll.setFillViewport(true);

        box.setLayoutParams(
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        graph.setMinimumWidth(
                getResources().getDisplayMetrics().widthPixels - dp(80)
        );

        detailScroll.addView(
                box,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        new AlertDialog.Builder(this)
                .setTitle("이벤트 상세 그래프")
                .setView(detailScroll)
                .setPositiveButton("닫기", null)
                .show();
    }

    private int dp(int v) {
        return (int)(v *
                getResources().getDisplayMetrics().density);
    }



    static class BuildingMapView extends View {

        private final double latitude;
        private final double longitude;

        private final Paint borderPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint fillPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pointPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint textPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        // WA5 네 모서리
        // 1: 왼쪽 위
        // 2: 오른쪽 위
        // 3: 오른쪽 아래
        // 4: 왼쪽 아래
        private static final double LAT1 = 51.0245604;
        private static final double LON1 = 16.8860644;

        private static final double LAT2 = 51.0239081;
        private static final double LON2 = 16.8896636;

        private static final double LAT3 = 51.0230264;
        private static final double LON3 = 16.8892264;

        private static final double LAT4 = 51.0236584;
        private static final double LON4 = 16.8856524;

        BuildingMapView(
                android.content.Context context,
                double latitude,
                double longitude
        ) {
            super(context);

            this.latitude = latitude;
            this.longitude = longitude;

            borderPaint.setColor(Color.DKGRAY);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(5f);

            fillPaint.setColor(Color.rgb(238, 242, 245));
            fillPaint.setStyle(Paint.Style.FILL);

            pointPaint.setColor(Color.RED);
            pointPaint.setStyle(Paint.Style.FILL);

            textPaint.setColor(Color.DKGRAY);
            textPaint.setTextSize(32f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float w = getWidth();
            float h = getHeight();

            float left = 55f;
            float right = w - 55f;
            float top = 45f;
            float bottom = h - 55f;

            canvas.drawRect(
                    left,
                    top,
                    right,
                    bottom,
                    fillPaint
            );

            canvas.drawRect(
                    left,
                    top,
                    right,
                    bottom,
                    borderPaint
            );

            canvas.drawText(
                    "WA5",
                    left + 12f,
                    top + 36f,
                    textPaint
            );

            // 위도/경도를 현장 기준 meter 좌표로 변환
            double refLat =
                    (LAT1 + LAT2 + LAT3 + LAT4) / 4.0;

            double meterPerLat = 111320.0;
            double meterPerLon =
                    111320.0 *
                    Math.cos(
                            Math.toRadians(refLat)
                    );

            // 1번 모서리를 원점으로 설정
            double bx =
                    (longitude - LON1) *
                    meterPerLon;

            double by =
                    (latitude - LAT1) *
                    meterPerLat;

            // 1 -> 2 : 건물 가로축
            double ax =
                    (LON2 - LON1) *
                    meterPerLon;

            double ay =
                    (LAT2 - LAT1) *
                    meterPerLat;

            // 1 -> 4 : 건물 세로축
            double cx =
                    (LON4 - LON1) *
                    meterPerLon;

            double cy =
                    (LAT4 - LAT1) *
                    meterPerLat;

            // 2x2 연립방정식으로 u/v 계산
            double det =
                    ax * cy -
                    ay * cx;

            double u = 0.5;
            double v = 0.5;

            if (Math.abs(det) > 0.000001) {

                u =
                        (bx * cy -
                         by * cx) / det;

                v =
                        (ax * by -
                         ay * bx) / det;
            }

            boolean inside =
                    u >= 0.0 &&
                    u <= 1.0 &&
                    v >= 0.0 &&
                    v <= 1.0;

            // 화면 밖으로 점이 사라지지 않도록 제한
            double drawU =
                    Math.max(
                            0.0,
                            Math.min(1.0, u)
                    );

            double drawV =
                    Math.max(
                            0.0,
                            Math.min(1.0, v)
                    );

            float px =
                    (float)(
                            left +
                            drawU *
                            (right - left)
                    );

            float py =
                    (float)(
                            top +
                            drawV *
                            (bottom - top)
                    );

            canvas.drawCircle(
                    px,
                    py,
                    16f,
                    pointPaint
            );

            Paint labelPaint =
                    new Paint(Paint.ANTI_ALIAS_FLAG);

            labelPaint.setColor(Color.RED);
            labelPaint.setTextSize(28f);

            canvas.drawText(
                    "이벤트 위치",
                    Math.min(px + 20f, right - 150f),
                    Math.max(py - 15f, top + 70f),
                    labelPaint
            );

            if (!inside) {
                Paint warnPaint =
                        new Paint(Paint.ANTI_ALIAS_FLAG);

                warnPaint.setColor(Color.rgb(200, 100, 0));
                warnPaint.setTextSize(25f);

                canvas.drawText(
                        "GPS 오차 또는 건물 범위 밖",
                        left,
                        h - 15f,
                        warnPaint
                );
            }
        }
    }

    static class DetailView extends View {
        private final ArrayList<Double> values;
        private final double spec;

        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint specPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        DetailView(android.content.Context c, ArrayList<Double> values, double spec) {
            super(c);
            this.values = values;
            this.spec = spec;

            line.setColor(Color.rgb(20, 110, 200));
            line.setStrokeWidth(3f);
            line.setStyle(Paint.Style.STROKE);

            specPaint.setColor(Color.RED);
            specPaint.setStrokeWidth(3f);

            grid.setColor(Color.LTGRAY);
            grid.setStrokeWidth(1f);

            text.setColor(Color.DKGRAY);
            text.setTextSize(24f);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (values.isEmpty()) return;

            float left = 72f;
            float right = getWidth() - 18f;
            float top = 28f;
            float bottom = getHeight() - 52f;

            double max = spec;
            for (double v : values) {
                if (v > max) max = v;
            }
            max *= 1.15;
            if (max <= 0) max = 1;

            for (int i = 0; i <= 4; i++) {
                float y = top + (bottom - top) * i / 4f;
                c.drawLine(left, y, right, y, grid);

                double label = max * (4 - i) / 4.0;
                c.drawText(String.format(Locale.US, "%.1f", label),
                        5, y + 8, text);
            }

            float sy = (float)(bottom - spec / max * (bottom - top));
            c.drawLine(left, sy, right, sy, specPaint);
            c.drawText(String.format(Locale.US, "SPEC %.2f", spec),
                    left + 8, Math.max(top + 24, sy - 8), text);

            Path path = new Path();

            for (int i = 0; i < values.size(); i++) {
                float x = values.size() == 1
                        ? left
                        : left + (right - left) * i / (values.size() - 1f);

                float y = (float)(bottom - values.get(i) / max * (bottom - top));

                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }

            c.drawPath(path, line);

            c.drawText("-3s", left, getHeight() - 12, text);
            c.drawText("0s", (left + right) / 2f - 14, getHeight() - 12, text);
            c.drawText("+3s", right - 48, getHeight() - 12, text);
        }
    }

    static class TrendView extends View {
        private final ArrayList<EventStat> events;
        private final double spec;

        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint specPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint point = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        TrendView(android.content.Context c, ArrayList<EventStat> events, double spec) {
            super(c);
            this.events = events;
            this.spec = spec;

            line.setColor(Color.rgb(20, 110, 200));
            line.setStrokeWidth(4f);
            line.setStyle(Paint.Style.STROKE);

            specPaint.setColor(Color.RED);
            specPaint.setStrokeWidth(3f);

            point.setColor(Color.rgb(20, 110, 200));

            grid.setColor(Color.LTGRAY);
            grid.setStrokeWidth(1f);

            text.setColor(Color.DKGRAY);
            text.setTextSize(22f);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (events.isEmpty()) return;

            float left = 72f;
            float right = getWidth() - 18f;
            float top = 42f;
            float bottom = getHeight() - 74f;

            double scaleMax = spec;
            for (EventStat e : events) {
                if (e.max > scaleMax) scaleMax = e.max;
            }

            scaleMax *= 1.15;
            if (scaleMax <= 0) scaleMax = 1;

            for (int i = 0; i <= 4; i++) {
                float y = top + (bottom - top) * i / 4f;
                c.drawLine(left, y, right, y, grid);

                double label = scaleMax * (4 - i) / 4.0;
                c.drawText(String.format(Locale.US, "%.1f", label),
                        5, y + 8, text);
            }

            float sy = (float)(bottom - spec / scaleMax * (bottom - top));
            c.drawLine(left, sy, right, sy, specPaint);
            c.drawText(String.format(Locale.US, "SPEC %.2f", spec),
                    left + 8, Math.max(top + 24, sy - 6), text);

            Path path = new Path();

            for (int i = 0; i < events.size(); i++) {
                EventStat e = events.get(events.size() - 1 - i);

                float x = events.size() == 1
                        ? (left + right) / 2f
                        : left + (right - left) * i / (events.size() - 1f);

                float y = (float)(bottom - e.max / scaleMax * (bottom - top));

                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);

                c.drawCircle(x, y, 7f, point);

                c.drawText(String.format(Locale.US, "%.2f", e.max),
                        x - 24, y - 12, text);

                String t = e.dateText.length() >= 16
                        ? e.dateText.substring(11, 16)
                        : e.dateText;

                c.save();
                c.rotate(-35, x, bottom + 48);
                c.drawText(t, x - 18, bottom + 48, text);
                c.restore();
            }

            c.drawPath(path, line);
        }
    }
}
