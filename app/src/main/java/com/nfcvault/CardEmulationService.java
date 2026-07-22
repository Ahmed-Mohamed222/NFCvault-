package com.nfcvault;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.Charset;
import java.util.Arrays;

public class CardEmulationService extends HostApduService {

    private static final String TAG = "CardEmulationService";

    // Standard NDEF Type 4 Tag AID (Application Identifier)
    private static final byte[] NDEF_AID = {
            (byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01
    };

    // APDU commands
    private static final byte[] SELECT_CC_FILE_CMD = {
            0x00, (byte) 0xA4, 0x00, 0x0C, 0x02, (byte) 0xE1, 0x03
    };
    private static final byte[] SELECT_NDEF_FILE_CMD = {
            0x00, (byte) 0xA4, 0x00, 0x0C, 0x02, (byte) 0xE1, 0x04
    };

    // Standard APDU success response
    private static final byte[] SUCCESS_SW = { (byte) 0x90, 0x00 };
    private static final byte[] FAILURE_SW = { 0x6A, (byte) 0x82 };
    private static final byte[] WRONG_LENGTH_SW = { 0x67, 0x00 };
    private static final byte[] WRONG_OFFSET_SW = { 0x6B, 0x00 };
    private static final int MAX_NDEF_SIZE = 4095;

    // Capability Container (CC) File data for NDEF Type 4
    private static final byte[] CC_FILE = {
            0x00, 0x0F, // CCLEN: 15 bytes
            0x20,       // Mapping Version
            0x00, 0x3A, // MLe: 58 bytes max read
            0x00, 0x34, // MLc: 52 bytes max write
            0x04,       // T (NDEF File Control TLV)
            0x06,       // L
            (byte) 0xE1, 0x04, // File Identifier
            0x0F, (byte) 0xFF, // Max NDEF Size (4095 bytes)
            0x00,       // Read Access (0x00 = free)
            (byte) 0xFF // Write Access (0xFF = none)
    };

    // NDEF Message (Default empty, updated dynamically)
    private static volatile byte[] ndefMessage = new byte[0];
    private boolean ccSelected = false;
    private boolean ndefSelected = false;

    // Static setter to pass emulated data from NfcVaultActivity
    public static void setEmulationData(String jsonData) {
        try {
            if (jsonData == null || jsonData.isEmpty()) {
                ndefMessage = new byte[0];
                return;
            }
            JSONObject card = new JSONObject(jsonData);

            // Preferred: use the exact raw NDEF message captured at read time.
            if (card.has("ndefMessageHex")) {
                String hex = card.getString("ndefMessageHex").replaceAll("[^0-9A-Fa-f]", "");
                if (hex.length() >= 2 && hex.length() % 2 == 0) {
                    byte[] candidate = hexToBytes(hex);
                    ndefMessage = candidate.length <= MAX_NDEF_SIZE ? candidate : new byte[0];
                    return;
                }
            }

            // Fallback: rebuild an NDEF message from parsed records (URI / TEXT).
            if (card.has("ndefRecords")) {
                byte[] rebuilt = buildFromRecords(card.getJSONArray("ndefRecords"));
                ndefMessage = (rebuilt != null && rebuilt.length <= MAX_NDEF_SIZE)
                        ? rebuilt : new byte[0];
                return;
            }

            ndefMessage = new byte[0];
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse emulation data", e);
            ndefMessage = new byte[0];
        }
    }

    /** Reconstruct an NDEF message from parsed URI/TEXT records when no raw hex is stored. */
    private static byte[] buildFromRecords(JSONArray records) {
        try {
            java.util.ArrayList<NdefRecord> list = new java.util.ArrayList<>();
            for (int i = 0; i < records.length(); i++) {
                JSONObject r = records.getJSONObject(i);
                String type = r.optString("recordType", "");
                String decoded = r.optString("decoded", "");
                if ("URI".equalsIgnoreCase(type)) {
                    list.add(NdefRecord.createUri(decoded));
                } else if ("TEXT".equalsIgnoreCase(type)) {
                    list.add(NdefRecord.createTextRecord("en", decoded));
                } else if ("MIME".equalsIgnoreCase(type) && r.has("mimeType")) {
                    list.add(NdefRecord.createMime(r.getString("mimeType"),
                        decoded.getBytes(Charset.forName("UTF-8"))));
                }
            }
            if (list.isEmpty()) return null;
            return new NdefMessage(list.toArray(new NdefRecord[0])).toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "buildFromRecords failed", e);
            return null;
        }
    }

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        if (commandApdu == null || commandApdu.length < 4) return WRONG_LENGTH_SW;

        // SELECT NDEF Application
        if (isSelectNdefApplication(commandApdu)) {
            ccSelected = false;
            ndefSelected = false;
            return SUCCESS_SW;
        }
        // SELECT CC File
        else if (Arrays.equals(SELECT_CC_FILE_CMD, commandApdu)) {
            ccSelected = true;
            ndefSelected = false;
            return SUCCESS_SW;
        }
        // SELECT NDEF File
        else if (Arrays.equals(SELECT_NDEF_FILE_CMD, commandApdu)) {
            ccSelected = false;
            ndefSelected = true;
            return SUCCESS_SW;
        }
        // READ BINARY
        else if (commandApdu[0] == 0x00 && commandApdu[1] == (byte) 0xB0) {
            if (commandApdu.length < 5) return WRONG_LENGTH_SW;
            int offset = ((commandApdu[2] & 0xFF) << 8) | (commandApdu[3] & 0xFF);
            int length = commandApdu[4] & 0xFF;
            if (length == 0) length = 256;

            if (ccSelected) {
                if (offset > CC_FILE.length) return WRONG_OFFSET_SW;
                length = Math.min(length, CC_FILE.length - offset);
                byte[] response = new byte[length + 2];
                System.arraycopy(CC_FILE, offset, response, 0, length);
                System.arraycopy(SUCCESS_SW, 0, response, length, 2);
                return response;
            } else if (ndefSelected) {
                // NDEF File structure: 2 bytes length + NDEF message
                byte[] messageSnapshot = ndefMessage;
                byte[] ndefFile = new byte[messageSnapshot.length + 2];
                ndefFile[0] = (byte) ((messageSnapshot.length >> 8) & 0xFF);
                ndefFile[1] = (byte) (messageSnapshot.length & 0xFF);
                System.arraycopy(messageSnapshot, 0, ndefFile, 2, messageSnapshot.length);

                if (offset > ndefFile.length) return WRONG_OFFSET_SW;
                length = Math.min(length, ndefFile.length - offset);
                byte[] response = new byte[length + 2];
                System.arraycopy(ndefFile, offset, response, 0, length);
                System.arraycopy(SUCCESS_SW, 0, response, length, 2);
                return response;
            }
        }

        return FAILURE_SW;
    }

    private static boolean isSelectNdefApplication(byte[] command) {
        if (command.length != 12 && command.length != 13) return false;
        if (command[0] != 0x00 || command[1] != (byte) 0xA4
                || command[2] != 0x04 || command[3] != 0x00
                || (command[4] & 0xFF) != NDEF_AID.length) return false;
        for (int i = 0; i < NDEF_AID.length; i++) {
            if (command[5 + i] != NDEF_AID[i]) return false;
        }
        return command.length == 12 || command[12] == 0x00;
    }

    @Override
    public void onDeactivated(int reason) {
        ccSelected = false;
        ndefSelected = false;
    }

    private static byte[] hexToBytes(String hex) {
        return NfcDataUtils.decodeHex(hex);
    }
}
