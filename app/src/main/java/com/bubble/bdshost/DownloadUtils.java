package com.bubble.bdshost;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class DownloadUtils {
    public interface Progress {
        void onProgress(long done, long total);
    }

    private DownloadUtils() {}

    public static void download(String url, File dest, Progress progress) throws Exception {
        File part = new File(dest.getAbsolutePath() + ".part");
        if (part.exists() && !part.delete()) throw new IllegalStateException("Não foi possível limpar download parcial");
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "BubbleBDSHost/0.1");
        c.connect();
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new IllegalStateException("HTTP " + code + " ao baixar " + url);
        }

        long total = c.getContentLengthLong();
        long done = 0;
        try (BufferedInputStream in = new BufferedInputStream(c.getInputStream(), 128 * 1024);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(part), 128 * 1024)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                out.write(buf, 0, n);
                done += n;
                if (progress != null) progress.onProgress(done, total);
            }
        } finally {
            c.disconnect();
        }

        if (dest.exists() && !dest.delete()) throw new IllegalStateException("Não foi possível substituir " + dest);
        if (!part.renameTo(dest)) throw new IllegalStateException("Falha ao concluir download: " + dest);
    }
}
