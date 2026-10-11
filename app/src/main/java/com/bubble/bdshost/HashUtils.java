package com.bubble.bdshost;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

public final class HashUtils {
    private HashUtils() {}

    public static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[1024 * 128];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
