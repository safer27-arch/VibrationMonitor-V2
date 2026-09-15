package com.example.vibrationmonitor;

import android.app.Activity;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.*;
import java.util.*;

public class StatisticsActivity extends Activity {

    private LinearLayout root;
    private LinearLayout summaryBox;
    private LinearLayout chartBox;

    private double spec = 2.0;
    private File dataDir;

    static class EventData {
        String dateText;
        Date date;
        double max;
        double avg;
        int count;
        boolean over;
    }

    static class DayData {
        String label;
        double max = 0;
        double sumAvg = 0;
        int events = 0;
        int overEvents = 0;

        double getAvg() {
            return events == 0 ? 0 : sumAvg / events;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        spec = getIntent().getDoubleExtra("spec", 2.0);
        dataDir = new File(getExternalFilesDir(null), "VibrationData");

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(100));

        TextView title = new TextView(this);
        title.setText("진동 통계 대시보드");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title);

        TextView specText = new TextView(this);
        specText.setText(String.format(Locale.US,
                "현재 SPEC : %.2f m/s²", spec));
        specText.setTextSize(18);
        root.addView(specText);

        LinearLayout periodRow = new LinearLayout(this);
        periodRow.setOrientation(LinearLayout.HORIZONTAL);

        Button today = new Button(this);
        today.setText("오늘");

        Button week = new Button(this);
        week.setText("7일");

        Button month = new Button(this);
        month.setText("30일");

        LinearLayout.LayoutParams bp =
                new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        periodRow.addView(today, bp);
        periodRow.addView(week, bp);
        periodRow.addView(month, bp);
        root.addView(periodRow);

        summaryBox = new LinearLayout(this);
        summaryBox.setOrientation(LinearLayout.VERTICAL);
        summaryBox.setPadding(0, dp(12), 0, dp(8));
        root.addView(summaryBox);

        chartBox = new LinearLayout(this);
        chartBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(chartBox);

        today.setOnClickListener(v -> showPeriod(1));
        week.setOnClickListener(v -> showPeriod(7));
        month.setOnClickListener(v -> showPeriod(30));

        Button back = new Button(this);
        back.setText("돌아가기");
        back.setOnClickListener(v -> finish());
        root.addView(back);

        scroll.addView(root);
        setContentView(scroll);

