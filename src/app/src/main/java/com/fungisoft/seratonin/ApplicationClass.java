package com.fungisoft.seratonin;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

public class ApplicationClass extends Application {

    public static final String CHANNEL_ID_1 = "channel1";
    public static final String CHANNEL_ID_2 = "channel2";
    public static final String ACTION_PREVIOUS = "actionprevious";
    public static final String ACTION_NEXT = "actionnext";
    public static final String ACTION_PLAY = "actionplay";
    public static final String ACTION_STOP = "actionstop";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            // General notification channel
            NotificationChannel channel1 =
                    new NotificationChannel(CHANNEL_ID_1,
                            "General Notifications", NotificationManager.IMPORTANCE_DEFAULT);
            channel1.setDescription("General app notifications");

            // Media playback channel - use LOW importance to avoid intrusive alerts
            NotificationChannel channel2 =
                    new NotificationChannel(CHANNEL_ID_2,
                            "Media Playback", NotificationManager.IMPORTANCE_LOW);
            channel2.setDescription("Music playback controls");
            channel2.setShowBadge(false);
            channel2.setSound(null, null);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel1);
            notificationManager.createNotificationChannel(channel2);
        }
    }
}
