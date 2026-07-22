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
    public void sanitizeJsonFileNameRemovesUnsafeCharacters() {
        assertEquals("hotel_key_12.json", NfcDataUtils.sanitizeJsonFileName("hotel:key*12"));
        assertEquals("nfc-vault-export.json", NfcDataUtils.sanitizeJsonFileName("  "));
    }
}
