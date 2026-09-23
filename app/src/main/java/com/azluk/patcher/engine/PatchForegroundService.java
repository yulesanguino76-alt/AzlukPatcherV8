package com.azluk.patcher.engine;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import androidx.core.app.NotificationCompat;
import com.azluk.patcher.R;

/**
 * PatchForegroundService — runs the APK patch job in a foreground service
 * so Android doesn't kill the process mid-patch on low-RAM devices.
 *
 * The service posts a persistent notification "Patching in background..."
 * while the work is running. When done, it updates to "Patch complete" or
 * "Patch failed" and sends a local broadcast for the UI to pick up.
 *
 * The actual patch work is delegated to PatchWorker (a Kotlin coroutine-based
 * class) via a callback interface so the two languages interop cleanly.
 *
 * Usage:
 *   val intent = Intent(ctx, PatchForegroundService::class.java).apply {
 *       putExtra("pkg", "com.example.app")
 *       putStringArrayListExtra("patches", ArrayList(patchKeys))
 *   }
 *   ctx.startForegroundService(intent)
 */
public class PatchForegroundService extends Service {

    public static final String ACTION_PATCH_PROGRESS = "com.azluk.patcher.PATCH_PROGRESS";
    public static final String ACTION_PATCH_DONE     = "com.azluk.patcher.PATCH_DONE";
    public static final String EXTRA_LOG_LINE  = "log_line";
    public static final String EXTRA_SUCCESS   = "success";
    public static final String EXTRA_OUTPUT    = "output_path";
    public static final String EXTRA_ERROR     = "error";

    private static final String CHANNEL_ID   = "azluk_patch";
    private static final int    NOTIF_ID     = 0xA21;

    private NotificationManager notifMgr;
    private volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        notifMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || running) return START_NOT_STICKY;
        running = true;

        // Post foreground notification immediately so Android doesn't kill us
        startForeground(NOTIF_ID, buildNotification("Patching in background...", false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        String pkg = intent.getStringExtra("pkg");
        String apkPath = intent.getStringExtra("apk_path");

        // Kick the actual work on a background thread
        // The KotlinPatchBridge is called from Kotlin's PatchViewModel
        // We just own the foreground context here.
        new Thread(() -> {
            try {
                // Notify UI we started
                sendProgress("Foreground service started");

                // The real work is triggered via KotlinBridge callback
                // registered by PatchViewModel before starting this service.
                KotlinBridge bridge = KotlinBridge.INSTANCE;
                if (bridge != null && bridge.callback != null) {
                    bridge.callback.startPatch(
                        pkg, apkPath,
                        msg -> {
                            sendProgress(msg);
                            updateNotification(msg);
                        },
                        (success, output, error) -> {
                            sendDone(success, output, error);
                            updateNotification(success ? "Patch complete" : "Patch failed");
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                stopForeground(STOP_FOREGROUND_DETACH);
                            } else {
                                stopForeground(true);
                            }
                            stopSelf();
                            running = false;
                        }
                    );
                } else {
                    // No bridge registered — stop
                    sendDone(false, null, "No patch task registered");
                    stopSelf();
                    running = false;
                }
            } catch (Exception e) {
                sendDone(false, null, e.getMessage());
                stopSelf();
                running = false;
            }
        }, "AzlukPatchThread").start();

        return START_NOT_STICKY;
    }

    private void sendProgress(String msg) {
        Intent i = new Intent(ACTION_PATCH_PROGRESS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_LOG_LINE, msg);
        sendBroadcast(i);
    }

    private void sendDone(boolean success, String output, String error) {
        Intent i = new Intent(ACTION_PATCH_DONE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_SUCCESS, success);
        if (output != null) i.putExtra(EXTRA_OUTPUT, output);
        if (error  != null) i.putExtra(EXTRA_ERROR,  error);
        sendBroadcast(i);
    }

    private void updateNotification(String text) {
        notifMgr.notify(NOTIF_ID, buildNotification(text, false));
    }

    private Notification buildNotification(String text, boolean done) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AzlukPatcher")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(!done)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Patching", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("AzlukPatcher background patching");
            notifMgr.createNotificationChannel(ch);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Kotlin interop bridge ─────────────────────────────────────────────────
    // Singleton that Kotlin's PatchViewModel registers itself on before
    // starting the foreground service, so the service can call back into it.

    public static final class KotlinBridge {
        public static KotlinBridge INSTANCE = new KotlinBridge();
        public volatile PatchCallback callback;
    }

    public interface PatchCallback {
        void startPatch(
            String pkg,
            String apkPath,
            ProgressCallback onProgress,
            DoneCallback onDone
        );
    }

    public interface ProgressCallback {
        void log(String msg);
    }

    public interface DoneCallback {
        void done(boolean success, String outputPath, String error);
    }
}
