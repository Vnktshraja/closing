package com.vnkst.closing.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

public class ChecksumGenerator {

    private static final String SHA_256 = "SHA-256";

    public static String generateChecksum(String secret, String timestamp) {
        try {
            String input = secret + timestamp;
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    public static String getLatestTimestampinEpochSeconds() {
        return String.format("%d", Instant.now().getEpochSecond());
    }
}