        showPeriod(7);
    }

    private void showPeriod(int days) {
        ArrayList<EventData> events = loadEvents(days);

        summaryBox.removeAllViews();
        chartBox.removeAllViews();

        String periodName =
                days == 1 ? "오늘" :
                days == 7 ? "최근 7일" :
                "최근 30일";

        TextView period = new TextView(this);
        period.setText("\n조회 기간 : " + periodName);
        period.setTextSize(20);
        summaryBox.addView(period);

        if (events.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("\n해당 기간에 저장된 이벤트가 없습니다.");
            empty.setTextSize(18);
            summaryBox.addView(empty);
            return;
        }

        double max = 0;
        double sumAvg = 0;
        int overCount = 0;

        for (EventData e : events) {
            max = Math.max(max, e.max);
            sumAvg += e.avg;
            if (e.over) overCount++;
        }

        double avg = sumAvg / events.size();
        double overRate = events.isEmpty()
                ? 0
                : (100.0 * overCount / events.size());

        String status;
        int statusColor;

        // 간단 관리 기준:
        // SPEC 초과 이벤트 0%       = OK
        // 0% 초과 ~ 20% 이하       = WARNING
        // 20% 초과                 = NG
        if (overCount == 0) {
            status = "OK";
            statusColor = Color.rgb(0, 130, 0);
        } else if (overRate <= 20.0) {
            status = "WARNING";
            statusColor = Color.rgb(220, 140, 0);
        } else {
            status = "NG";
            statusColor = Color.RED;
        }

        TextView statusText = new TextView(this);
        statusText.setText("판정 : " + status);
        statusText.setTextSize(26);
        statusText.setTextColor(statusColor);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(10), 0, dp(10));
        summaryBox.addView(statusText);

        addSummary("이벤트 수", events.size() + "건");
        addSummary("기간 최대값",
                String.format(Locale.US, "%.3f m/s²", max));
        addSummary("이벤트 평균값",
                String.format(Locale.US, "%.3f m/s²", avg));
        addSummary("SPEC 초과 이벤트",
                overCount + "건");
        addSummary("SPEC 초과율",
                String.format(Locale.US, "%.1f %%", overRate));

        TextView trendTitle = new TextView(this);
        trendTitle.setText("\n날짜별 MAX / AVG Trend");
        trendTitle.setTextSize(20);
        chartBox.addView(trendTitle);

        ArrayList<DayData> daysData = aggregateByDay(events);

        StatsChart chart = new StatsChart(this, daysData, spec);
        chart.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(330)));
        chartBox.addView(chart);

        TextView dayTitle = new TextView(this);
        dayTitle.setText("\n날짜별 요약");
        dayTitle.setTextSize(20);
        chartBox.addView(dayTitle);

        for (int i = daysData.size() - 1; i >= 0; i--) {
            DayData d = daysData.get(i);

            TextView row = new TextView(this);
            row.setText(
                    d.label +
                    String.format(Locale.US,
                            "\nMAX %.3f   AVG %.3f   초과 %d/%d건",
                            d.max, d.getAvg(),
                            d.overEvents, d.events)
            );
            row.setTextSize(16);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundColor(Color.rgb(238, 238, 238));

            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));

            chartBox.addView(row, lp);
        }
    }

    private void addSummary(String name, String value) {
        TextView t = new TextView(this);
        t.setText(name + " : " + value);
        t.setTextSize(18);
        t.setPadding(0, dp(4), 0, dp(4));
        summaryBox.addView(t);
    }

    private ArrayList<EventData> loadEvents(int periodDays) {
        ArrayList<EventData> result = new ArrayList<>();

        File[] files = dataDir.listFiles((dir, name) ->
                name.toLowerCase(Locale.US).endsWith(".csv"));

        if (files == null) return result;

        long now = System.currentTimeMillis();
        long periodMs = periodDays * 24L * 60L * 60L * 1000L;

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        long start;
        if (periodDays == 1) {
            start = cal.getTimeInMillis();
        } else {
            start = now - periodMs;
        }

        for (File f : files) {
            Date fileDate = parseDate(f);
            if (fileDate == null) continue;
            if (fileDate.getTime() < start) continue;

            EventData e = readEvent(f, fileDate);
            if (e != null) result.add(e);
        }

        Collections.sort(result,
                (a, b) -> Long.compare(a.date.getTime(), b.date.getTime()));

        return result;
    }

    private EventData readEvent(File file, Date date) {
        ArrayList<Double> values = new ArrayList<>();

        try (BufferedReader br =
                     new BufferedReader(new FileReader(file))) {

            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.trim().split(",");
                if (p.length < 2) continue;

                try {
                    values.add(Double.parseDouble(p[1].trim()));
                } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            return null;
        }

        if (values.isEmpty()) return null;

        double max = 0;
        double sum = 0;

        for (double v : values) {
            max = Math.max(max, v);
            sum += v;
        }

        EventData e = new EventData();
        e.date = date;
        e.dateText = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.US).format(date);
        e.max = max;
        e.avg = sum / values.size();
        e.count = values.size();
        e.over = max > spec;

        return e;
    }

    private Date parseDate(File file) {
        String n = file.getName();

        try {
            if (n.startsWith("event_") && n.length() >= 21) {
                String raw = n.substring(6, 21);
                return new SimpleDateFormat(
                        "yyyyMMdd_HHmmss",
                        Locale.US).parse(raw);
            }
        } catch (Exception ignored) {}

        try {
            return new Date(file.lastModified());
        } catch (Exception ignored) {
            return null;
        }
    }

    private ArrayList<DayData> aggregateByDay(
            ArrayList<EventData> events) {

        LinkedHashMap<String, DayData> map =
                new LinkedHashMap<>();

        SimpleDateFormat sdf =
                new SimpleDateFormat("MM-dd", Locale.US);

        for (EventData e : events) {
            String key = sdf.format(e.date);

            DayData d = map.get(key);
            if (d == null) {
                d = new DayData();
                d.label = key;
                map.put(key, d);
            }

            d.events++;
            d.max = Math.max(d.max, e.max);
            d.sumAvg += e.avg;

            if (e.over) d.overEvents++;
        }

        return new ArrayList<>(map.values());
    }

    private int dp(int v) {
        return (int)(v *
                getResources().getDisplayMetrics().density);
    }

    static class StatsChart extends View {

        private final ArrayList<DayData> data;
        private final double spec;

        private final Paint maxPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint avgPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint specPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        StatsChart(android.content.Context c,
                   ArrayList<DayData> data,
                   double spec) {
            super(c);

            this.data = data;
            this.spec = spec;

            maxPaint.setColor(Color.rgb(20, 110, 200));
            maxPaint.setStrokeWidth(4f);
            maxPaint.setStyle(Paint.Style.STROKE);

            avgPaint.setColor(Color.rgb(0, 150, 90));
            avgPaint.setStrokeWidth(4f);
            avgPaint.setStyle(Paint.Style.STROKE);

            specPaint.setColor(Color.RED);
            specPaint.setStrokeWidth(3f);

            gridPaint.setColor(Color.LTGRAY);
            gridPaint.setStrokeWidth(1f);

            textPaint.setColor(Color.DKGRAY);
            textPaint.setTextSize(22f);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);

            if (data.isEmpty()) return;

            float left = 72f;
            float right = getWidth() - 20f;
            float top = 45f;
            float bottom = getHeight() - 75f;

            double maxY = spec;

            for (DayData d : data) {
                maxY = Math.max(maxY, d.max);
                maxY = Math.max(maxY, d.getAvg());
            }

            maxY *= 1.15;
            if (maxY <= 0) maxY = 1;

            for (int i = 0; i <= 4; i++) {
                float y =
                        top + (bottom - top) * i / 4f;

                c.drawLine(left, y, right, y, gridPaint);

                double label =
                        maxY * (4 - i) / 4.0;

                c.drawText(
                        String.format(Locale.US,
                                "%.1f", label),
                        5, y + 8, textPaint);
            }

            float specY =
                    (float)(bottom -
                            spec / maxY *
                            (bottom - top));

            c.drawLine(left, specY,
                    right, specY, specPaint);

            c.drawText(
                    String.format(Locale.US,
                            "SPEC %.2f", spec),
                    left + 8,
                    Math.max(top + 25, specY - 7),
                    textPaint);

            Path maxPath = new Path();
            Path avgPath = new Path();

            for (int i = 0; i < data.size(); i++) {
                DayData d = data.get(i);

                float x =
                        data.size() == 1
                        ? (left + right) / 2f
                        : left +
                          (right - left) *
                          i / (data.size() - 1f);

                float maxPointY =
                        (float)(bottom -
                                d.max / maxY *
                                (bottom - top));

                float avgPointY =
                        (float)(bottom -
                                d.getAvg() / maxY *
                                (bottom - top));

                if (i == 0) {
                    maxPath.moveTo(x, maxPointY);
                    avgPath.moveTo(x, avgPointY);
                } else {
                    maxPath.lineTo(x, maxPointY);
                    avgPath.lineTo(x, avgPointY);
                }

                c.drawCircle(x, maxPointY,
                        6f, maxPaint);

                c.drawCircle(x, avgPointY,
                        6f, avgPaint);

                c.save();
                c.rotate(-35,
                        x, bottom + 45);

                c.drawText(d.label,
                        x - 22,
                        bottom + 45,
                        textPaint);

                c.restore();
            }

            c.drawPath(maxPath, maxPaint);
            c.drawPath(avgPath, avgPaint);

            c.drawText("MAX",
                    left, top - 12, maxPaint);

            c.drawText("AVG",
                    left + 85, top - 12, avgPaint);
        }
    }
}
