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

    /**
     * Total readable pages for an Ultralight/NTAG variant. Reads past the last page can
     * wrap around to page 0 instead of failing, so the page loop needs a real upper bound.
     */
    static int ultralightPageCount(String tagType) {
        return ultralightPageCount(tagType, 0);
    }

    static int ultralightPageCount(String tagType, int capabilityDataSizeBytes) {
        String name = tagType == null ? "" : tagType;
        if (name.startsWith("NTAG 210")) return 20;
        if (name.startsWith("NTAG 212")) return 41;
        if (name.startsWith("NTAG 213")) return 45;
        if (name.startsWith("NTAG 215")) return 135;
        if (name.startsWith("NTAG 216")) return 231;
        if (name.startsWith("MIFARE Ultralight C")) return 48;
        if (name.startsWith("MIFARE Ultralight EV1 (48")) return 20;
        if (name.startsWith("MIFARE Ultralight EV1 (128")) return 41;
        if (name.equals("MIFARE Ultralight")) return 16;

        // The NFC Forum capability container describes the NDEF data area. Use it only
        // when the platform and GET_VERSION did not identify an exact chip variant.
        switch (capabilityDataSizeBytes) {
            case 48:  return 20;
            case 128: return 41;
            case 144: return 45;
            case 504: return 135;
            case 888: return 231;
            default:  return 16; // Smallest safe layout; never guess past the physical end.
        }
    }

    static String sanitizeJsonFileName(String name) {
        String safe = name == null || name.trim().isEmpty()
                ? "nfc-vault-export.json" : name.trim();
        safe = safe.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!safe.toLowerCase(Locale.US).endsWith(".json")) safe += ".json";
        return safe.length() > 96 ? safe.substring(0, 91) + ".json" : safe;
    }
}
