package com.lightchat.util;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class MediaStore {
    private MediaStore() {}

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "media");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String fileName(String key) {
        if (key == null) return "media";
        return key.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static File localFile(Context ctx, String key) {
        return new File(dir(ctx), fileName(key));
    }

    public static boolean exists(Context ctx, String key) {
        return key != null && localFile(ctx, key).exists();
    }

    public static void save(Context ctx, String key, byte[] data) throws IOException {
        File f = localFile(ctx, key);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }

    public static void delete(Context ctx, String key) {
        File f = localFile(ctx, key);
        if (f.exists()) f.delete();
    }

    public static int clearCached(Context ctx) {
        File d = dir(ctx);
        File[] files = d.listFiles();
        int n = 0;
        if (files != null) {
            for (File f : files) {
                if (f.delete()) n++;
            }
        }
        return n;
    }
}