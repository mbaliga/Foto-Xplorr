package com.fotoxplorr.app;

import android.net.Uri;

public final class MediaItem {
    public final long id;
    public final Uri uri;
    public final String name;
    public final String mime;
    public final String folder;
    public final long takenAt;
    public final long size;
    public final int width;
    public final int height;
    public double latitude = Double.NaN;
    public double longitude = Double.NaN;

    public MediaItem(long id, Uri uri, String name, String mime, String folder,
                     long takenAt, long size, int width, int height) {
        this.id = id;
        this.uri = uri;
        this.name = name == null ? "Untitled" : name;
        this.mime = mime == null ? "application/octet-stream" : mime;
        this.folder = folder == null || folder.isBlank() ? "Other" : folder;
        this.takenAt = takenAt;
        this.size = size;
        this.width = width;
        this.height = height;
    }

    public boolean isAnimated() {
        return mime.equals("image/gif") || mime.equals("image/webp") || mime.equals("image/avif");
    }

    public boolean hasLocation() {
        return !Double.isNaN(latitude) && !Double.isNaN(longitude);
    }
}
