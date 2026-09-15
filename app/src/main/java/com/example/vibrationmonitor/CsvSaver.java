package com.example.vibrationmonitor;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CsvSaver {

    public static File save(Context context, List<Float> values) {
        String time = new SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.getDefault()
        ).format(new Date());

        File dir = new File(context.getExternalFilesDir(null), "VibrationData");

        if (!dir.exists()) {
            dir.mkdirs();
        }

        File file = new File(dir, "event_" + time + ".csv");

        try {
            FileWriter writer = new FileWriter(file);

            writer.write("sample,vibration_m_s2\n");

            for (int i = 0; i < values.size(); i++) {
                writer.write(i + "," + values.get(i) + "\n");
            }

            writer.flush();
            writer.close();

            return file;

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static File saveXYZ(Context context, List<float[]> rows) {
        String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File dir = new File(context.getExternalFilesDir(null), "VibrationData");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, "event_" + time + ".csv");
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("sample,x_m_s2,y_m_s2,z_m_s2,total_m_s2\n");
            for (int i=0;i<rows.size();i++) {
                float[] r=rows.get(i);
                writer.write(String.format(Locale.US,"%d,%.6f,%.6f,%.6f,%.6f\n",i,r[0],r[1],r[2],r[3]));
            }
            return file;
        } catch(IOException e){ e.printStackTrace(); return null; }
    }
}
