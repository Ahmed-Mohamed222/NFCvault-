package com.nfcvault;

import java.util.Locale;

/** Small, Android-independent helpers shared by NFC input and export flows. */
final class NfcDataUtils {
    private NfcDataUtils() {}

    static byte[] decodeHex(String value) {
        if (value == null) throw new IllegalArgumentException("Hex data is required");
        String clean = value.replaceAll("[^0-9A-Fa-f]", "");
        if (clean.isEmpty() || clean.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex data must contain complete bytes");
        }
        byte[] output = new byte[clean.length() / 2];
        for (int i = 0; i < clean.length(); i += 2) {
            int high = Character.digit(clean.charAt(i), 16);
            int low = Character.digit(clean.charAt(i + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid hex data");
            output[i / 2] = (byte) ((high << 4) | low);
        }
        return output;
    }

    static String sanitizeJsonFileName(String name) {
        String safe = name == null || name.trim().isEmpty()
                ? "nfc-vault-export.json" : name.trim();
        safe = safe.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!safe.toLowerCase(Locale.US).endsWith(".json")) safe += ".json";
        return safe.length() > 96 ? safe.substring(0, 91) + ".json" : safe;
    }
}
