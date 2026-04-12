package com.nfcvault;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;

public class NfcVaultActivity extends Activity {

    private static final String TAG = "NfcVault";

    // Extended MIFARE Classic key dictionary
    private static final byte[][] KEY_DICT = {
        MifareClassic.KEY_DEFAULT,                            // FF FF FF FF FF FF
        MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY,       // A0 A1 A2 A3 A4 A5
        MifareClassic.KEY_NFC_FORUM,                          // D3 F7 D3 F7 D3 F7
        hexToBytes("000000000000"),
        hexToBytes("A0B0C0D0E0F0"),
        hexToBytes("AABBCCDDEEFF"),
        hexToBytes("4D3A99C351DD"),
        hexToBytes("1A982C7E459A"),
        hexToBytes("714C5C886E97"),
        hexToBytes("587EE5F9350F"),
        hexToBytes("107B4B303784"),                           // Miwa Lock
        hexToBytes("414C41524F4E"),                           // ALARON
    };

    private NfcAdapter nfcAdapter;
    private WebView webView;
    private PendingIntent pendingIntent;
    private IntentFilter[] intentFilters;
    private String[][] techLists;
    private volatile boolean writeMode = false;
    private volatile String pendingWriteJson = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        webView = findViewById(R.id.webView);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new NfcBridge(), "NfcBridge");
        webView.loadUrl("file:///android_asset/www/index.html");

        pendingIntent = PendingIntent.getActivity(this, 0,
            new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        intentFilters = new IntentFilter[]{
            new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        };

        // Register all tech types for foreground dispatch
        techLists = new String[][]{
            { MifareClassic.class.getName() },
            { MifareUltralight.class.getName() },
            { Ndef.class.getName() },
            { IsoDep.class.getName() },
            { NfcA.class.getName() },
            { NfcB.class.getName() },
            { NfcF.class.getName() },
            { NfcV.class.getName() }
        };

        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!NfcAdapter.ACTION_TECH_DISCOVERED.equals(action) &&
            !NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) &&
            !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) return;

        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) return;

        if (writeMode && pendingWriteJson != null) {
            Thread t = new Thread(() -> writeCard(tag));
            t.setDaemon(true);
            t.start();
        } else {
            Thread t = new Thread(() -> readCard(tag));
            t.setDaemon(true);
            t.start();
        }
    }

    // ========================================================================
    //  UNIVERSAL CARD READER — auto-detects and reads ANY NFC card type
    // ========================================================================
    private void readCard(Tag tag) {
        try {
            JSONObject card = new JSONObject();

            // Base fields present on ALL tags
            byte[] tagId = tag.getId();
            String[] techList = tag.getTechList();

            card.put("serial", bytesToHex(tagId, ":"));
            card.put("rawId", bytesToHex(tagId, ""));
            card.put("scannedAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                java.util.Locale.US).format(new java.util.Date()));

            JSONArray techArr = new JSONArray();
            for (String tech : techList) {
                techArr.put(tech.substring(tech.lastIndexOf('.') + 1));
            }
            card.put("technologies", techArr);

            // Detect manufacturer from first byte of tag ID (NXP = 0x04)
            if (tagId.length > 0) {
                card.put("manufacturer", getManufacturer(tagId[0] & 0xFF));
            }

            // ==== AUTO-DETECT card type and branch ====

            MifareClassic mfc = MifareClassic.get(tag);
            MifareUltralight mfu = MifareUltralight.get(tag);
            Ndef ndef = Ndef.get(tag);
            IsoDep isoDep = IsoDep.get(tag);
            NfcV nfcV = NfcV.get(tag);
            NfcF nfcF = NfcF.get(tag);
            NfcA nfcA = NfcA.get(tag);
            NfcB nfcB = NfcB.get(tag);

            if (mfc != null) {
                readMifareClassic(mfc, tag, card);
            } else if (mfu != null) {
                readMifareUltralight(mfu, tag, card);
            } else if (ndef != null) {
                readNdef(ndef, tag, card);
            } else if (isoDep != null) {
                readIsoDep(isoDep, tag, card);
            } else if (nfcV != null) {
                readNfcV(nfcV, tag, card);
            } else if (nfcF != null) {
                readNfcF(nfcF, tag, card);
            } else if (nfcA != null) {
                readNfcAGeneric(nfcA, tag, card);
            } else if (nfcB != null) {
                readNfcBGeneric(nfcB, tag, card);
            } else {
                card.put("tagType", "Unknown NFC Tag");
                card.put("cardType", "generic");
            }

            notifyJS("onCardRead", card.toString());

        } catch (Exception e) {
            Log.e(TAG, "Read error", e);
            notifyJS("onReadError", e.getMessage() != null ? e.getMessage() : "Unknown read error");
        }
    }

    // ---- MIFARE Classic ----
    private void readMifareClassic(MifareClassic mfc, Tag tag, JSONObject card) throws Exception {
        try {
            mfc.connect();
            int sizeKb = mfc.getSize() / 1024;
            card.put("tagType", "MIFARE Classic " + sizeKb + "K");
            card.put("cardType", "mifare_classic");
            card.put("manufacturer", "NXP - MIFARE Classic " + sizeKb + "K");

            // NfcA info
            NfcA nfcA = NfcA.get(tag);
            if (nfcA != null) {
                card.put("atqa", "0x" + bytesToHex(nfcA.getAtqa(), ""));
                card.put("sak", "0x" + String.format("%02X", nfcA.getSak()));
            }

            JSONObject mem = new JSONObject();
            mem.put("total", mfc.getSize());
            mem.put("sectors", mfc.getSectorCount());
            mem.put("blocksPerSector", 4);
            mem.put("bytesPerBlock", 16);
            card.put("memory", mem);

            JSONArray sectorsArr = new JSONArray();
            for (int s = 0; s < mfc.getSectorCount(); s++) {
                JSONObject sector = new JSONObject();
                sector.put("sector", s);
                boolean authOk = false;
                String usedKey = "";
                for (byte[] key : KEY_DICT) {
                    try {
                        if (mfc.authenticateSectorWithKeyA(s, key)) {
                            authOk = true;
                            usedKey = bytesToHex(key, " ");
                            break;
                        }
                    } catch (IOException ignored) {}
                    try {
                        if (mfc.authenticateSectorWithKeyB(s, key)) {
                            authOk = true;
                            usedKey = bytesToHex(key, " ");
                            break;
                        }
                    } catch (IOException ignored) {}
                }
                sector.put("accessible", authOk);
                if (authOk) sector.put("keyUsed", usedKey);

                JSONArray blocks = new JSONArray();
                int firstBlock = mfc.sectorToBlock(s);
                int blockCount = mfc.getBlockCountInSector(s);
                for (int b = 0; b < blockCount; b++) {
                    JSONObject block = new JSONObject();
                    int bi = firstBlock + b;
                    block.put("block", bi);
                    block.put("isTrailer", b == blockCount - 1);
                    if (authOk) {
                        try {
                            block.put("data", bytesToHex(mfc.readBlock(bi), " "));
                        } catch (IOException e) {
                            block.put("data", "-- READ ERROR --");
                        }
                    } else {
                        block.put("data", "-- AUTH FAILED --");
                    }
                    blocks.put(block);
                }
                sector.put("blocks", blocks);
                sectorsArr.put(sector);
            }
            card.put("sectors", sectorsArr);
        } finally {
            try { mfc.close(); } catch (IOException ignored) {}
        }
    }

    // ---- MIFARE Ultralight / NTAG ----
    private void readMifareUltralight(MifareUltralight mfu, Tag tag, JSONObject card) throws Exception {
        try {
            mfu.connect();

            int type = mfu.getType();
            String subType;
            switch (type) {
                case MifareUltralight.TYPE_ULTRALIGHT:
                    subType = "MIFARE Ultralight";
                    break;
                case MifareUltralight.TYPE_ULTRALIGHT_C:
                    subType = "MIFARE Ultralight C";
                    break;
                default:
                    subType = "NTAG / Ultralight";
                    break;
            }
            card.put("tagType", subType);
            card.put("cardType", "mifare_ultralight");

            // NfcA info
            NfcA nfcA = NfcA.get(tag);
            if (nfcA != null) {
                card.put("atqa", "0x" + bytesToHex(nfcA.getAtqa(), ""));
                card.put("sak", "0x" + String.format("%02X", nfcA.getSak()));
            }

            // Read pages — Ultralight reads 4 pages (16 bytes) at a time
            JSONArray pagesArr = new JSONArray();
            int maxPages = 44; // NTAG216 max; will stop on IOException
            for (int p = 0; p < maxPages; p += 4) {
                try {
                    byte[] data = mfu.readPages(p);
                    // data contains 4 pages = 16 bytes
                    for (int i = 0; i < 4 && (p + i) < maxPages; i++) {
                        JSONObject pageObj = new JSONObject();
                        pageObj.put("page", p + i);
                        byte[] pageData = new byte[4];
                        System.arraycopy(data, i * 4, pageData, 0, 4);
                        pageObj.put("data", bytesToHex(pageData, " "));
                        pagesArr.put(pageObj);
                    }
                } catch (IOException e) {
                    // Reached end of readable pages
                    break;
                }
            }
            card.put("pages", pagesArr);
            card.put("totalPages", pagesArr.length());

            // Try to detect NTAG type from capability container (page 3)
            if (pagesArr.length() > 3) {
                try {
                    String p3 = pagesArr.getJSONObject(3).getString("data").replaceAll(" ", "");
                    if (p3.length() >= 4) {
                        int ccSize = Integer.parseInt(p3.substring(4, 6), 16) * 8;
                        if (ccSize <= 144) card.put("tagType", "NTAG 213");
                        else if (ccSize <= 504) card.put("tagType", "NTAG 215");
                        else card.put("tagType", "NTAG 216");
                    }
                } catch (Exception ignored) {}
            }

            // Also try to read NDEF from this tag
            Ndef ndefTech = Ndef.get(tag);
            if (ndefTech != null) {
                try {
                    ndefTech.connect();
                    NdefMessage msg = ndefTech.getNdefMessage();
                    if (msg != null) {
                        card.put("ndefRecords", parseNdefMessage(msg));
                    }
                    ndefTech.close();
                } catch (Exception ignored) {}
            }
        } finally {
            try { mfu.close(); } catch (IOException ignored) {}
        }
    }

    // ---- NDEF ----
    private void readNdef(Ndef ndef, Tag tag, JSONObject card) throws Exception {
        try {
            ndef.connect();
            card.put("tagType", "NDEF");
            card.put("cardType", "ndef");
            card.put("ndefType", ndef.getType());
            card.put("maxSize", ndef.getMaxSize());
            card.put("isWritable", ndef.isWritable());

            NdefMessage msg = ndef.getNdefMessage();
            if (msg != null) {
                card.put("ndefRecords", parseNdefMessage(msg));
                card.put("messageSize", msg.toByteArray().length);
            } else {
                card.put("ndefRecords", new JSONArray());
                card.put("messageSize", 0);
            }
        } finally {
            try { ndef.close(); } catch (IOException ignored) {}
        }
    }

    // ---- ISO-DEP / Smart Card ----
    private void readIsoDep(IsoDep isoDep, Tag tag, JSONObject card) throws Exception {
        try {
            isoDep.connect();
            card.put("tagType", "ISO-DEP / Smart Card");
            card.put("cardType", "isodep");

            byte[] hist = isoDep.getHistoricalBytes();
            if (hist != null) {
                card.put("historicalBytes", bytesToHex(hist, " "));
            }

            byte[] hiLayerResp = isoDep.getHiLayerResponse();
            if (hiLayerResp != null) {
                card.put("hiLayerResponse", bytesToHex(hiLayerResp, " "));
            }

            card.put("maxTransceiveLength", isoDep.getMaxTransceiveLength());
            card.put("timeout", isoDep.getTimeout());

            // Try SELECT APDU
            JSONArray apdus = new JSONArray();
            try {
                byte[] selectCmd = new byte[]{0x00, (byte) 0xA4, 0x04, 0x00, 0x00};
                byte[] response = isoDep.transceive(selectCmd);
                JSONObject apdu = new JSONObject();
                apdu.put("command", "SELECT (no AID)");
                apdu.put("sent", bytesToHex(selectCmd, " "));
                apdu.put("response", bytesToHex(response, " "));
                apdu.put("sw", bytesToHex(Arrays.copyOfRange(response, Math.max(0, response.length - 2), response.length), ""));
                apdus.put(apdu);
            } catch (Exception ignored) {}

            card.put("apdus", apdus);

            // NfcA and NfcB info
            NfcA nfcA = NfcA.get(tag);
            if (nfcA != null) {
                card.put("atqa", "0x" + bytesToHex(nfcA.getAtqa(), ""));
                card.put("sak", "0x" + String.format("%02X", nfcA.getSak()));
            }
            NfcB nfcB = NfcB.get(tag);
            if (nfcB != null) {
                byte[] appData = nfcB.getApplicationData();
                if (appData != null) card.put("applicationData", bytesToHex(appData, " "));
                byte[] protInfo = nfcB.getProtocolInfo();
                if (protInfo != null) card.put("protocolInfo", bytesToHex(protInfo, " "));
            }
        } finally {
            try { isoDep.close(); } catch (IOException ignored) {}
        }
    }

    // ---- NFC-V / ISO 15693 ----
    private void readNfcV(NfcV nfcV, Tag tag, JSONObject card) throws Exception {
        try {
            nfcV.connect();
            card.put("tagType", "NFC-V / ISO 15693");
            card.put("cardType", "nfcv");
            card.put("dsfId", "0x" + String.format("%02X", nfcV.getDsfId()));
            card.put("responseFlags", "0x" + String.format("%02X", nfcV.getResponseFlags()));
            card.put("maxTransceiveLength", nfcV.getMaxTransceiveLength());

            // Try to read blocks
            JSONArray blocks = new JSONArray();
            for (int i = 0; i < 64; i++) {
                try {
                    // READ_SINGLE_BLOCK command: flags=0x20, cmd=0x20, block number
                    byte[] cmd = new byte[]{0x20, 0x20, (byte) i};
                    byte[] resp = nfcV.transceive(cmd);
                    if (resp.length > 1 && resp[0] == 0x00) {
                        JSONObject block = new JSONObject();
                        block.put("block", i);
                        block.put("data", bytesToHex(Arrays.copyOfRange(resp, 1, resp.length), " "));
                        blocks.put(block);
                    } else {
                        break;
                    }
                } catch (Exception e) {
                    break;
                }
            }
            card.put("blocks", blocks);
            card.put("totalBlocks", blocks.length());
        } finally {
            try { nfcV.close(); } catch (IOException ignored) {}
        }
    }

    // ---- NFC-F / FeliCa ----
    private void readNfcF(NfcF nfcF, Tag tag, JSONObject card) throws Exception {
        try {
            nfcF.connect();
            card.put("tagType", "NFC-F / FeliCa");
            card.put("cardType", "nfcf");

            byte[] idm = tag.getId();
            card.put("idm", bytesToHex(idm, " "));

            byte[] pmm = nfcF.getManufacturer();
            if (pmm != null) {
                card.put("pmm", bytesToHex(pmm, " "));
            }

            byte[] systemCode = nfcF.getSystemCode();
            if (systemCode != null) {
                card.put("systemCode", bytesToHex(systemCode, " "));
            }

            card.put("maxTransceiveLength", nfcF.getMaxTransceiveLength());
        } finally {
            try { nfcF.close(); } catch (IOException ignored) {}
        }
    }

    // ---- NFC-A Generic ----
    private void readNfcAGeneric(NfcA nfcA, Tag tag, JSONObject card) throws Exception {
        try {
            nfcA.connect();
            card.put("tagType", "NFC-A / ISO 14443-3A");
            card.put("cardType", "nfca");
            card.put("atqa", "0x" + bytesToHex(nfcA.getAtqa(), ""));
            card.put("sak", "0x" + String.format("%02X", nfcA.getSak()));
            card.put("maxTransceiveLength", nfcA.getMaxTransceiveLength());
        } finally {
            try { nfcA.close(); } catch (IOException ignored) {}
        }
    }

    // ---- NFC-B Generic ----
    private void readNfcBGeneric(NfcB nfcB, Tag tag, JSONObject card) throws Exception {
        try {
            nfcB.connect();
            card.put("tagType", "NFC-B / ISO 14443-3B");
            card.put("cardType", "nfcb");

            byte[] appData = nfcB.getApplicationData();
            if (appData != null) card.put("applicationData", bytesToHex(appData, " "));

            byte[] protInfo = nfcB.getProtocolInfo();
            if (protInfo != null) card.put("protocolInfo", bytesToHex(protInfo, " "));

            card.put("maxTransceiveLength", nfcB.getMaxTransceiveLength());
        } finally {
            try { nfcB.close(); } catch (IOException ignored) {}
        }
    }

    // ========================================================================
    //  NDEF RECORD PARSER
    // ========================================================================
    private JSONArray parseNdefMessage(NdefMessage msg) throws Exception {
        JSONArray records = new JSONArray();
        for (NdefRecord rec : msg.getRecords()) {
            JSONObject r = new JSONObject();
            short tnf = rec.getTnf();
            r.put("tnf", tnf);

            byte[] payload = rec.getPayload();
            byte[] recType = rec.getType();
            r.put("typeHex", bytesToHex(recType, " "));
            r.put("payloadHex", bytesToHex(payload, " "));
            r.put("payloadSize", payload.length);

            switch (tnf) {
                case NdefRecord.TNF_WELL_KNOWN:
                    if (Arrays.equals(recType, NdefRecord.RTD_URI)) {
                        r.put("recordType", "URI");
                        r.put("decoded", parseNdefUri(payload));
                    } else if (Arrays.equals(recType, NdefRecord.RTD_TEXT)) {
                        r.put("recordType", "TEXT");
                        r.put("decoded", parseNdefText(payload));
                    } else if (Arrays.equals(recType, NdefRecord.RTD_SMART_POSTER)) {
                        r.put("recordType", "SMART_POSTER");
                        r.put("decoded", bytesToHex(payload, " "));
                    } else {
                        r.put("recordType", "WELL_KNOWN:" + new String(recType, Charset.forName("US-ASCII")));
                        r.put("decoded", bytesToHex(payload, " "));
                    }
                    break;
                case NdefRecord.TNF_MIME_MEDIA:
                    r.put("recordType", "MIME");
                    r.put("mimeType", new String(recType, Charset.forName("US-ASCII")));
                    r.put("decoded", new String(payload, Charset.forName("UTF-8")));
                    break;
                case NdefRecord.TNF_ABSOLUTE_URI:
                    r.put("recordType", "ABSOLUTE_URI");
                    r.put("decoded", new String(recType, Charset.forName("UTF-8")));
                    break;
                case NdefRecord.TNF_EXTERNAL_TYPE:
                    r.put("recordType", "EXTERNAL");
                    r.put("externalType", new String(recType, Charset.forName("US-ASCII")));
                    r.put("decoded", bytesToHex(payload, " "));
                    break;
                default:
                    r.put("recordType", "UNKNOWN");
                    r.put("decoded", bytesToHex(payload, " "));
                    break;
            }
            records.put(r);
        }
        return records;
    }

    private static final String[] URI_PREFIXES = {
        "", "http://www.", "https://www.", "http://", "https://",
        "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.",
        "ftps://", "sftp://", "smb://", "nfs://", "ftp://", "dav://",
        "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:",
        "sip:", "sips:", "tftp:", "btspp://", "btl2cap://",
        "btgoep://", "tcpobex://", "irdaobex://", "file://",
        "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:",
        "urn:epc:", "urn:nfc:"
    };

    private String parseNdefUri(byte[] payload) {
        if (payload.length < 1) return "";
        int prefix = payload[0] & 0xFF;
        String uri = (prefix < URI_PREFIXES.length ? URI_PREFIXES[prefix] : "");
        uri += new String(payload, 1, payload.length - 1, Charset.forName("UTF-8"));
        return uri;
    }

    private String parseNdefText(byte[] payload) {
        if (payload.length < 1) return "";
        int langLen = payload[0] & 0x3F;
        if (payload.length < 1 + langLen) return "";
        return new String(payload, 1 + langLen, payload.length - 1 - langLen, Charset.forName("UTF-8"));
    }

    // ========================================================================
    //  WRITE CARD (MIFARE Classic only for now)
    // ========================================================================
    private void writeCard(Tag tag) {
        MifareClassic mfc = MifareClassic.get(tag);
        if (mfc == null) {
            notifyJS("onWriteError", "Not a MIFARE Classic card — write not supported for this tag type");
            return;
        }
        try {
            mfc.connect();
            JSONObject card = new JSONObject(pendingWriteJson);
            JSONArray sectors = card.getJSONArray("sectors");
            int written = 0, failed = 0;

            for (int i = 0; i < sectors.length(); i++) {
                JSONObject sector = sectors.getJSONObject(i);
                int s = sector.getInt("sector");
                if (!sector.getBoolean("accessible")) { failed++; continue; }

                boolean authOk = false;
                for (byte[] key : KEY_DICT) {
                    try {
                        if (mfc.authenticateSectorWithKeyA(s, key)) { authOk = true; break; }
                    } catch (IOException ignored) {}
                    try {
                        if (mfc.authenticateSectorWithKeyB(s, key)) { authOk = true; break; }
                    } catch (IOException ignored) {}
                }
                if (!authOk) { failed++; continue; }

                JSONArray blocks = sector.getJSONArray("blocks");
                int firstBlk = mfc.sectorToBlock(s);
                int blkCount = mfc.getBlockCountInSector(s);
                for (int b = 0; b < blkCount - 1; b++) {
                    JSONObject block = blocks.getJSONObject(b);
                    if (block.getBoolean("isTrailer")) continue;
                    String hex = block.getString("data").replaceAll("[^0-9A-Fa-f]", "");
                    if (hex.length() < 32) { failed++; continue; }
                    try {
                        mfc.writeBlock(firstBlk + b, hexToBytes(hex));
                        written++;
                    } catch (IOException e) { failed++; }
                }
            }

            JSONObject result = new JSONObject();
            result.put("written", written);
            result.put("failed", failed);
            notifyJS("onWriteComplete", result.toString());
            writeMode = false;
            pendingWriteJson = null;
        } catch (Exception e) {
            Log.e(TAG, "Write error", e);
            notifyJS("onWriteError", e.getMessage() != null ? e.getMessage() : "Write failed");
        } finally {
            try { mfc.close(); } catch (IOException ignored) {}
        }
    }

    // ========================================================================
    //  JAVASCRIPT BRIDGE
    // ========================================================================
    private class NfcBridge {
        @JavascriptInterface
        public void startReadMode() {
            writeMode = false;
            pendingWriteJson = null;
        }

        @JavascriptInterface
        public void startWriteMode(String cardJson) {
            pendingWriteJson = cardJson;
            writeMode = true;
        }

        @JavascriptInterface
        public boolean isNfcAvailable() {
            return nfcAdapter != null;
        }

        @JavascriptInterface
        public boolean isNfcEnabled() {
            return nfcAdapter != null && nfcAdapter.isEnabled();
        }
    }

    // ========================================================================
    //  JS NOTIFICATION (thread-safe)
    // ========================================================================
    private void notifyJS(String cb, String data) {
        String safe = data
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
        runOnUiThread(() -> webView.evaluateJavascript(
            "window.NfcCallbacks&&window.NfcCallbacks." + cb + "('" + safe + "')", null));
    }

    // ========================================================================
    //  UTILITY METHODS
    // ========================================================================
    private static String bytesToHex(byte[] b, String sep) {
        if (b == null || b.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(sep);
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    private static String getManufacturer(int code) {
        switch (code) {
            case 0x04: return "NXP Semiconductors";
            case 0x02: return "STMicroelectronics";
            case 0x05: return "Infineon Technologies";
            case 0x07: return "Texas Instruments";
            case 0x16: return "EM Microelectronic";
            case 0x01: return "Motorola";
            default:   return "Unknown (0x" + String.format("%02X", code) + ")";
        }
    }
}
