package app.fieldtranslator;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The speech and translation models ship inside the APK (assets/asr, assets/mt).
 * Both engines need real files, so on first launch they are copied once into
 * the app's private storage. Runs on a background thread.
 */
final class ModelInstaller {
    interface Progress { void update(long copiedBytes, long totalBytes); }

    private static final String[] DIRS = {"asr/ru-zipformer", "asr/en", "mt/ru-en", "mt/en-ru", "learn"};

    static File root(Context c) { return new File(c.getFilesDir(), "models"); }

    static void ensureInstalled(Context c, Progress progress) throws IOException {
        File root = root(c);
        File marker = new File(root, ".installed");
        String version = versionTag(c);
        if (marker.isFile() && version.equals(readSmall(marker))) return;

        AssetManager am = c.getAssets();
        long total = readTotal(am);
        long free = c.getFilesDir().getUsableSpace();
        if (total > 0 && free < total + 30L * 1024 * 1024) {
            throw new IOException(String.format(java.util.Locale.US,
                    "Not enough storage. Need about %d MB free, have %d MB.",
                    (total >> 20) + 30, free >> 20));
        }

        deleteRecursive(root);
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("cannot create " + root);
        long[] copied = {0};
        byte[] buf = new byte[256 * 1024];
        for (String d : DIRS) {
            if (am.list(d) == null || am.list(d).length == 0) {
                throw new IOException("Model folder missing from the app: " + d +
                        " (the APK was built without models)");
            }
            copyAssetDir(am, d, new File(root, d), buf, copied, total, progress);
        }
        try (OutputStream o = new FileOutputStream(marker)) {
            o.write(version.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyAssetDir(AssetManager am, String path, File dest, byte[] buf,
                                     long[] copied, long total, Progress progress) throws IOException {
        String[] children = am.list(path);
        if (children == null || children.length == 0) {
            // a file
            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir " + parent);
            File tmp = new File(dest.getPath() + ".part");
            try (InputStream in = am.open(path, AssetManager.ACCESS_STREAMING);
                 OutputStream out = new FileOutputStream(tmp)) {
                int n;
                long lastReport = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    copied[0] += n;
                    if (copied[0] - lastReport > 2 * 1024 * 1024) {
                        lastReport = copied[0];
                        progress.update(copied[0], total);
                    }
                }
            }
            if (!tmp.renameTo(dest)) throw new IOException("rename " + tmp);
            return;
        }
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("mkdir " + dest);
        for (String child : children) {
            copyAssetDir(am, path + "/" + child, new File(dest, child), buf, copied, total, progress);
        }
    }

    private static long readTotal(AssetManager am) {
        try (InputStream in = am.open("models_size.txt")) {
            return Long.parseLong(readAll(in).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String versionTag(Context c) {
        try {
            long t = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
            return Long.toString(t);
        } catch (Exception e) {
            return "0";
        }
    }

    private static String readSmall(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readAll(in).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String l;
        while ((l = r.readLine()) != null) b.append(l).append('\n');
        return b.toString();
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
