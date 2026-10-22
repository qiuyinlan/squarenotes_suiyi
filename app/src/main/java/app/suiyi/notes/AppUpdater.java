package app.suiyi.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AppUpdater {
    private static final String PREFS = "suiyi_updates";
    private static final String PREF_LAST_CHECK = "last_check";
    private static final String PREF_DOWNLOAD_ID = "download_id";
    private static final String PREF_PENDING_URI = "pending_install_uri";
    private static final String PREF_DOWNLOAD_STATE_MIGRATED = "download_state_migrated";
    private static final long CHECK_INTERVAL = 24L * 60L * 60L * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private AppUpdater() {}

    static BroadcastReceiver register(Activity activity) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                    onDownloadComplete(activity, id);
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (android.os.Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else activity.registerReceiver(receiver, filter);
        return receiver;
    }

    static void onResume(Activity activity) {
        migrateDownloadState(activity);
        completeSavedDownload(activity);
        installPending(activity);
        check(activity, false);
    }

    private static void migrateDownloadState(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_DOWNLOAD_STATE_MIGRATED, false)) return;
        SharedPreferences.Editor edit = prefs.edit().putBoolean(PREF_DOWNLOAD_STATE_MIGRATED, true);
        if (prefs.getString(PREF_PENDING_URI, "").isEmpty()) edit.remove(PREF_DOWNLOAD_ID);
        edit.apply();
    }

    private static void completeSavedDownload(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long id = prefs.getLong(PREF_DOWNLOAD_ID, -1L);
        if (id < 0 || !prefs.getString(PREF_PENDING_URI, "").isEmpty()) return;
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        try (android.database.Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor != null && cursor.moveToFirst() &&
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) {
                onDownloadComplete(activity, id);
            }
        }
    }

    static void check(Activity activity, boolean userInitiated) {
        String manifestUrl = BuildConfig.UPDATE_MANIFEST_URL.trim();
        if (manifestUrl.isEmpty()) {
            if (userInitiated) toast(activity, "尚未配置更新清单地址");
            return;
        }
        if (!manifestUrl.startsWith("https://")) {
            if (userInitiated) toast(activity, "更新地址必须使用 HTTPS");
            return;
        }
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!userInitiated && now - prefs.getLong(PREF_LAST_CHECK, 0L) < CHECK_INTERVAL) return;
        prefs.edit().putLong(PREF_LAST_CHECK, now).apply();
        EXECUTOR.execute(() -> {
            JSONObject manifest = null;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(manifestUrl).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("Accept", "application/json");
                try {
                    if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300 ||
                            !"https".equalsIgnoreCase(connection.getURL().getProtocol())) throw new Exception("bad response");
                    try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            if (out.size() + count > 65536) throw new Exception("manifest too large");
                            out.write(buffer, 0, count);
                        }
                        manifest = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) {}
            JSONObject result = manifest;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || result == null) {
                    if (userInitiated && !activity.isFinishing()) toast(activity, "检查更新失败，请检查网络或更新清单");
                    return;
                }
                int versionCode = result.optInt("versionCode", 0);
                String versionName = result.optString("versionName", "");
                String downloadUrl = result.optString("downloadUrl", "");
                if (versionCode <= BuildConfig.VERSION_CODE) {
                    if (userInitiated) toast(activity, "当前已是最新版本");
                    return;
                }
                if (!downloadUrl.startsWith("https://") || versionName.isEmpty()) {
                    if (userInitiated) toast(activity, "更新清单内容无效");
                    return;
                }
                showUpdate(activity, versionName, downloadUrl, result.optString("releaseNotes", ""));
            });
        });
    }

    private static void showUpdate(Activity activity, String versionName, String downloadUrl, String notes) {
        String message = "发现新版本 " + versionName + (notes.isEmpty() ? "" : "\n\n" + notes);
        new AlertDialog.Builder(activity)
                .setTitle("碎忆有新版本")
                .setMessage(message)
                .setNegativeButton("稍后", null)
                .setPositiveButton("下载更新", (dialog, which) -> download(activity, versionName, downloadUrl))
                .show();
    }

    private static void download(Activity activity, String versionName, String downloadUrl) {
        try {
            DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            request.setTitle("碎忆 " + versionName);
            request.setDescription("正在下载更新包");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "suiyi-update.apk");
            long id = manager.enqueue(request);
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(PREF_DOWNLOAD_ID, id).apply();
            toast(activity, "已开始下载，完成后可安装");
        } catch (Exception e) {
            toast(activity, "无法开始下载更新");
        }
    }

    private static void onDownloadComplete(Activity activity, long completedId) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (completedId != prefs.getLong(PREF_DOWNLOAD_ID, -1L)) return;
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(completedId);
        try (android.database.Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                prefs.edit().remove(PREF_DOWNLOAD_ID).apply();
                toast(activity, "更新包下载失败，请稍后重试");
                return;
            }
        }
        Uri uri = manager.getUriForDownloadedFile(completedId);
        if (uri == null) {
            prefs.edit().remove(PREF_DOWNLOAD_ID).apply();
            toast(activity, "找不到已下载的更新包");
            return;
        }
        prefs.edit().putString(PREF_PENDING_URI, uri.toString()).remove(PREF_DOWNLOAD_ID).apply();
        installPending(activity);
    }

    private static void installPending(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String savedUri = prefs.getString(PREF_PENDING_URI, "");
        if (savedUri.isEmpty()) return;
        if (android.os.Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
            toast(activity, "请允许此来源安装应用，然后返回继续安装");
            return;
        }
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(Uri.parse(savedUri), "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
            prefs.edit().remove(PREF_PENDING_URI).apply();
        } catch (Exception e) {
            toast(activity, "无法打开安装程序");
        }
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
