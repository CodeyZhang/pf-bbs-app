package cn.peopleforever.bbs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class KeepAliveService extends Service {

    private static final String TAG = "PFKeepAlive";
    private static final String CHANNEL_ID = "pf_keepalive";
    private static final String CHANNEL_ID_MSG = "pf_messages";
    private static final int NOTI_ID_FOREGROUND = 1001;

    // 轮询间隔：前台 10s，后台 30s
    private static final long POLL_INTERVAL_FOREGROUND_MS = 10_000L;
    private static final long POLL_INTERVAL_BACKGROUND_MS = 30_000L;

    private static final String UNREAD_URL = "https://bbs.peopleforever.cn/api/messages/unread-count";

    // 前台标志，由 MainActivity 通过 Intent action 更新
    public static volatile boolean isAppForeground = false;

    private Handler handler;
    private Runnable pollTask;
    private int lastUnreadCount = -1;
    private boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("APP_FOREGROUND".equals(action)) {
                isAppForeground = true;
                Log.d(TAG, "APP_FOREGROUND, 轮询切到 10s");
            } else if ("APP_BACKGROUND".equals(action)) {
                isAppForeground = false;
                Log.d(TAG, "APP_BACKGROUND, 轮询切到 30s");
            }
        }

        if (!running) {
            running = true;
            startForeground(NOTI_ID_FOREGROUND, buildForegroundNotification());
            schedulePolling(1_500L);
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null && pollTask != null) {
            handler.removeCallbacks(pollTask);
        }
        super.onDestroy();
    }

    // ===== 通知渠道 =====

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            NotificationChannel keepAlive = new NotificationChannel(
                    CHANNEL_ID,
                    "后台连接",
                    NotificationManager.IMPORTANCE_LOW
            );
            keepAlive.setDescription("保持与服务器的长连接");
            keepAlive.setShowBadge(false);
            nm.createNotificationChannel(keepAlive);

            NotificationChannel msg = new NotificationChannel(
                    CHANNEL_ID_MSG,
                    "新私信",
                    NotificationManager.IMPORTANCE_HIGH
            );
            msg.setDescription("有人给你发私信时提醒");
            msg.enableVibration(true);
            nm.createNotificationChannel(msg);
        }
    }

    private Notification buildForegroundNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("PeopleForever BBS")
                .setContentText("正在保持连接，接收新消息")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    // ===== 自适应轮询 =====

    private long currentInterval() {
        return isAppForeground ? POLL_INTERVAL_FOREGROUND_MS : POLL_INTERVAL_BACKGROUND_MS;
    }

    private void schedulePolling(long delayMs) {
        if (pollTask != null) handler.removeCallbacks(pollTask);
        pollTask = new Runnable() {
            @Override
            public void run() {
                doPollOnce();
                if (running) {
                    handler.postDelayed(this, currentInterval());
                }
            }
        };
        handler.postDelayed(pollTask, delayMs);
    }

    private void doPollOnce() {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                SharedPreferences sp = getSharedPreferences("pf_bbs", MODE_PRIVATE);
                String cookie = sp.getString("cookie", "");
                if (cookie == null || cookie.isEmpty()) {
                    Log.d(TAG, "无 cookie，跳过轮询");
                    return;
                }

                URL url = new URL(UNREAD_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8_000);
                conn.setReadTimeout(8_000);
                conn.setRequestProperty("Cookie", cookie);
                conn.setRequestProperty("User-Agent", "PFBBSApp/1.0 (KeepAlive)");

                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.d(TAG, "轮询 HTTP " + code);
                    return;
                }

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                int count = parseCount(sb.toString());
                if (count < 0) return;

                Log.d(TAG, "未读数: " + count + " (上次: " + lastUnreadCount + "), 前台=" + isAppForeground);

                if (lastUnreadCount >= 0 && count > lastUnreadCount) {
                    int delta = count - lastUnreadCount;
                    // 只在后台弹系统通知；前台时用户就在 App 里，前端自己会处理
                    if (!isAppForeground) {
                        showMessageNotification(delta);
                    }
                }
                lastUnreadCount = count;

            } catch (Exception e) {
                Log.e(TAG, "轮询失败", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private int parseCount(String json) {
        try {
            int idx = json.indexOf("\"count\"");
            if (idx < 0) return -1;
            int colon = json.indexOf(':', idx);
            if (colon < 0) return -1;
            int end = colon + 1;
            while (end < json.length() && (json.charAt(end) == ' ' || json.charAt(end) == '\t')) end++;
            int start = end;
            while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
            if (end == start) return -1;
            return Integer.parseInt(json.substring(start, end));
        } catch (Exception e) {
            return -1;
        }
    }

    private void showMessageNotification(int delta) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        openIntent.putExtra("open_page", "messages");
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 1, openIntent, piFlags);

        String title = "✉️ PF BBS 新私信";
        String text = delta == 1 ? "你有 1 条新私信" : ("你有 " + delta + " 条新私信");

        Notification noti = new NotificationCompat.Builder(this, CHANNEL_ID_MSG)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build();

        nm.notify((int) (System.currentTimeMillis() % 100000), noti);
    }
}
