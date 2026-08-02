package com.fotoxplorr.app;

import android.content.Context;
import android.net.Uri;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class BackupExporter {
    private BackupExporter() { }

    public static void write(Context context, Uri target, CatalogStore store) throws Exception {
        try (OutputStream output = context.getContentResolver().openOutputStream(target, "w")) {
            if (output == null) throw new IllegalStateException("Unable to open backup destination");
            output.write(store.exportJson().toString(2).getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }
}
