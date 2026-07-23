package com.nfcvault;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcBarcode;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class NfcVaultActivity extends Activity {

    private static final String TAG = "NfcVault";
    private static final int REQUEST_OPEN_FILE = 7001;
    private static final int REQUEST_EXPORT_JSON = 7002;
    private static final int REQUEST_UNLOCK = 7003;
    // App-lock config lives in the encrypted store; the activity owns lock state for lifecycle gating.
    private static final String LOCK_PREF_KEY = "nfc_vault_lock_config_v1";
    // Must match CARD_KEY / LEGACY_CARD_KEY in web-src/app.jsx: the only bridge values held back while locked.
    private static final String CARDS_KEY = "nfc_saved_cards_v3";
    private static final String LEGACY_CARDS_KEY = "nfc_saved_cards";

    // Public NFC Forum/default keys only. NFC Vault does not probe issuer-specific keys.
    private static final byte[][] KEY_DICT = {
        MifareClassic.KEY_DEFAULT,                            // FF FF FF FF FF FF
        MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY,       // A0 A1 A2 A3 A4 A5
        MifareClassic.KEY_NFC_FORUM                           // D3 F7 D3 F7 D3 F7
    };

    private NfcAdapter nfcAdapter;
    private WebView webView;
    private PendingIntent pendingIntent;
    private IntentFilter[] intentFilters;
    private String[][] techLists;
    private volatile boolean ndefWriteMode = false;
    private volatile byte[] pendingNdefBytes = null;
    private boolean webViewReady = false;
    private final AtomicBoolean operationInProgress = new AtomicBoolean(false);
    private SecureVaultStore secureVaultStore;
    private ValueCallback<Uri[]> fileChooserCallback;
    private String pendingExportJson;

    private KeyguardManager keyguardManager;
    private volatile boolean appLockEnabled = false;
    private int lockTimeoutSeconds = 0;
    private volatile boolean locked = false;
    private long backgroundedAt = 0;            // elapsedRealtime at onStop; 0 = not backgrounded
    private boolean internalActivityLaunch = false; // our own picker/settings/unlock, not a real backgrounding

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        secureVaultStore = new SecureVaultStore(this);
        keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        loadLockConfig();
        locked = lockActive(); // a fresh process always starts locked when the lock is armed

        webView = findViewById(R.id.webView);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(false);
        ws.setAllowFileAccess(false); // file:///android_asset stays reachable; the filesystem does not
        ws.setAllowContentAccess(false);
        ws.setAllowFileAccessFromFileURLs(false);
        ws.setAllowUniversalAccessFromFileURLs(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setSupportMultipleWindows(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webViewReady = true;
                pushLockState(); // first onResume ran before the page was ready
                handleIntent(getIntent());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equalsIgnoreCase(uri.getScheme())) return false;
                openExternalUri(uri);
                return true;
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if ("file".equalsIgnoreCase(uri.getScheme())) return false;
                openExternalUri(uri);
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (locked) {
                    callback.onReceiveValue(null);
                    return true;
                }
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                try {
                    internalActivityLaunch = true;
                    startActivityForResult(intent, REQUEST_OPEN_FILE);
                    return true;
                } catch (Exception e) {
                    internalActivityLaunch = false;
                    fileChooserCallback = null;
                    return false;
                }
            }
        });
        webView.addJavascriptInterface(new NfcBridge(), "NfcBridge");
        webView.loadUrl("file:///android_asset/www/index.html");

        pendingIntent = PendingIntent.getActivity(this, 0,
            new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        intentFilters = new IntentFilter[]{
            new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        };

        techLists = new String[][]{
            { MifareClassic.class.getName() },
            { MifareUltralight.class.getName() },
            { Ndef.class.getName() },
            { NdefFormatable.class.getName() },
            { IsoDep.class.getName() },
            { NfcA.class.getName() },
            { NfcB.class.getName() },
            { NfcF.class.getName() },
            { NfcV.class.getName() },
            { NfcBarcode.class.getName() }
        };
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_UNLOCK) {
            if (resultCode == RESULT_OK) {
                locked = false;
                backgroundedAt = 0;
                notifyJS("onUnlockResult", "success");
            } else {
                notifyJS("onUnlockResult", "cancelled");
            }
            // onResume runs next and calls pushLockState(); it also clears internalActivityLaunch.
            return;
        }
        if (requestCode == REQUEST_OPEN_FILE) {
            if (fileChooserCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
                fileChooserCallback.onReceiveValue(result);
                fileChooserCallback = null;
            }
            return;
        }
        if (requestCode == REQUEST_EXPORT_JSON) {
            boolean success = false;
            String message = "Export cancelled";
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && pendingExportJson != null) {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                    if (output == null) throw new IOException("Unable to open destination");
                    output.write(pendingExportJson.getBytes(StandardCharsets.UTF_8));
                    success = true;
                    message = "Export saved";
                } catch (Exception e) {
                    Log.e(TAG, "Export failed", e);
                    message = "Export failed: " + safeMessage(e);
                }
            }
            pendingExportJson = null;
            try {
                JSONObject result = new JSONObject();
                result.put("success", success);
                result.put("message", message);
                notifyJS("onExportComplete", result.toString());
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("NfcBridge");
            webView.destroy();
        }
        CardEmulationService.setEmulationData(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists);
        }
        if (webViewReady) {
            notifyJS("onNfcStatus", nfcAdapter == null
                    ? "unavailable" : (nfcAdapter.isEnabled() ? "ready" : "disabled"));
        }
        evaluateLockOnResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        backgroundedAt = SystemClock.elapsedRealtime();
    }

    // ========================================================================
    //  APP LOCK — device credential gate (device Keyguard handles auth UI)
    // ========================================================================
    /** The lock only applies when the user armed it AND a device screen lock exists to authenticate against. */
    private boolean lockActive() {
        return appLockEnabled && keyguardManager != null && keyguardManager.isDeviceSecure();
    }

    private void loadLockConfig() {
        try {
            String raw = secureVaultStore.getString(LOCK_PREF_KEY);
            if (raw != null && !raw.isEmpty()) {
                JSONObject o = new JSONObject(raw);
                appLockEnabled = o.optBoolean("enabled", false);
                lockTimeoutSeconds = Math.max(0, o.optInt("timeout", 0));
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to read lock config", e);
        }
    }

    private void evaluateLockOnResume() {
        if (!lockActive()) {
            locked = false;                 // disabled or device lock removed: never strand the user
        } else if (internalActivityLaunch) {
            // returning from our own picker/settings/unlock — keep the current lock state
        } else if (backgroundedAt > 0
                && SystemClock.elapsedRealtime() - backgroundedAt >= lockTimeoutSeconds * 1000L) {
            locked = true;
        }
        internalActivityLaunch = false;
        backgroundedAt = 0;
        if (locked) cancelPendingWrite();
        pushLockState();
    }

    private void cancelPendingWrite() {
        ndefWriteMode = false;
        pendingNdefBytes = null;
    }

    private void pushLockState() {
        if (webViewReady) notifyJS("onLockState", locked ? "locked" : "unlocked");
    }

    /** Launch the system confirm-credential screen; result arrives in onActivityResult. */
    private void launchUnlock() {
        if (!lockActive()) { locked = false; pushLockState(); return; }
        // ponytail: deprecated in API 29 but the only zero-dependency call spanning API 23–34.
        //           It confirms the device PIN, pattern, or password through the system UI.
        //           Upgrade path: androidx.biometric BiometricPrompt (needs FragmentActivity).
        Intent intent = keyguardManager.createConfirmDeviceCredentialIntent(
                getString(R.string.lock_prompt_title), getString(R.string.lock_prompt_desc));
        if (intent == null) { locked = false; pushLockState(); return; }
        try {
            internalActivityLaunch = true;
            startActivityForResult(intent, REQUEST_UNLOCK);
        } catch (Exception e) {
            internalActivityLaunch = false;
            notifyJS("onUnlockResult", "error");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // NFC may relaunch a stopped activity before onResume has applied the timeout.
        evaluateLockOnResume();
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !webViewReady) return;
        String action = intent.getAction();
        if (!NfcAdapter.ACTION_TECH_DISCOVERED.equals(action) &&
            !NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) &&
            !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) return;

        // Consume NFC launch intents while locked so they cannot run after a later unlock.
        if (locked) {
            intent.setAction(null);
            cancelPendingWrite();
            return;
        }

        Tag tag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag.class);
        } else {
            tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        }
        if (tag == null) return;
        if (!operationInProgress.compareAndSet(false, true)) return;

        intent.setAction(null);

        final Runnable job;
        if (ndefWriteMode && pendingNdefBytes != null) {
            job = () -> writeNdef(tag);
        } else {
            job = () -> readCard(tag);
        }
        Thread t = new Thread(() -> {
            try {
                job.run();
            } finally {
                operationInProgress.set(false);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ========================================================================
    //  SUPPORTED CARD READER — identifies technologies exposed by Android NFC
    // ========================================================================
    private void readCard(Tag tag) {
        try {
            JSONObject card = new JSONObject();

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
            NfcBarcode nfcBarcode = NfcBarcode.get(tag);
            NdefFormatable ndefFormatable = NdefFormatable.get(tag);

            if (mfc != null) {
                readMifareClassic(mfc, tag, card);
            } else if (mfu != null) {
                readMifareUltralight(mfu, tag, card);
            } else if (isoDep != null) {
                // IsoDep can be DESFire, EMV, or generic — detect sub-type
                readIsoDepSmart(isoDep, tag, card);
            } else if (ndef != null) {
                readNdef(ndef, tag, card);
            } else if (nfcBarcode != null) {
                readNfcBarcode(nfcBarcode, tag, card);
            } else if (nfcV != null) {
                readNfcV(nfcV, tag, card);
            } else if (nfcF != null) {
                readNfcF(nfcF, tag, card);
            } else if (nfcA != null) {
                readNfcAGeneric(nfcA, tag, card);
            } else if (nfcB != null) {
                readNfcBGeneric(nfcB, tag, card);
            } else if (ndefFormatable != null) {
                readNdefFormatable(ndefFormatable, tag, card);
            } else {
                card.put("tagType", "Unknown NFC Tag");
                card.put("cardType", "generic");
            }

            // For any tag that also has NDEF, append NDEF records if not already present
            if (!card.has("ndefRecords") && ndef != null) {
                try {
                    ndef.connect();
                    NdefMessage msg = ndef.getNdefMessage();
                    if (msg != null) {
                        card.put("ndefRecords", parseNdefMessage(msg));
                        card.put("ndefMessageHex", bytesToHex(msg.toByteArray(), ""));
                    }
                    ndef.close();
                } catch (Exception ignored) {}
            }

            notifyJS("onCardRead", card.toString());

        } catch (Exception e) {
            Log.e(TAG, "Read error", e);
            notifyJS("onReadError", e.getMessage() != null ? e.getMessage() : "Unknown read error");
        }
    }

    // ========================================================================
    //  MIFARE CLASSIC
    // ========================================================================
    private void readMifareClassic(MifareClassic mfc, Tag tag, JSONObject card) throws Exception {
        try {
            mfc.connect();
            int type = mfc.getType();
            String typeName;
            switch (type) {
                case MifareClassic.TYPE_CLASSIC: typeName = "MIFARE Classic"; break;
                case MifareClassic.TYPE_PLUS: typeName = "MIFARE Plus"; break;
                case MifareClassic.TYPE_PRO: typeName = "MIFARE Pro"; break;
                default: typeName = "MIFARE Classic"; break;
            }
            int sizeKb = mfc.getSize() / 1024;
            card.put("tagType", typeName + " " + sizeKb + "K");
            card.put("cardType", type == MifareClassic.TYPE_PLUS ? "mifare_plus" : "mifare_classic");
            card.put("manufacturer", "NXP - " + typeName + " " + sizeKb + "K");

            NfcA nfcA = NfcA.get(tag);
            if (nfcA != null) {
                card.put("atqa", "0x" + bytesToHex(nfcA.getAtqa(), ""));
                card.put("sak", "0x" + String.format("%02X", nfcA.getSak()));
            }

            JSONObject mem = new JSONObject();
            mem.put("total", mfc.getSize());
            mem.put("sectors", mfc.getSectorCount());
            mem.put("blocks", mfc.getBlockCount());
            mem.put("bytesPerBlock", 16);
            card.put("memory", mem);

            JSONArray sectorsArr = new JSONArray();
            for (int s = 0; s < mfc.getSectorCount(); s++) {
                JSONObject sector = new JSONObject();
                sector.put("sector", s);
                boolean authOk = false;
                for (byte[] key : KEY_DICT) {
                    try {
                        if (mfc.authenticateSectorWithKeyA(s, key)) {
                            authOk = true; break;
                        }
                    } catch (IOException ignored) {}
                    try {
                        if (mfc.authenticateSectorWithKeyB(s, key)) {
                            authOk = true; break;
                        }
                    } catch (IOException ignored) {}
                }
                sector.put("accessible", authOk);

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

    // ========================================================================
    //  MIFARE ULTRALIGHT / NTAG — with GET_VERSION for exact chip detection
    // ========================================================================
    private void readMifareUltralight(MifareUltralight mfu, Tag tag, JSONObject card) throws Exception {
        try {
            mfu.connect();

            int type = mfu.getType();
            String subType;
            switch (type) {
                case MifareUltralight.TYPE_ULTRALIGHT: subType = "MIFARE Ultralight"; break;
                case MifareUltralight.TYPE_ULTRALIGHT_C: subType = "MIFARE Ultralight C"; break;
                default: subType = "NTAG / Ultralight"; break;
            }
            card.put("tagType", subType);
            card.put("cardType", "mifare_ultralight");

            NfcA nfcA = NfcA.get(tag);
            if (nfcA != null) {
                card.put("atqa", "0x" + bytesToHex(nfcA.getAtqa(), ""));
                card.put("sak", "0x" + String.format("%02X", nfcA.getSak()));
            }

            // Try GET_VERSION command (0x60) for exact chip identification
            try {
                byte[] verResp = mfu.transceive(new byte[]{(byte) 0x60});
                    if (verResp != null && verResp.length >= 8) {
                        card.put("chipVendor", verResp[1] == 0x04 ? "NXP" : "0x" + String.format("%02X", verResp[1]));
                        card.put("chipType", "0x" + String.format("%02X", verResp[2]));
                        card.put("chipSubtype", "0x" + String.format("%02X", verResp[3]));
                        int prodMajor = verResp[4] & 0xFF;
                        int prodMinor = verResp[5] & 0xFF;
                        int storageSize = verResp[6] & 0xFF;
                        int proto = verResp[7] & 0xFF;
                        card.put("productVersion", prodMajor + "." + prodMinor);
                        card.put("storageCode", "0x" + String.format("%02X", storageSize));
                        card.put("protocolType", "0x" + String.format("%02X", proto));

                        // Decode exact NTAG/Ultralight variant from storage size byte
                        String exactType = decodeNtagType(verResp[2] & 0xFF, storageSize);
                        if (exactType != null) {
                            card.put("tagType", exactType);
                        }
                    }
            } catch (Exception ignored) {
                // GET_VERSION not supported — fall back to CC detection
            }

            // Read and validate the NFC Forum capability container (page 3). Its size byte
            // provides a safe bound when GET_VERSION cannot identify the exact variant.
            int capabilityDataSize = 0;
            try {
                byte[] header = mfu.readPages(0);
                if (header != null && header.length >= 16 && (header[12] & 0xFF) == 0xE1) {
                    capabilityDataSize = (header[14] & 0xFF) * 8;
                    String currentType = card.optString("tagType", "");
                    if (currentType.equals("NTAG / Ultralight")
                            || currentType.equals("NTAG (unknown variant)")) {
                        if (capabilityDataSize == 144) card.put("tagType", "NTAG 213");
                        else if (capabilityDataSize == 504) card.put("tagType", "NTAG 215");
                        else if (capabilityDataSize == 888) card.put("tagType", "NTAG 216");
                    }
                }
            } catch (Exception ignored) {}

            // Read pages
            JSONArray pagesArr = new JSONArray();
            int maxPages = NfcDataUtils.ultralightPageCount(
                    card.optString("tagType", null), capabilityDataSize);
            for (int p = 0; p < maxPages; p += 4) {
                try {
                    byte[] data = mfu.readPages(p);
                    for (int i = 0; i < 4 && (p + i) < maxPages; i++) {
                        JSONObject pageObj = new JSONObject();
                        pageObj.put("page", p + i);
                        byte[] pageData = new byte[4];
                        System.arraycopy(data, i * 4, pageData, 0, 4);
                        pageObj.put("data", bytesToHex(pageData, " "));
                        pagesArr.put(pageObj);
                    }
                } catch (IOException e) {
                    break;
                }
            }
            card.put("pages", pagesArr);
            card.put("totalPages", pagesArr.length());

            // Also try to read NDEF from this tag
            Ndef ndefTech = Ndef.get(tag);
            if (ndefTech != null) {
                try {
                    mfu.close();
                    ndefTech.connect();
                    NdefMessage msg = ndefTech.getNdefMessage();
                    if (msg != null) {
                        card.put("ndefRecords", parseNdefMessage(msg));
                        card.put("ndefMessageHex", bytesToHex(msg.toByteArray(), ""));
                    }
                    ndefTech.close();
                } catch (Exception ignored) {}
            }
        } finally {
            try { mfu.close(); } catch (IOException ignored) {}
        }
    }

    /** Decode the exact NTAG/Ultralight chip variant from GET_VERSION response bytes */
    private String decodeNtagType(int productType, int storageSize) {
        // productType: 0x03 = Ultralight, 0x04 = NTAG
        if (productType == 0x03) {
            switch (storageSize) {
                case 0x06: return "MIFARE Ultralight EV1 (48 bytes)";
                case 0x0A: return "MIFARE Ultralight EV1 (128 bytes)";
                case 0x0B: return "MIFARE Ultralight EV1 (128 bytes)";
                default:   return "MIFARE Ultralight";
            }
        } else if (productType == 0x04) {
            switch (storageSize) {
                case 0x06: return "NTAG 210";
                case 0x0A: return "NTAG 212";
                case 0x0F: return "NTAG 213";
                case 0x11: return "NTAG 215";
                case 0x13: return "NTAG 216";
                default:   return "NTAG (unknown variant)";
            }
        } else if (productType == 0x05) {
            // NTAG I2C
            switch (storageSize) {
                case 0x13: return "NTAG I2C 1K";
                case 0x15: return "NTAG I2C 2K";
                default:   return "NTAG I2C";
            }
        } else if (productType == 0x07) {
            return "NTAG I2C Plus";
        }
        return null;
    }

    // ========================================================================
    //  NDEF
    // ========================================================================
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
                byte[] raw = msg.toByteArray();
                card.put("ndefRecords", parseNdefMessage(msg));
                card.put("messageSize", raw.length);
                card.put("ndefMessageHex", bytesToHex(raw, ""));
            } else {
                card.put("ndefRecords", new JSONArray());
                card.put("messageSize", 0);
            }
        } finally {
            try { ndef.close(); } catch (IOException ignored) {}
        }
    }

    // ========================================================================
    //  NDEF FORMATABLE — tags that can be formatted but have no NDEF yet
    // ========================================================================
    private void readNdefFormatable(NdefFormatable nf, Tag tag, JSONObject card) throws Exception {
        card.put("tagType", "NDEF Formatable");
        card.put("cardType", "ndef_formatable");
        card.put("isWritable", true);
        card.put("isFormatted", false);

        // Add NfcA info if available
        NfcA nfcA = NfcA.get(tag);
        if (nfcA != null) {
            try {
                nfcA.connect();
                card.put("atqa", "0x" + bytesToHex(nfcA.getAtqa(), ""));
                card.put("sak", "0x" + String.format("%02X", nfcA.getSak()));
                nfcA.close();
            } catch (Exception ignored) {}
        }
    }

    // ========================================================================
    //  NFC BARCODE (Kovio)
    // ========================================================================
    private void readNfcBarcode(NfcBarcode nfcBarcode, Tag tag, JSONObject card) throws Exception {
        try {
            nfcBarcode.connect();
            card.put("tagType", "NFC Barcode (Kovio)");
            card.put("cardType", "nfc_barcode");

            int barcodeType = nfcBarcode.getType();
            card.put("barcodeType", barcodeType == NfcBarcode.TYPE_KOVIO ? "Kovio" :
                                    barcodeType == NfcBarcode.TYPE_UNKNOWN ? "Unknown" :
                                    "Type " + barcodeType);

            byte[] barcodeData = nfcBarcode.getBarcode();
            if (barcodeData != null) {
                card.put("barcodeHex", bytesToHex(barcodeData, " "));
                card.put("barcodeLength", barcodeData.length);
                // Try to decode as ASCII
                try {
                    String ascii = new String(barcodeData, Charset.forName("US-ASCII"));
                    if (isPrintable(ascii)) {
                        card.put("barcodeAscii", ascii);
                    }
                } catch (Exception ignored) {}
            }
        } finally {
            try { nfcBarcode.close(); } catch (IOException ignored) {}
        }
    }

    // ========================================================================
    //  ISO-DEP SMART DETECTION — DESFire / EMV / Java Card / Generic
    // ========================================================================
    private void readIsoDepSmart(IsoDep isoDep, Tag tag, JSONObject card) throws Exception {
        try {
            isoDep.connect();
            isoDep.setTimeout(5000); // Generous timeout for slow cards

            byte[] hist = isoDep.getHistoricalBytes();
            if (hist != null) card.put("historicalBytes", bytesToHex(hist, " "));

            byte[] hiLayerResp = isoDep.getHiLayerResponse();
            if (hiLayerResp != null) card.put("hiLayerResponse", bytesToHex(hiLayerResp, " "));

            card.put("maxTransceiveLength", isoDep.getMaxTransceiveLength());
            card.put("timeout", isoDep.getTimeout());

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

            JSONArray apdus = new JSONArray();

            // ── TRY 1: DESFire GET_VERSION (90 60 00 00 00) ──
            boolean isDESFire = false;
            try {
                byte[] getVer1 = isoDep.transceive(new byte[]{(byte) 0x90, 0x60, 0x00, 0x00, 0x00});
                if (getVer1 != null && getVer1.length >= 9) {
                    int sw = ((getVer1[getVer1.length - 2] & 0xFF) << 8) | (getVer1[getVer1.length - 1] & 0xFF);
                    if (sw == 0x91AF || sw == 0x9100) {
                        isDESFire = true;
                        JSONObject verObj = new JSONObject();
                        byte[] verData = Arrays.copyOf(getVer1, getVer1.length - 2);

                        // Get remaining version parts (91 AF means more data)
                        if (sw == 0x91AF) {
                            byte[] getVer2 = isoDep.transceive(new byte[]{(byte) 0x90, (byte) 0xAF, 0x00, 0x00, 0x00});
                            if (getVer2 != null && getVer2.length >= 2) {
                                byte[] part2 = Arrays.copyOf(getVer2, getVer2.length - 2);
                                verObj.put("softwareVersion", bytesToHex(part2, " "));

                                int sw2 = ((getVer2[getVer2.length - 2] & 0xFF) << 8) | (getVer2[getVer2.length - 1] & 0xFF);
                                if (sw2 == 0x91AF) {
                                    byte[] getVer3 = isoDep.transceive(new byte[]{(byte) 0x90, (byte) 0xAF, 0x00, 0x00, 0x00});
                                    if (getVer3 != null && getVer3.length >= 2) {
                                        byte[] part3 = Arrays.copyOf(getVer3, getVer3.length - 2);
                                        verObj.put("productionInfo", bytesToHex(part3, " "));
                                    }
                                }
                            }
                        }

                        // Parse hardware version info
                        if (verData.length >= 7) {
                            int hwVendor = verData[0] & 0xFF;
                            int hwType = verData[1] & 0xFF;
                            int hwSubtype = verData[2] & 0xFF;
                            int hwMajor = verData[3] & 0xFF;
                            int hwMinor = verData[4] & 0xFF;
                            int hwStorage = verData[5] & 0xFF;
                            int hwProto = verData[6] & 0xFF;

                            verObj.put("hardwareVendor", hwVendor == 0x04 ? "NXP" : "0x" + String.format("%02X", hwVendor));
                            verObj.put("hardwareType", "0x" + String.format("%02X", hwType));
                            verObj.put("hardwareSubtype", "0x" + String.format("%02X", hwSubtype));
                            verObj.put("hardwareVersion", hwMajor + "." + hwMinor);
                            verObj.put("storageSize", desfireStorageSize(hwStorage));
                            verObj.put("protocol", hwProto == 0x05 ? "ISO 14443-3/4" : "0x" + String.format("%02X", hwProto));

                            // Identify DESFire variant
                            String desfireType = desfireVariant(hwType, hwSubtype, hwMajor, hwMinor);
                            card.put("tagType", desfireType);
                        }

                        card.put("desfireVersion", verObj);
                        card.put("cardType", "desfire");
                        card.put("manufacturer", "NXP Semiconductors");

                        // Try to list DESFire applications
                        try {
                            byte[] getApps = isoDep.transceive(new byte[]{(byte) 0x90, (byte) 0x6A, 0x00, 0x00, 0x00});
                            if (getApps != null && getApps.length >= 2) {
                                int swApps = ((getApps[getApps.length - 2] & 0xFF) << 8) | (getApps[getApps.length - 1] & 0xFF);
                                if (swApps == 0x9100 || swApps == 0x91AF) {
                                    byte[] appIds = Arrays.copyOf(getApps, getApps.length - 2);
                                    JSONArray apps = new JSONArray();
                                    for (int i = 0; i + 2 < appIds.length; i += 3) {
                                        String aid = String.format("%02X%02X%02X", appIds[i] & 0xFF, appIds[i + 1] & 0xFF, appIds[i + 2] & 0xFF);
                                        apps.put(aid);
                                    }
                                    card.put("desfireApplications", apps);
                                }
                            }
                        } catch (Exception ignored) {}

                        logApdu(apdus, "DESFire GET_VERSION", new byte[]{(byte) 0x90, 0x60, 0x00, 0x00, 0x00}, getVer1);
                    }
                }
            } catch (Exception ignored) {}

            // ── TRY 2: EMV Payment Card — SELECT PPSE (2PAY.SYS.DDF01) ──
            boolean isEMV = false;
            if (!isDESFire) {
                try {
                    byte[] ppseBytes = "2PAY.SYS.DDF01".getBytes(Charset.forName("UTF-8"));
                    byte[] selectPpse = new byte[6 + ppseBytes.length];
                    selectPpse[0] = 0x00; selectPpse[1] = (byte) 0xA4;
                    selectPpse[2] = 0x04; selectPpse[3] = 0x00;
                    selectPpse[4] = (byte) ppseBytes.length;
                    System.arraycopy(ppseBytes, 0, selectPpse, 5, ppseBytes.length);
                    selectPpse[selectPpse.length - 1] = 0x00;

                    byte[] ppseResp = isoDep.transceive(selectPpse);
                    if (ppseResp != null && ppseResp.length >= 2) {
                        int swPpse = ((ppseResp[ppseResp.length - 2] & 0xFF) << 8) | (ppseResp[ppseResp.length - 1] & 0xFF);
                        logApdu(apdus, "SELECT PPSE", selectPpse, ppseResp);

                        if (swPpse == 0x9000) {
                            isEMV = true;
                            card.put("cardType", "emv");
                            card.put("tagType", "EMV Contactless Card");
                            card.put("manufacturer", "Payment Card");

                            // Parse PPSE response for AIDs
                            byte[] ppseData = Arrays.copyOf(ppseResp, ppseResp.length - 2);
                            JSONArray aids = parseEmvAids(ppseData);
                            card.put("emvApplications", aids);

                            // Try to SELECT each AID to get more info
                            for (int i = 0; i < aids.length(); i++) {
                                JSONObject aidObj = aids.getJSONObject(i);
                                String aidHex = aidObj.getString("aid");
                                byte[] aidBytes = hexToBytes(aidHex);

                                byte[] selectAid = new byte[6 + aidBytes.length];
                                selectAid[0] = 0x00; selectAid[1] = (byte) 0xA4;
                                selectAid[2] = 0x04; selectAid[3] = 0x00;
                                selectAid[4] = (byte) aidBytes.length;
                                System.arraycopy(aidBytes, 0, selectAid, 5, aidBytes.length);
                                selectAid[selectAid.length - 1] = 0x00;

                                try {
                                    byte[] aidResp = isoDep.transceive(selectAid);
                                    logApdu(apdus, "SELECT AID " + aidHex, selectAid, aidResp);

                                    if (aidResp != null && aidResp.length >= 2) {
                                        int swAid = ((aidResp[aidResp.length - 2] & 0xFF) << 8) | (aidResp[aidResp.length - 1] & 0xFF);
                                        if (swAid == 0x9000) {
                                            // Identify card network from AID
                                            String network = identifyPaymentNetwork(aidHex);
                                            if (network != null) {
                                                card.put("tagType", network + " Contactless");
                                                card.put("paymentNetwork", network);
                                            }

                                            // Parse FCI for application label
                                            byte[] fciData = Arrays.copyOf(aidResp, aidResp.length - 2);
                                            String appLabel = parseFciLabel(fciData);
                                            if (appLabel != null) {
                                                aidObj.put("applicationLabel", appLabel);
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // ── TRY 3: Generic ISO-DEP SELECT ──
            if (!isDESFire && !isEMV) {
                card.put("tagType", "ISO-DEP / Smart Card");
                card.put("cardType", "isodep");

                // Identify sub-type from SAK
                if (card.has("sak")) {
                    String sakStr = card.getString("sak");
                    int sak = Integer.parseInt(sakStr.replace("0x", ""), 16);
                    String subType = identifyFromSak(sak);
                    if (subType != null) card.put("tagSubType", subType);
                }

                try {
                    byte[] selectCmd = new byte[]{0x00, (byte) 0xA4, 0x04, 0x00, 0x00};
                    byte[] response = isoDep.transceive(selectCmd);
                    logApdu(apdus, "SELECT (no AID)", selectCmd, response);
                } catch (Exception ignored) {}
            }

            card.put("apdus", apdus);

        } finally {
            try { isoDep.close(); } catch (IOException ignored) {}
        }
    }

    /** Parse EMV PPSE response to extract AIDs */
    private JSONArray parseEmvAids(byte[] data) {
        JSONArray aids = new JSONArray();
        try {
            // Simple TLV parser to find tag 4F (AID) and tag 50 (Application Label)
            int i = 0;
            while (i < data.length - 2) {
                if (data[i] == 0x4F) {
                    // AID tag
                    int len = data[i + 1] & 0xFF;
                    if (i + 2 + len <= data.length) {
                        byte[] aidBytes = Arrays.copyOfRange(data, i + 2, i + 2 + len);
                        JSONObject aidObj = new JSONObject();
                        aidObj.put("aid", bytesToHex(aidBytes, ""));
                        String network = identifyPaymentNetwork(bytesToHex(aidBytes, ""));
                        if (network != null) aidObj.put("network", network);
                        aids.put(aidObj);
                        i += 2 + len;
                        continue;
                    }
                }
                i++;
            }
        } catch (Exception ignored) {}

        return aids;
    }

    /** Identify payment network from AID prefix */
    private String identifyPaymentNetwork(String aid) {
        String upper = aid.toUpperCase(Locale.ROOT);
        if (upper.startsWith("A000000003")) return "Visa";
        if (upper.startsWith("A000000004")) return "Mastercard";
        if (upper.startsWith("A000000025")) return "American Express";
        if (upper.startsWith("A000000065")) return "JCB";
        if (upper.startsWith("A000000152")) return "Discover";
        if (upper.startsWith("A000000324")) return "UnionPay";
        if (upper.startsWith("A000000029")) return "Dankort";
        if (upper.startsWith("D5280050")) return "girocard";
        if (upper.startsWith("A0000006200620")) return "DNA";
        return null;
    }

    /** Parse FCI template to find Application Label (tag 50) */
    private String parseFciLabel(byte[] data) {
        try {
            for (int i = 0; i < data.length - 2; i++) {
                if (data[i] == 0x50) {
                    int len = data[i + 1] & 0xFF;
                    if (i + 2 + len <= data.length) {
                        return new String(Arrays.copyOfRange(data, i + 2, i + 2 + len), Charset.forName("US-ASCII"));
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** DESFire storage size from code */
    private String desfireStorageSize(int code) {
        switch (code) {
            case 0x16: return "2 KB";
            case 0x18: return "4 KB";
            case 0x1A: return "8 KB";
            case 0x1C: return "16 KB";
            case 0x1E: return "32 KB";
            default:   return "0x" + String.format("%02X", code);
        }
    }

    /** DESFire variant name from version bytes */
    private String desfireVariant(int hwType, int hwSubtype, int major, int minor) {
        if (hwType == 0x01) {
            if (major == 0 && minor <= 6) return "MIFARE DESFire EV1";
            if (major == 1) return "MIFARE DESFire EV2";
            if (major == 2) return "MIFARE DESFire EV3";
            return "MIFARE DESFire";
        }
        return "MIFARE DESFire";
    }

    /** Identify card sub-type from SAK byte */
    private String identifyFromSak(int sak) {
        switch (sak) {
            case 0x08: return "MIFARE Classic 1K";
            case 0x09: return "MIFARE Mini";
            case 0x10: return "MIFARE Plus 2K (SL2)";
            case 0x11: return "MIFARE Plus 4K (SL2)";
            case 0x18: return "MIFARE Classic 4K";
            case 0x19: return "MIFARE Classic 2K";
            case 0x20: return "MIFARE Plus / DESFire / ISO 14443-4";
            case 0x28: return "IBM JCOP / Smart MX";
            case 0x38: return "Smart MX with MIFARE Classic 4K";
            case 0x88: return "Infineon SLE 66R35";
            case 0x98: return "Gemplus MPCOS";
            default: return null;
        }
    }

    // ========================================================================
    //  NFC-V / ISO 15693 — with SYSTEM INFO for chip identification
    // ========================================================================
    private void readNfcV(NfcV nfcV, Tag tag, JSONObject card) throws Exception {
        try {
            nfcV.connect();
            card.put("tagType", "NFC-V / ISO 15693");
            card.put("cardType", "nfcv");
            card.put("dsfId", "0x" + String.format("%02X", nfcV.getDsfId()));
            card.put("responseFlags", "0x" + String.format("%02X", nfcV.getResponseFlags()));
            card.put("maxTransceiveLength", nfcV.getMaxTransceiveLength());

            // Try GET_SYSTEM_INFORMATION command for chip identification
            try {
                byte[] sysInfoCmd = new byte[]{0x20, 0x2B}; // Flags=0x20, GET_SYSTEM_INFO=0x2B
                byte[] sysResp = nfcV.transceive(sysInfoCmd);
                if (sysResp != null && sysResp.length > 2 && sysResp[0] == 0x00) {
                    // Parse system info response
                    int infoFlags = sysResp[1] & 0xFF;
                    int offset = 2;

                    // UID (8 bytes, reversed)
                    if (offset + 8 <= sysResp.length) {
                        byte[] uid = new byte[8];
                        for (int i = 0; i < 8; i++) uid[i] = sysResp[offset + 7 - i];
                        card.put("nfcvUid", bytesToHex(uid, " "));
                        offset += 8;
                    }

                    // DSFID
                    if ((infoFlags & 0x01) != 0 && offset < sysResp.length) {
                        card.put("dsfId", "0x" + String.format("%02X", sysResp[offset] & 0xFF));
                        offset++;
                    }

                    // AFI
                    if ((infoFlags & 0x02) != 0 && offset < sysResp.length) {
                        card.put("afi", "0x" + String.format("%02X", sysResp[offset] & 0xFF));
                        offset++;
                    }

                    // Memory size
                    if ((infoFlags & 0x04) != 0 && offset + 1 < sysResp.length) {
                        int numBlocks = (sysResp[offset] & 0xFF) + 1;
                        int blockSize = (sysResp[offset + 1] & 0x1F) + 1;
                        card.put("totalBlocks", numBlocks);
                        card.put("blockSize", blockSize);
                        card.put("totalMemory", numBlocks * blockSize + " bytes");
                        offset += 2;
                    }

                    // IC reference — identifies the chip
                    if ((infoFlags & 0x08) != 0 && offset < sysResp.length) {
                        int icRef = sysResp[offset] & 0xFF;
                        card.put("icReference", "0x" + String.format("%02X", icRef));

                        // Try to identify NFC-V chip variant
                        int mfr = (tag.getId().length > 0) ? (tag.getId()[0] & 0xFF) : 0;
                        String chipName = identifyNfcVChip(mfr, icRef);
                        if (chipName != null) {
                            card.put("tagType", chipName);
                        }
                    }
                }
            } catch (Exception ignored) {}

            // Read blocks
            JSONArray blocks = new JSONArray();
            int maxBlocks = card.has("totalBlocks") ? card.getInt("totalBlocks") : 64;
            for (int i = 0; i < maxBlocks; i++) {
                try {
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
            if (!card.has("totalBlocks")) card.put("totalBlocks", blocks.length());
        } finally {
            try { nfcV.close(); } catch (IOException ignored) {}
        }
    }

    /** Identify NFC-V chip from manufacturer code and IC reference */
    private String identifyNfcVChip(int mfr, int icRef) {
        if (mfr == 0x02) { // STMicroelectronics
            switch (icRef) {
                case 0x05: return "ST25TV512";
                case 0x24: return "SRIX4K";
                case 0x2C: return "ST25TV02K";
                case 0x28: return "M24LR16E-R";
                case 0x2D: return "ST25DV04K";
                case 0x2E: return "ST25DV16K";
                case 0x2F: return "ST25DV64K";
                case 0x44: return "LRI2K";
                case 0x4C: return "M24LR04E-R";
                default:   return "ST NFC-V (IC 0x" + String.format("%02X", icRef) + ")";
            }
        } else if (mfr == 0x04) { // NXP
            switch (icRef) {
                case 0x01: return "ICODE SLI (SL2 ICS20)";
                case 0x02: return "ICODE SLI-S (SL2 ICS53)";
                case 0x03: return "ICODE SLI-L (SL2 ICS50)";
                case 0x41: return "ICODE SLIX";
                case 0x42: return "ICODE SLIX-S";
                case 0x43: return "ICODE SLIX-L";
                case 0x45: return "ICODE SLIX2";
                case 0x46: return "ICODE DNA";
                default:   return "NXP ICODE (IC 0x" + String.format("%02X", icRef) + ")";
            }
        } else if (mfr == 0x07) { // Texas Instruments
            if (icRef == 0x00) return "Tag-it HF-I Standard";
            if (icRef == 0x80) return "Tag-it HF-I Pro";
            if (icRef == 0xC0) return "Tag-it HF-I Plus";
            return "TI Tag-it (IC 0x" + String.format("%02X", icRef) + ")";
        }
        return null;
    }

    // ========================================================================
    //  NFC-F / FeliCa
    // ========================================================================
    private void readNfcF(NfcF nfcF, Tag tag, JSONObject card) throws Exception {
        try {
            nfcF.connect();
            card.put("tagType", "NFC-F / FeliCa");
            card.put("cardType", "nfcf");

            byte[] idm = tag.getId();
            card.put("idm", bytesToHex(idm, " "));

            byte[] pmm = nfcF.getManufacturer();
            if (pmm != null) card.put("pmm", bytesToHex(pmm, " "));

            byte[] systemCode = nfcF.getSystemCode();
            if (systemCode != null) {
                card.put("systemCode", bytesToHex(systemCode, " "));
                // Identify FeliCa system
                String scHex = bytesToHex(systemCode, "");
                String felicaType = identifyFelicaSystem(scHex);
                if (felicaType != null) card.put("felicaSystem", felicaType);
            }

            card.put("maxTransceiveLength", nfcF.getMaxTransceiveLength());
        } finally {
            try { nfcF.close(); } catch (IOException ignored) {}
        }
    }

    /** Identify FeliCa system from system code */
    private String identifyFelicaSystem(String scHex) {
        String upper = scHex.toUpperCase(Locale.ROOT);
        if (upper.equals("88B4")) return "NDEF on FeliCa";
        if (upper.equals("8008")) return "FeliCa Lite / Lite-S";
        if (upper.equals("8B5C")) return "FeliCa Standard (NFC-F)";
        if (upper.equals("0003")) return "Suica / PASMO (transit)";
        if (upper.equals("0088")) return "FeliCa Common Area";
        if (upper.equals("FE00")) return "Common Area / Plug";
        if (upper.equals("12FC")) return "WAON (e-money)";
        if (upper.equals("00FE")) return "FeliCa Plug";
        return null;
    }

    // ========================================================================
    //  NFC-A Generic
    // ========================================================================
    private void readNfcAGeneric(NfcA nfcA, Tag tag, JSONObject card) throws Exception {
        try {
            nfcA.connect();
            card.put("tagType", "NFC-A / ISO 14443-3A");
            card.put("cardType", "nfca");
            card.put("atqa", "0x" + bytesToHex(nfcA.getAtqa(), ""));
            int sak = nfcA.getSak();
            card.put("sak", "0x" + String.format("%02X", sak));
            card.put("maxTransceiveLength", nfcA.getMaxTransceiveLength());

            // Identify from SAK
            String subType = identifyFromSak(sak);
            if (subType != null) card.put("tagSubType", subType);
        } finally {
            try { nfcA.close(); } catch (IOException ignored) {}
        }
    }

    // ========================================================================
    //  NFC-B Generic
    // ========================================================================
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
                    String extType = new String(recType, Charset.forName("US-ASCII"));
                    r.put("externalType", extType);
                    if ("android.com:pkg".equalsIgnoreCase(extType)) {
                        r.put("recordType", "ANDROID_APP");
                        r.put("decoded", new String(payload, Charset.forName("US-ASCII")));
                    } else {
                        r.put("recordType", "EXTERNAL");
                        String ascii = new String(payload, Charset.forName("US-ASCII"));
                        r.put("decoded", isPrintable(ascii) ? ascii : bytesToHex(payload, " "));
                    }
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
        // Bit 7 of the status byte selects UTF-16 instead of UTF-8.
        Charset charset = (payload[0] & 0x80) != 0
                ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8;
        return new String(payload, 1 + langLen, payload.length - 1 - langLen, charset);
    }

    // ========================================================================
    //  NDEF WRITER — write URL / Text / Tel / Email / Geo / MIME / AAR tags
    // ========================================================================
    /** Build an NdefMessage from a JSON array of record specs. */
    private byte[] buildNdefMessage(String recordsJson) throws Exception {
        JSONArray arr = new JSONArray(recordsJson);
        if (arr.length() == 0) throw new IllegalArgumentException("No records to write");
        NdefRecord[] recs = new NdefRecord[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String type = o.optString("type", "text").toLowerCase(Locale.ROOT);
            String value = o.optString("value", "");
            switch (type) {
                case "uri":
                case "url":
                    recs[i] = NdefRecord.createUri(value);
                    break;
                case "text":
                    recs[i] = NdefRecord.createTextRecord(o.optString("lang", "en"), value);
                    break;
                case "mime":
                    recs[i] = NdefRecord.createMime(
                        o.optString("mime", "text/plain"),
                        value.getBytes(Charset.forName("UTF-8")));
                    break;
                case "aar":
                case "app":
                    recs[i] = NdefRecord.createApplicationRecord(value);
                    break;
                case "tel":
                    recs[i] = NdefRecord.createUri("tel:" + value);
                    break;
                case "mailto":
                case "email":
                    recs[i] = NdefRecord.createUri("mailto:" + value);
                    break;
                case "geo":
                    recs[i] = NdefRecord.createUri("geo:" + value);
                    break;
                case "raw":
                    recs[i] = new NdefRecord(NdefRecord.TNF_UNKNOWN, null, null,
                        hexToBytes(value.replaceAll("[^0-9A-Fa-f]", "")));
                    break;
                default:
                    recs[i] = NdefRecord.createTextRecord("en", value);
                    break;
            }
        }
        return new NdefMessage(recs).toByteArray();
    }

    /** Write the pending NDEF message to the tapped tag (Ndef or NdefFormatable). */
    private void writeNdef(Tag tag) {
        try {
            byte[] bytes = pendingNdefBytes;
            if (bytes == null) { notifyJS("onWriteError", "No NDEF data queued"); return; }
            NdefMessage msg = new NdefMessage(bytes);

            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                try {
                    ndef.connect();
                    if (!ndef.isWritable()) {
                        notifyJS("onWriteError", "Tag is read-only / locked");
                        return;
                    }
                    if (ndef.getMaxSize() < bytes.length) {
                        notifyJS("onWriteError", "Message (" + bytes.length + "B) exceeds tag capacity (" + ndef.getMaxSize() + "B)");
                        return;
                    }
                    ndef.writeNdefMessage(msg);
                    NdefMessage verifiedMessage = ndef.getNdefMessage();
                    if (verifiedMessage == null
                            || !Arrays.equals(bytes, verifiedMessage.toByteArray())) {
                        notifyJS("onWriteError", "The tag could not be verified after writing");
                        return;
                    }
                    JSONObject result = new JSONObject();
                    result.put("mode", "ndef");
                    result.put("written", msg.getRecords().length);
                    result.put("bytes", bytes.length);
                    result.put("tag", "NDEF");
                    result.put("verified", true);
                    notifyJS("onWriteComplete", result.toString());
                    return;
                } finally {
                    try { ndef.close(); } catch (IOException ignored) {}
                }
            }

            NdefFormatable formatable = NdefFormatable.get(tag);
            if (formatable != null) {
                try {
                    formatable.connect();
                    formatable.format(msg);
                    JSONObject result = new JSONObject();
                    result.put("mode", "ndef");
                    result.put("written", msg.getRecords().length);
                    result.put("bytes", bytes.length);
                    result.put("tag", "NDEF (formatted)");
                    result.put("verified", false);
                    notifyJS("onWriteComplete", result.toString());
                    return;
                } finally {
                    try { formatable.close(); } catch (IOException ignored) {}
                }
            }

            notifyJS("onWriteError", "This tag does not support NDEF writing");
        } catch (FormatException fe) {
            notifyJS("onWriteError", "Format error: " + fe.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "NDEF write error", e);
            notifyJS("onWriteError", e.getMessage() != null ? e.getMessage() : "NDEF write failed");
        } finally {
            ndefWriteMode = false;
            pendingNdefBytes = null;
        }
    }

    // ========================================================================
    //  JAVASCRIPT BRIDGE
    // ========================================================================
    private class NfcBridge {
        @JavascriptInterface
        public void startReadMode() {
            ndefWriteMode = false;
            pendingNdefBytes = null;
        }

        /** Queue an NDEF message (built from a JSON record spec) to write to the next tag. */
        @JavascriptInterface
        public String startNdefWrite(String recordsJson) {
            if (locked) return "ERR:Vault is locked";
            try {
                pendingNdefBytes = buildNdefMessage(recordsJson);
                ndefWriteMode = true;
                return "" + pendingNdefBytes.length;
            } catch (Exception e) {
                ndefWriteMode = false;
                pendingNdefBytes = null;
                return "ERR:" + (e.getMessage() != null ? e.getMessage() : "invalid records");
            }
        }

        /** Queue a previously read standard NDEF message for writing to a compatible tag. */
        @JavascriptInterface
        public String startRawNdefWrite(String ndefHex) {
            if (locked) return "ERR:Vault is locked";
            try {
                String clean = ndefHex == null ? "" : ndefHex.replaceAll("[^0-9A-Fa-f]", "");
                if (clean.length() < 2 || clean.length() % 2 != 0) {
                    throw new IllegalArgumentException("Invalid NDEF data");
                }
                byte[] candidate = hexToBytes(clean);
                if (candidate.length > 4095) throw new IllegalArgumentException("NDEF data is too large");
                // Parsing verifies that this is a complete, standards-compliant NDEF message.
                new NdefMessage(candidate);
                pendingNdefBytes = candidate;
                ndefWriteMode = true;
                return Integer.toString(candidate.length);
            } catch (Exception e) {
                ndefWriteMode = false;
                pendingNdefBytes = null;
                return "ERR:" + safeMessage(e);
            }
        }

        @JavascriptInterface
        public void cancelWrite() {
            cancelPendingWrite();
        }

        @JavascriptInterface
        public void copyToClipboard(String text) {
            if (locked) return;
            runOnUiThread(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("NFC Vault", text));
                } catch (Exception e) {
                    Log.e(TAG, "clipboard error", e);
                }
            });
        }

        @JavascriptInterface
        public boolean isNfcAvailable() {
            return nfcAdapter != null;
        }

        @JavascriptInterface
        public boolean isNfcEnabled() {
            return nfcAdapter != null && nfcAdapter.isEnabled();
        }

        @JavascriptInterface
        public boolean isEmulationAvailable() {
            return getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_NFC_HOST_CARD_EMULATION);
        }

        @JavascriptInterface
        public void openNfcSettings() {
            runOnUiThread(() -> {
                internalActivityLaunch = true;
                try {
                    startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                }
            });
        }

        @JavascriptInterface
        public boolean isDeviceSecure() {
            return keyguardManager != null && keyguardManager.isDeviceSecure();
        }

        @JavascriptInterface
        public boolean isLocked() {
            return locked;
        }

        /** {enabled,timeout,deviceSecure,locked} so the UI can render the current lock setting. */
        @JavascriptInterface
        public String getLockConfig() {
            try {
                JSONObject o = new JSONObject();
                o.put("enabled", appLockEnabled);
                o.put("timeout", lockTimeoutSeconds);
                o.put("deviceSecure", keyguardManager != null && keyguardManager.isDeviceSecure());
                o.put("locked", locked);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void setLockConfig(boolean enabled, int timeoutSeconds) {
            if (locked) return;
            appLockEnabled = enabled && keyguardManager != null && keyguardManager.isDeviceSecure();
            lockTimeoutSeconds = Math.max(0, timeoutSeconds);
            if (!appLockEnabled) locked = false;
            try {
                JSONObject o = new JSONObject();
                o.put("enabled", appLockEnabled);
                o.put("timeout", lockTimeoutSeconds);
                secureVaultStore.putString(LOCK_PREF_KEY, o.toString());
            } catch (Exception e) {
                Log.e(TAG, "Unable to save lock config", e);
            }
        }

        @JavascriptInterface
        public void requestUnlock() {
            runOnUiThread(NfcVaultActivity.this::launchUnlock);
        }

        @JavascriptInterface
        public void openExternalUrl(String url) {
            runOnUiThread(() -> openExternalUri(Uri.parse(url)));
        }

        @JavascriptInterface
        public void startEmulation(String cardJson) {
            if (locked) return;
            CardEmulationService.setEmulationData(cardJson);
        }

        @JavascriptInterface
        public void stopEmulation() {
            CardEmulationService.setEmulationData(null);
        }

        @JavascriptInterface
        public void exportJson(String fileName, String json) {
            if (locked) {
                notifyJS("onExportComplete", "{\"success\":false,\"message\":\"Unlock the vault to export\"}");
                return;
            }
            if (json == null || json.length() > 5_000_000) {
                notifyJS("onExportComplete", "{\"success\":false,\"message\":\"Export is too large\"}");
                return;
            }
            pendingExportJson = json;
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, NfcDataUtils.sanitizeJsonFileName(fileName));
                try {
                    internalActivityLaunch = true;
                    startActivityForResult(intent, REQUEST_EXPORT_JSON);
                } catch (Exception e) {
                    internalActivityLaunch = false;
                    pendingExportJson = null;
                    notifyJS("onExportComplete", "{\"success\":false,\"message\":\"No file picker is available\"}");
                }
            });
        }

        @JavascriptInterface
        public void saveData(String key, String value) {
            if (locked && (CARDS_KEY.equals(key) || LEGACY_CARDS_KEY.equals(key))) return;
            secureVaultStore.putString(key, value);
        }

        @JavascriptInterface
        public String loadData(String key) {
            // Hold back saved-card data while locked; UI prefs (theme, text size) still load.
            if (locked && (CARDS_KEY.equals(key) || LEGACY_CARDS_KEY.equals(key))) return "";
            return secureVaultStore.getString(key);
        }

        @JavascriptInterface
        public void removeData(String key) {
            if (locked && (CARDS_KEY.equals(key) || LEGACY_CARDS_KEY.equals(key))) return;
            secureVaultStore.remove(key);
        }
    }

    // ========================================================================
    //  JS NOTIFICATION (thread-safe)
    // ========================================================================
    private void notifyJS(String cb, String data) {
        if (webView == null || !webViewReady) return;
        // JSONObject.quote escapes quotes, backslashes, control chars and U+2028/U+2029,
        // so tag content can never terminate the literal and inject script.
        String literal = JSONObject.quote(data);
        runOnUiThread(() -> webView.evaluateJavascript(
            "window.NfcCallbacks&&window.NfcCallbacks." + cb + "(" + literal + ")", null));
    }

    private void openExternalUri(Uri uri) {
        if (uri == null || uri.getScheme() == null) return;
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.US);
        if (!Arrays.asList("https", "http", "mailto", "tel", "geo").contains(scheme)) {
            notifyJS("onReadError", "Blocked unsupported link type");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            notifyJS("onReadError", "No app can open this link");
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? "Unknown error" : message;
    }

    // ========================================================================
    //  UTILITY METHODS
    // ========================================================================
    private void logApdu(JSONArray apdus, String label, byte[] sent, byte[] recv) {
        try {
            JSONObject o = new JSONObject();
            o.put("command", label);
            o.put("sent", bytesToHex(sent, " "));
            o.put("response", bytesToHex(recv, " "));
            if (recv != null && recv.length >= 2) {
                o.put("sw", bytesToHex(Arrays.copyOfRange(recv, Math.max(0, recv.length - 2), recv.length), ""));
            }
            apdus.put(o);
        } catch (Exception ignored) {}
    }

    private static boolean isPrintable(String s) {
        for (char c : s.toCharArray()) {
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

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
        return NfcDataUtils.decodeHex(hex);
    }

    private static String getManufacturer(int code) {
        switch (code) {
            case 0x01: return "Motorola";
            case 0x02: return "STMicroelectronics";
            case 0x03: return "Hitachi";
            case 0x04: return "NXP Semiconductors";
            case 0x05: return "Infineon Technologies";
            case 0x06: return "Cylink";
            case 0x07: return "Texas Instruments";
            case 0x08: return "Fujitsu";
            case 0x09: return "Matsushita Electronics";
            case 0x0A: return "NEC";
            case 0x0B: return "Oki Electric";
            case 0x0C: return "Toshiba";
            case 0x0D: return "Mitsubishi Electric";
            case 0x0E: return "Samsung Electronics";
            case 0x0F: return "Hyundai Electronics";
            case 0x10: return "LG Semiconductors";
            case 0x11: return "Emosyn-EM Microelectronics";
            case 0x12: return "INSIDE Technology";
            case 0x13: return "ORGA Kartensysteme";
            case 0x14: return "SHARP";
            case 0x15: return "ATMEL";
            case 0x16: return "EM Microelectronic-Marin";
            case 0x17: return "SMARTRAC Technology";
            case 0x18: return "ZMD AG";
            case 0x19: return "XICOR";
            case 0x1A: return "Sony";
            case 0x1B: return "Malaysia Microelectronic Solutions";
            case 0x1C: return "Emosyn";
            case 0x1D: return "Shanghai Fudan Microelectronics";
            case 0x1E: return "Magellan Technology";
            case 0x1F: return "Melexis";
            case 0x20: return "Renesas Technology";
            case 0x21: return "TAGSYS";
            case 0x22: return "Transcore";
            case 0x23: return "Shanghai Belling";
            case 0x24: return "Masktech Germany";
            case 0x25: return "Innovision Research and Technology";
            case 0x26: return "Hitachi ULSI Systems";
            case 0x27: return "Yubico";
            case 0x28: return "Ricoh";
            case 0x29: return "ASK";
            case 0x2A: return "Unicore Microsystems";
            case 0x2B: return "Dallas Semiconductor / Maxim";
            case 0x2C: return "Impinj";
            case 0x2D: return "RightPlug Alliance";
            case 0x2E: return "Broadcom";
            case 0x2F: return "MStar Semiconductor";
            case 0x30: return "BeeDar Technology";
            case 0x31: return "RFIDsec";
            case 0x32: return "Schweizer Electronic";
            case 0x33: return "AMIC Technology";
            case 0x34: return "Mikron JSC";
            case 0x35: return "Fraunhofer Institute";
            case 0x36: return "IDS Microchip AG";
            case 0x37: return "Kovio";
            case 0x38: return "HMT Microelectronic";
            case 0x39: return "Silicon Craft Technology";
            case 0x3A: return "Advanced Film Device";
            case 0x3B: return "Nitecrest";
            case 0x3C: return "Verayo";
            case 0x3D: return "HID Global";
            case 0x3E: return "Productivity Engineering";
            case 0x3F: return "Austriamicrosystems (ams)";
            case 0x40: return "Gemalto SA";
            case 0x41: return "Renesas Electronics";
            case 0x42: return "3Alogics";
            case 0x43: return "Top TroniQ Asia";
            case 0x44: return "Gentag";
            case 0x45: return "Invengo Information Technology";
            case 0x46: return "Guangzhou Sysur Microelectronics";
            case 0x47: return "CEITEC";
            case 0x48: return "Shanghai Quanray Electronics";
            case 0x49: return "MediaTek";
            case 0x4A: return "Angstrem PJSC";
            case 0x4B: return "Celisic Semiconductor";
            case 0x4C: return "LEGIC Identsystems";
            case 0x4D: return "Balluff";
            case 0x4E: return "Oberthur Technologies";
            case 0x4F: return "Silterra Malaysia";
            case 0x50: return "DELTA Danish Electronics";
            case 0x51: return "Giesecke & Devrient";
            case 0x52: return "Shenzhen China Vision Microelectronics";
            case 0x53: return "Shanghai Feiju Microelectronics";
            case 0x54: return "Intel";
            case 0x55: return "Microsensys";
            case 0x56: return "Sonix Technology";
            case 0x57: return "Qualcomm";
            case 0x58: return "Realtek Semiconductor";
            case 0x59: return "Freevision Technologies";
            case 0x5A: return "Giantec Semiconductor";
            case 0x5B: return "JSC Angstrem-T";
            case 0x5C: return "STARCHIP France";
            case 0x5D: return "SPIRTECH";
            case 0x5E: return "GANTNER Electronic";
            case 0x5F: return "Nordic Semiconductor";
            case 0x60: return "Verisiti";
            case 0x61: return "Wearlinks Technology";
            case 0x62: return "Userstar Information Systems";
            case 0x63: return "Pragmatic Printing";
            case 0x64: return "Associacao do Laboratorio (AOI)";
            case 0x65: return "Tego";
            case 0x66: return "Continental Microelectronics";
            case 0x67: return "SecureCode";
            case 0x68: return "Sichuan Kiloway Electronics";
            case 0x69: return "Confidex";
            case 0x6A: return "Espressif Systems";
            case 0x6B: return "Amazon.com";
            case 0x6C: return "SmartCard Manufacturing";
            case 0x6D: return "Sun Han Technology";
            case 0x6E: return "Junsun Technology";
            case 0x6F: return "Shanghai Sensor Electronic Technology";
            case 0x70: return "Custom (Muhlbauer)";
            default:   return "Unknown (0x" + String.format("%02X", code) + ")";
        }
    }
}
