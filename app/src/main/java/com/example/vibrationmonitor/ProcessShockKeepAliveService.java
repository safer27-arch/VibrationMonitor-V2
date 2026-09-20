package com.example.vibrationmonitor;

public class ProcessShockKeepAliveService extends android.app.Service {

    private static final String CHANNEL_ID = "process_shock_monitor";
    private android.os.PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel channel =
                    new android.app.NotificationChannel(
                            CHANNEL_ID,
                            "Process Shock Monitoring",
                            android.app.NotificationManager.IMPORTANCE_LOW
                    );

            channel.setDescription("Keeps continuous equipment impact monitoring active.");

            android.app.NotificationManager nm =
                    (android.app.NotificationManager)
                            getSystemService(NOTIFICATION_SERVICE);

            nm.createNotificationChannel(channel);
        }

        android.os.PowerManager pm =
                (android.os.PowerManager)
                        getSystemService(POWER_SERVICE);

        wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "VibrationMonitor:ProcessShock"
        );

        wakeLock.acquire(6L * 60L * 60L * 1000L);
    }

    private android.app.Notification notification() {
        android.content.Intent open =
                new android.content.Intent(
                        this,
                        ProcessShockActivity.class
                );

        android.app.PendingIntent pi =
                android.app.PendingIntent.getActivity(
                        this,
                        3201,
                        open,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                | android.app.PendingIntent.FLAG_IMMUTABLE
                );

        android.app.Notification.Builder b;

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            b = new android.app.Notification.Builder(
                    this,
                    CHANNEL_ID
            );
        } else {
            b = new android.app.Notification.Builder(this);
        }

        return b
                .setContentTitle("Process Shock Monitoring")
                .setContentText("연속 충격 측정이 진행 중입니다.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public int onStartCommand(
            android.content.Intent intent,
            int flags,
            int startId
    ) {
        startForeground(
                3201,
                notification()
        );

        return START_STICKY;
    }

    @Override
    public android.os.IBinder onBind(
            android.content.Intent intent
    ) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null
                && wakeLock.isHeld()) {
            wakeLock.release();
        }

        super.onDestroy();
    }
}
