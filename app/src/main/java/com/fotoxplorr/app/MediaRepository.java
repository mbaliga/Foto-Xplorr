package com.fotoxplorr.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class MediaRepository {
    private final Context context;

    public MediaRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<MediaItem> scan(String query) {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[] {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
        };
        String selection = null;
        String[] args = null;
        if (query != null && !query.isBlank()) {
            selection = MediaStore.Images.Media.DISPLAY_NAME + " LIKE ? OR "
                    + MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " LIKE ?";
            String term = "%" + query.trim() + "%";
            args = new String[] { term, term };
        }
        List<MediaItem> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(collection, projection, selection, args,
                MediaStore.Images.Media.DATE_TAKEN + " DESC, " + MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) return result;
            int id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int name = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int mime = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE);
            int folder = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            int taken = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);
            int added = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
            int size = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
            int width = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH);
            int height = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT);
            while (cursor.moveToNext()) {
                long mediaId = cursor.getLong(id);
                long timestamp = cursor.getLong(taken);
                if (timestamp <= 0) timestamp = cursor.getLong(added) * 1000L;
                MediaItem item = new MediaItem(mediaId, ContentUris.withAppendedId(collection, mediaId),
                        cursor.getString(name), cursor.getString(mime), cursor.getString(folder), timestamp,
                        cursor.getLong(size), cursor.getInt(width), cursor.getInt(height));
                readLocation(item);
                result.add(item);
            }
        } catch (SecurityException ignored) {
            return Collections.emptyList();
        }
        result.sort(Comparator.comparingLong((MediaItem item) -> item.takenAt).reversed());
        return result;
    }

    private void readLocation(MediaItem item) {
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(item.uri, "r")) {
            if (descriptor == null) return;
            ExifInterface exif = new ExifInterface(descriptor.getFileDescriptor());
            float[] location = new float[2];
            if (exif.getLatLong(location)) {
                item.latitude = location[0];
                item.longitude = location[1];
            }
        } catch (Exception ignored) { }
    }
}
