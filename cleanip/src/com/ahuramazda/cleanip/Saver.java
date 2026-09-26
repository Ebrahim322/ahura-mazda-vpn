package com.ahuramazda.cleanip;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/** Clipboard, share intent and "save into Downloads" helpers. */
public final class Saver {

    public static final int REQUEST_WRITE = 4711;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String FOLDER = "AhuraCleanIP";

    private static Pending pending;

    public interface SaveCallback {
        void onSaved(String location);

        void onError(String message);
    }

    private static final class Pending {
        String name;
        String content;
        SaveCallback callback;
    }

    private Saver() {
    }

    public static void copy(Context context, String label, String text) {
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text == null ? "" : text));
    }

    public static String clipboard(Context context) {
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return "";
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return "";
        }
        CharSequence text = clip.getItemAt(0).coerceToText(context);
        return text == null ? "" : text.toString();
    }

    public static void share(Context context, String subject, String text) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        intent.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
        context.startActivity(Intent.createChooser(intent, Text.SHARE));
    }

    /** Saves into Downloads/AhuraCleanIP; asks for the legacy permission when needed. */
    public static void save(Activity activity, String fileName, String content, SaveCallback callback) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                saveModern(activity, fileName, content);
                callback.onSaved("Downloads/" + FOLDER + "/" + fileName);
            } catch (Exception e) {
                callback.onError(String.valueOf(e.getMessage()));
            }
            return;
        }
        if (activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pending = new Pending();
            pending.name = fileName;
            pending.content = content;
            pending.callback = callback;
            activity.requestPermissions(
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE);
            return;
        }
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), FOLDER);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("cannot create " + dir);
            }
            File file = new File(dir, fileName);
            OutputStream out = new FileOutputStream(file);
            out.write(content.getBytes(UTF8));
            out.close();
            callback.onSaved(file.getAbsolutePath());
        } catch (Exception e) {
            callback.onError(String.valueOf(e.getMessage()));
        }
    }

    private static void saveModern(Context context, String fileName, String content) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IllegalStateException("media store refused the request");
        }
        OutputStream out = context.getContentResolver().openOutputStream(uri);
        if (out == null) {
            throw new IllegalStateException("cannot open " + uri);
        }
        out.write(content.getBytes(UTF8));
        out.close();
    }

    /** Called from Activity.onRequestPermissionsResult. */
    public static void onPermissionResult(Activity activity, int requestCode, boolean granted) {
        if (requestCode != REQUEST_WRITE || pending == null) {
            return;
        }
        Pending request = pending;
        pending = null;
        if (granted) {
            save(activity, request.name, request.content, request.callback);
        } else {
            request.callback.onError(Text.NEED_PERMISSION);
        }
    }

    public static String timeStamp() {
        java.text.SimpleDateFormat format =
                new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US);
        return format.format(new java.util.Date());
    }
}
