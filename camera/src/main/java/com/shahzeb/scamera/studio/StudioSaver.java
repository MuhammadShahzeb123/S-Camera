package com.shahzeb.scamera.studio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.MediaStore;

import com.shahzeb.scamera.PreferenceKeys;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes studio stills and videos into the same DCIM folder Open Camera already uses. */
public final class StudioSaver {
    private StudioSaver() {
    }

    public static final class VideoTarget {
        public Uri uri;
        public ParcelFileDescriptor pfd;
        public File legacyFile;
    }

    public static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    public static String folderName(Context context) {
        String folder = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PreferenceKeys.SaveLocationPreferenceKey, "S Camera");
        if( folder == null || folder.isEmpty() )
            folder = "S Camera";
        if( folder.startsWith("/") )
            return "S Camera";
        while( folder.endsWith("/") )
            folder = folder.substring(0, folder.length() - 1);
        return folder;
    }

    public static Uri saveJpeg(Context context, byte [] jpeg, String displayName) {
        if( jpeg == null || jpeg.length == 0 )
            return null;
        try {
            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ) {
                ContentValues values = imageValues(context, displayName);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                ContentResolver resolver = context.getContentResolver();
                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if( uri == null )
                    return null;
                try( OutputStream out = resolver.openOutputStream(uri) ) {
                    if( out == null )
                        return null;
                    out.write(jpeg);
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                return uri;
            }
            File file = new File(legacyDir(context), displayName);
            try( FileOutputStream out = new FileOutputStream(file) ) {
                out.write(jpeg);
            }
            MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            return Uri.fromFile(file);
        }
        catch(Exception e) {
            StudioLog.e("save jpeg failed", e);
            return null;
        }
    }

    public static Uri saveBitmap(Context context, Bitmap bitmap, String displayName) {
        if( bitmap == null )
            return null;
        try {
            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ) {
                ContentValues values = imageValues(context, displayName);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                ContentResolver resolver = context.getContentResolver();
                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if( uri == null )
                    return null;
                try( OutputStream out = resolver.openOutputStream(uri) ) {
                    if( out == null )
                        return null;
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                return uri;
            }
            File file = new File(legacyDir(context), displayName);
            try( FileOutputStream out = new FileOutputStream(file) ) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
            MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            return Uri.fromFile(file);
        }
        catch(Exception e) {
            StudioLog.e("save bitmap failed", e);
            return null;
        }
    }

    /** Prepares a video file. On Android 10+ this is a pending MediaStore item; otherwise a file in DCIM. */
    public static VideoTarget beginVideo(Context context, String displayName) {
        VideoTarget target = new VideoTarget();
        try {
            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + folderName(context));
                values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                ContentResolver resolver = context.getContentResolver();
                target.uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if( target.uri == null )
                    return null;
                target.pfd = resolver.openFileDescriptor(target.uri, "w");
                if( target.pfd == null )
                    return null;
                return target;
            }
            target.legacyFile = new File(legacyDir(context), displayName);
            return target;
        }
        catch(Exception e) {
            StudioLog.e("begin video failed", e);
            abandonVideo(context, target);
            return null;
        }
    }

    public static void finishVideo(Context context, VideoTarget target) {
        if( target == null )
            return;
        try {
            if( target.pfd != null )
                target.pfd.close();
        }
        catch(Exception ignored) {
        }
        target.pfd = null;
        try {
            if( target.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(target.uri, values, null, null);
            }
            else if( target.legacyFile != null ) {
                MediaScannerConnection.scanFile(context, new String[]{target.legacyFile.getAbsolutePath()}, new String[]{"video/mp4"}, null);
            }
        }
        catch(Exception e) {
            StudioLog.e("finish video failed", e);
        }
        // Drop the handles so a later teardown cannot delete a video that already finished.
        target.uri = null;
        target.legacyFile = null;
    }

    public static void abandonVideo(Context context, VideoTarget target) {
        if( target == null )
            return;
        try {
            if( target.pfd != null )
                target.pfd.close();
        }
        catch(Exception ignored) {
        }
        try {
            if( target.uri != null )
                context.getContentResolver().delete(target.uri, null, null);
            if( target.legacyFile != null && target.legacyFile.exists() )
                target.legacyFile.delete();
        }
        catch(Exception ignored) {
        }
    }

    public static void saveText(Context context, String displayName, String text) {
        try {
            File dir = context.getExternalFilesDir(null);
            if( dir == null )
                return;
            File file = new File(dir, displayName);
            try( FileOutputStream out = new FileOutputStream(file) ) {
                out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        catch(Exception e) {
            StudioLog.e("save text failed", e);
        }
    }

    private static ContentValues imageValues(Context context, String displayName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + folderName(context));
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        return values;
    }

    private static File legacyDir(Context context) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), folderName(context));
        if( !dir.exists() )
            dir.mkdirs();
        return dir;
    }
}
