package com.nfcvault;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class NfcDataUtilsTest {
    @Test
    public void decodeHexAcceptsCommonSeparators() {
        assertArrayEquals(
                new byte[]{(byte) 0xD2, 0x76, 0x00, 0x01},
                NfcDataUtils.decodeHex("D2:76 00-01")
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void decodeHexRejectsIncompleteByte() {
        NfcDataUtils.decodeHex("ABC");
    }

    @Test
    public void ultralightPageCountMatchesChipLayout() {
        assertEquals(45, NfcDataUtils.ultralightPageCount("NTAG 213"));
        assertEquals(231, NfcDataUtils.ultralightPageCount("NTAG 216"));
        assertEquals(48, NfcDataUtils.ultralightPageCount("MIFARE Ultralight C"));
        assertEquals(16, NfcDataUtils.ultralightPageCount("MIFARE Ultralight"));
        assertEquals(20, NfcDataUtils.ultralightPageCount("MIFARE Ultralight EV1 (48 bytes)"));
        assertEquals(20, NfcDataUtils.ultralightPageCount("NTAG / Ultralight", 48));
        assertEquals(231, NfcDataUtils.ultralightPageCount("NTAG / Ultralight", 888));
        // A recognized original Ultralight must not be overridden by its 48-byte CC area.
        assertEquals(16, NfcDataUtils.ultralightPageCount("MIFARE Ultralight", 48));
        // Unknown chips use the smallest safe layout rather than risking rollover garbage.
        assertEquals(16, NfcDataUtils.ultralightPageCount("NTAG / Ultralight"));
        assertEquals(16, NfcDataUtils.ultralightPageCount(null));
    }

    @Test
    public void sanitizeJsonFileNameRemovesUnsafeCharacters() {
        assertEquals("hotel_key_12.json", NfcDataUtils.sanitizeJsonFileName("hotel:key*12"));
        assertEquals("nfc-vault-export.json", NfcDataUtils.sanitizeJsonFileName("  "));
    }
}
