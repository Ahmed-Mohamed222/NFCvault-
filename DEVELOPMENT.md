# NFC Vault — Historical Development Documentation

> **Version 3 note:** This file records earlier implementation phases. Older sections that describe raw card cloning, unencrypted `SharedPreferences`, or “universal” support are historical and no longer describe the app. See [`README.md`](README.md) for the current product, security, compatibility, and build documentation.

> **Universal NFC Reader, Writer & Card Manager for Android**
> Version 2.0 · Built July 2026

---

## Table of Contents

- [Project Overview](#project-overview)
- [Architecture](#architecture)
- [Technology Stack](#technology-stack)
- [Development Timeline](#development-timeline)
- [Phase 1 — Audit & Bug Fixing](#phase-1--audit--bug-fixing)
- [Phase 2 — Universal NFC Implementation](#phase-2--universal-nfc-implementation)
- [Phase 3 — Frontend Overhaul](#phase-3--frontend-overhaul)
- [Phase 4 — Persistent Storage & Auto-Scan](#phase-4--persistent-storage--auto-scan)
- [Phase 5 — Expanded Card Coverage](#phase-5--expanded-card-coverage)
- [Supported Card Types](#supported-card-types)
- [File Structure](#file-structure)
- [Key Technical Decisions](#key-technical-decisions)
- [Security Considerations](#security-considerations)
- [Build & Deployment](#build--deployment)
- [Known Limitations](#known-limitations)
- [Future Improvements](#future-improvements)

---

## Project Overview

NFC Vault started as a basic MIFARE Classic-only NFC reader built with Android's `Activity` + `WebView` architecture. Through a multi-phase development process, it was transformed into a **universal NFC reader** capable of detecting, reading, and displaying data from **14 different NFC card types** — covering every tag technology supported by the Android NFC API.

### Core Capabilities

- **Read** any NFC card type automatically (no manual type selection)
- **Save** scanned cards to a persistent local library (SharedPreferences-backed)
- **Clone** writable cards (MIFARE Classic, NTAG/Ultralight)
- **Export** card data as JSON files
- **Auto-detect** cards when tapped — no need to press "Scan" first
- **Identify** exact chip variants (NTAG 213 vs 215 vs 216, DESFire EV1/EV2/EV3, ICODE SLIX vs SLIX2, etc.)

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│                  Android Layer                   │
│                                                  │
│  NfcVaultActivity.java                           │
│  ├── NFC Foreground Dispatch (all tech types)    │
│  ├── Universal Card Reader (auto-detect)         │
│  │   ├── MIFARE Classic reader                   │
│  │   ├── MIFARE Ultralight/NTAG reader           │
│  │   ├── IsoDep smart detection                  │
│  │   │   ├── DESFire (GET_VERSION)               │
│  │   │   ├── EMV (SELECT PPSE)                   │
│  │   │   └── Generic ISO-DEP                     │
│  │   ├── NDEF reader                             │
│  │   ├── NFC-V reader (+ GET_SYSTEM_INFO)        │
│  │   ├── NFC-F / FeliCa reader                   │
│  │   ├── NFC-A / NFC-B generic readers           │
│  │   ├── NFC Barcode (Kovio) reader              │
│  │   └── NDEF Formatable detector                │
│  ├── Card Writer (MIFARE Classic)                │
│  ├── SharedPreferences storage bridge            │
│  └── JavaScript Bridge (@JavascriptInterface)    │
│       ├── startReadMode()                        │
│       ├── startWriteMode(json)                   │
│       ├── saveData(key, value)                   │
│       ├── loadData(key)                          │
│       └── removeData(key)                        │
│                                                  │
├──────────────── WebView ─────────────────────────┤
│                                                  │
│  index.html (React 18 + Babel)                   │
│  ├── NfcCallbacks (global event bus)             │
│  ├── Persistent Storage (SharedPrefs primary)    │
│  ├── App Component                               │
│  │   ├── Scan Tab (auto-scan + manual scan)      │
│  │   ├── Library Tab (saved cards)               │
│  │   └── Clone Tab (write to blank card)         │
│  ├── DetailPanel (dynamic per card type)         │
│  ├── Card-Type Renderers                         │
│  │   ├── SectorRow (MIFARE Classic)              │
│  │   ├── PageGrid (NTAG/Ultralight)              │
│  │   ├── NdefRecords (NDEF)                      │
│  │   ├── ApduLog (ISO-DEP / DESFire / EMV)       │
│  │   ├── DESFireDetail                           │
│  │   ├── EmvDetail                               │
│  │   ├── BlockGrid (NFC-V)                       │
│  │   ├── FeliCaDetail                            │
│  │   ├── BarcodeDetail (Kovio)                   │
│  │   └── RawHexDump (JSON view)                  │
│  └── Demo Mode (random card generator)           │
│                                                  │
└─────────────────────────────────────────────────┘
```

---

## Technology Stack

| Component | Technology | Version |
|---|---|---|
| **Platform** | Android | SDK 21–34 (Android 5.0–14) |
| **Language** | Java | 11 |
| **UI Framework** | React | 18.2.0 (CDN) |
| **Transpiler** | Babel Standalone | 7.23.2 (CDN) |
| **Typography** | IBM Plex Sans / Mono | Google Fonts CDN |
| **Build System** | Gradle (Kotlin DSL) | 8.2 |
| **Android Plugin** | AGP | 8.2.0 |
| **UI Host** | Android WebView | Built-in |
| **Storage** | SharedPreferences | Android native |

### Why WebView + React?

The app uses a single `WebView` hosting a React 18 SPA instead of native Android UI. This provides:

- **Rapid UI iteration** — HTML/CSS/JS is faster to develop than XML layouts + Java views
- **Rich, animated UI** — CSS animations, gradients, and glassmorphism effects
- **Single-file deployment** — The entire frontend is one `index.html` file
- **Cross-platform potential** — The same frontend could be reused in a Cordova/Capacitor wrapper

The NFC layer remains fully native Java for direct hardware access.

---

## Development Timeline

| Phase | Date | Focus |
|---|---|---|
| **Phase 1** | April 2026 | Audit & fix compilation errors |
| **Phase 2** | April 2026 | Universal NFC reader implementation |
| **Phase 3** | April 2026 | Frontend overhaul with React 18 |
| **Phase 4** | July 2026 | Persistent storage, auto-scan, bug fixes |
| **Phase 5** | July 2026 | Expanded card coverage (DESFire, EMV, Kovio, etc.) |
| **Phase 6** | July 12, 2026 | NDEF authoring, working HCE, library management |

---

## Phase 1 — Audit & Bug Fixing

### Problems Found

The original project had multiple compilation-blocking issues:

| File | Issue | Fix |
|---|---|---|
| `libs.versions.toml` | AGP version `8.13.2` — incompatible with available Gradle | Changed to `8.2.0` |
| `gradle-wrapper.properties` | Gradle `8.13` — too new for AGP 8.2.0 | Downgraded to `8.2` |
| `app/build.gradle.kts` | `JavaVersion.VERSION_17` — user's JDK was 11 | Changed to `VERSION_11` |
| `local.properties` | Double-escaped backslashes in `sdk.dir` path | Fixed escaping |
| `libs.versions.toml` | Unused AndroidX dependencies declared | Removed dead entries |
| `AndroidManifest.xml` | Missing `android:icon` and `android:roundIcon` | Added mipmap references |

### Key Insight

The AGP (Android Gradle Plugin) version must be compatible with the installed Gradle wrapper version. AGP 8.2.0 requires Gradle 8.2+. The original project had AGP 8.13.2 with Gradle 8.13, which hadn't been downloaded yet.

---

## Phase 2 — Universal NFC Implementation

### Original State

The original `NfcVaultActivity.java` only supported **MIFARE Classic** cards. It had:
- A fixed key dictionary of ~6 keys
- Only `MifareClassic` tech in the foreground dispatch
- No support for any other card technology

### Rewrite Strategy

The entire `readCard()` method was replaced with a **branching auto-detection** pattern:

```java
MifareClassic mfc = MifareClassic.get(tag);
MifareUltralight mfu = MifareUltralight.get(tag);
Ndef ndef = Ndef.get(tag);
IsoDep isoDep = IsoDep.get(tag);
// ... etc

if (mfc != null) {
    readMifareClassic(mfc, tag, card);
} else if (mfu != null) {
    readMifareUltralight(mfu, tag, card);
} else if (isoDep != null) {
    readIsoDepSmart(isoDep, tag, card);
}
// ... etc
```

Each card type has its own dedicated reader method that:
1. Connects to the tag via the appropriate tech class
2. Reads all available data using technology-specific commands
3. Populates a `JSONObject` with structured data
4. Closes the connection in a `finally` block
5. Falls through to a generic handler if no specific tech matches

### MIFARE Key Dictionary

The key dictionary was expanded to 12 common keys:

```
FF FF FF FF FF FF  — Factory default
A0 A1 A2 A3 A4 A5  — MAD (MIFARE Application Directory)
D3 F7 D3 F7 D3 F7  — NFC Forum
00 00 00 00 00 00  — All zeros
A0 B0 C0 D0 E0 F0  — Common Chinese manufacturer
AA BB CC DD EE FF  — Common test key
4D 3A 99 C3 51 DD  — Common transport
1A 98 2C 7E 45 9A  — Common hotel
71 4C 5C 88 6E 97  — Common access control
58 7E E5 F9 35 0F  — Common parking
10 7B 4B 30 37 84  — Miwa Lock
41 4C 41 52 4F 4E  — ALARON system
```

Each sector attempts authentication with **Key A** first for all 12 keys, then **Key B** for all 12 keys, stopping at the first successful authentication.

### NFC Tech Filter

The `nfc_tech_filter.xml` was expanded from 2 to 10 tech-list entries to ensure Android dispatches all tag types to the app:

```xml
<tech-list><tech>android.nfc.tech.MifareClassic</tech></tech-list>
<tech-list><tech>android.nfc.tech.MifareUltralight</tech></tech-list>
<tech-list><tech>android.nfc.tech.Ndef</tech></tech-list>
<tech-list><tech>android.nfc.tech.NdefFormatable</tech></tech-list>
<tech-list><tech>android.nfc.tech.IsoDep</tech></tech-list>
<tech-list><tech>android.nfc.tech.NfcA</tech></tech-list>
<tech-list><tech>android.nfc.tech.NfcB</tech></tech-list>
<tech-list><tech>android.nfc.tech.NfcF</tech></tech-list>
<tech-list><tech>android.nfc.tech.NfcV</tech></tech-list>
<tech-list><tech>android.nfc.tech.NfcBarcode</tech></tech-list>
```

> **Note:** Each `<tech-list>` contains a single tech entry. Android uses OR logic between tech-lists — a tag matching ANY single tech-list will trigger the intent. Within a tech-list, AND logic applies.

---

## Phase 3 — Frontend Overhaul

### Design System

The frontend uses a dark-themed design with monospace accents:

- **Background:** `#060c12` (near-black with blue undertone)
- **Primary accent:** `#00f5d4` (cyan/teal)
- **Font stack:** IBM Plex Sans (UI) + IBM Plex Mono (data)
- **Card components:** Gradient backgrounds with colored borders
- **Animations:** `fadeSlide`, `ping` (scan rings), `spin` (loading), `pulse`

### Card Type Color Coding

Each card type has a dedicated color for instant visual identification:

| Card Type | Color | Icon |
|---|---|---|
| MIFARE Classic | `#00f5d4` (teal) | 🔑 |
| MIFARE Plus | `#00e0b0` (green-teal) | 🔐 |
| NTAG / Ultralight | `#7B61FF` (purple) | 🏷️ |
| DESFire | `#E040FB` (magenta) | 🛡️ |
| EMV Payment | `#FFD700` (gold) | 💳 |
| NDEF | `#00BFFF` (sky blue) | 📝 |
| NDEF Formatable | `#5599CC` (slate blue) | 📋 |
| ISO-DEP | `#FF6B6B` (coral) | 💳 |
| NFC Barcode | `#B0FF57` (lime) | 📊 |
| NFC-V | `#FFB800` (amber) | 📦 |
| FeliCa | `#FF69B4` (pink) | 🚃 |
| NFC-A | `#4ECDC4` (mint) | 📡 |
| NFC-B | `#95E1D3` (seafoam) | 📡 |

### Component Hierarchy

```
App
├── Header (NFC status indicator + saved count)
├── Tab Bar (Scan / Library / Clone)
├── Scan Tab
│   ├── Ready state (scan button + card type list)
│   ├── Scanning state (animated rings)
│   └── Result state (DetailPanel + save/export/clone buttons)
├── Library Tab
│   ├── CardChip list (saved cards)
│   └── DetailPanel (selected card detail)
└── Clone Tab
    ├── Select card step
    ├── Confirm step
    ├── Writing step (spinner)
    └── Done step (success checkmark)

DetailPanel
├── Header (name, type badge, edit name)
├── Info fields table (tag type, manufacturer, serial, SAK, etc.)
├── Action buttons (Save, Clone, Export)
├── Card-specific data renderers
│   ├── SectorRow × N (MIFARE Classic)
│   ├── PageGrid (NTAG/Ultralight)
│   ├── NdefRecords (NDEF)
│   ├── ApduLog (ISO-DEP/DESFire/EMV)
│   ├── DESFireDetail (version + applications)
│   ├── EmvDetail (payment network + AIDs)
│   ├── BarcodeDetail (Kovio hex/ASCII)
│   ├── BlockGrid (NFC-V)
│   └── FeliCaDetail (IDm, PMm, system code)
└── Raw Hex toggle (full JSON dump)
```

---

## Phase 4 — Persistent Storage & Auto-Scan

### Problem: Data Loss

The original app used `localStorage` for saving scanned cards. However, `localStorage` in Android WebViews loaded via `file://` URLs is **unreliable across app restarts** — the WebView data directory can be cleared by the system, especially under memory pressure.

### Solution: SharedPreferences Bridge

A new `@JavascriptInterface` storage API was added to `NfcBridge`:

```java
@JavascriptInterface
public void saveData(String key, String value) {
    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    prefs.edit().putString(key, value).apply();
}

@JavascriptInterface
public String loadData(String key) {
    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    return prefs.getString(key, "");
}
```

The frontend now uses `NfcBridge.saveData()`/`loadData()` as the **primary** storage mechanism, with `localStorage` as a secondary backup. On first launch, any existing `localStorage` data is **automatically migrated** to SharedPreferences.

### Problem: Silent Card Drops

When a user tapped a card without first pressing the "Scan" button, the native side would call `window.NfcCallbacks.onCardRead()`, but since `window.__nfcRR` (the manual scan callback) was `null`, the data was silently discarded.

### Solution: Global Event Bus

A global event listener system was added:

```javascript
const cardEventListeners = [];
function onCardEvent(cb) {
    cardEventListeners.push(cb);
    return () => { /* cleanup */ };
}

window.NfcCallbacks = {
    onCardRead: (j) => {
        const card = JSON.parse(j);
        if (window.__nfcRR) {
            // Manual scan callback exists — use it
            window.__nfcRR(card);
        } else {
            // Auto-scan — broadcast to all listeners
            cardEventListeners.forEach(cb => cb(card));
        }
    }
};
```

Now cards are **always** captured, regardless of whether the user pressed "Scan" first.

### Other Fixes

- **WebView ready guard:** Tags scanned at cold launch (before `index.html` finishes loading) are deferred until `onPageFinished()` fires
- **Intent clearing:** `intent.setAction(null)` after processing prevents re-processing the same tag on activity resume
- **NFC disabled detection:** Checks `isNfcEnabled()` and shows a user-facing warning if NFC is off
- **Export feature:** Added JSON export button to download card data as a `.json` file
- **Cancel scan:** Added cancel button during the scanning animation

---

## Phase 5 — Expanded Card Coverage

### Research

A systematic audit of the Android NFC API (`android.nfc.tech` package) revealed 10 tag technology classes. The app originally supported 8 of them but was missing:

- `NfcBarcode` — Kovio barcode tags
- `NdefFormatable` — Unformatted tags

Additionally, within the `IsoDep` class, several major card families were being treated as generic ISO-DEP when they could be specifically identified:

- **MIFARE DESFire** — The most common smart card in access control
- **EMV Payment Cards** — Contactless credit/debit cards (Visa, Mastercard, etc.)

### MIFARE DESFire Detection

DESFire cards respond to the `GET_VERSION` command sent as a wrapped APDU:

```
TX: 90 60 00 00 00  (CLA=90, INS=60 GET_VERSION, P1=00, P2=00, Le=00)
RX: [7 bytes HW version] 91 AF  (status: more data available)
TX: 90 AF 00 00 00  (continue)
RX: [7 bytes SW version] 91 AF
TX: 90 AF 00 00 00  (continue)
RX: [7 bytes production] 91 00  (success)
```

The hardware version bytes are decoded to identify the exact DESFire variant:

| HW Type | Major.Minor | Variant |
|---|---|---|
| 0x01 | 0.x | DESFire EV1 |
| 0x01 | 1.x | DESFire EV2 |
| 0x01 | 2.x | DESFire EV3 |

After version detection, the app also lists DESFire application IDs using command `0x6A` (GET_APPLICATION_IDS).

### EMV Payment Card Detection

If the DESFire probe fails, the app tries the EMV `SELECT PPSE` command:

```
TX: 00 A4 04 00 0E [2PAY.SYS.DDF01] 00
RX: [FCI template with AID list] 90 00
```

The response is parsed for tag `4F` (AID) entries, and each AID is matched against known payment network prefixes:

| AID Prefix | Network |
|---|---|
| `A000000003` | Visa |
| `A000000004` | Mastercard |
| `A000000025` | American Express |
| `A000000065` | JCB |
| `A000000152` | Discover |
| `A000000324` | UnionPay |
| `A000000029` | Dankort |
| `D5280050` | girocard |

Each detected AID is then individually `SELECT`ed to read the FCI (File Control Information) and extract the application label (tag `50`).

### Exact NTAG Identification

Instead of guessing the NTAG variant from the Capability Container (page 3), the app now sends the `GET_VERSION` command (byte `0x60`) directly via `NfcA.transceive()`:

```
TX: 60
RX: [fixed header] [vendor] [product type] [subtype] [major] [minor] [storage] [protocol]
```

The storage size byte maps to specific chip variants:

| Product Type | Storage | Chip |
|---|---|---|
| 0x04 | 0x06 | NTAG 210 |
| 0x04 | 0x0A | NTAG 212 |
| 0x04 | 0x0F | NTAG 213 |
| 0x04 | 0x11 | NTAG 215 |
| 0x04 | 0x13 | NTAG 216 |
| 0x03 | 0x06 | Ultralight EV1 (48B) |
| 0x03 | 0x0B | Ultralight EV1 (128B) |
| 0x05 | 0x13 | NTAG I2C 1K |
| 0x05 | 0x15 | NTAG I2C 2K |
| 0x07 | * | NTAG I2C Plus |

### NFC-V Chip Identification

The `GET_SYSTEM_INFORMATION` command (`0x2B`) returns detailed chip information:

```
TX: 20 2B  (flags=0x20, cmd=0x2B)
RX: [flags] [info_flags] [8-byte UID] [DSFID] [AFI] [num_blocks, block_size] [IC reference]
```

The IC reference byte, combined with the manufacturer code, identifies the exact chip:

| Manufacturer | IC Ref | Chip |
|---|---|---|
| NXP (0x04) | 0x01 | ICODE SLI |
| NXP (0x04) | 0x41 | ICODE SLIX |
| NXP (0x04) | 0x45 | ICODE SLIX2 |
| NXP (0x04) | 0x46 | ICODE DNA |
| ST (0x02) | 0x2D | ST25DV04K |
| ST (0x02) | 0x2E | ST25DV16K |
| ST (0x02) | 0x2F | ST25DV64K |
| TI (0x07) | 0x00 | Tag-it HF-I Standard |

---

## Phase 6 — NDEF Authoring, Working HCE & Library Management

Phase 6 closed the biggest functional gaps from the earlier "Future Improvements" list and fixed a latent emulation bug.

### 1. NDEF Writer (new "Write Data" mode)

Previously the app could only *clone* an existing dump. It now authors fresh NDEF messages and writes them to any writable NDEF or `NdefFormatable` tag (blank NTAG / Ultralight / NDEF-formatted MIFARE Classic stickers).

A native `buildNdefMessage(recordsJson)` turns a JSON record spec into an `NdefMessage` using the platform factories, and `writeNdef(tag)` handles capacity/read-only checks, `Ndef.writeNdefMessage()`, and the `NdefFormatable.format()` fallback:

| UI preset | Record built |
|---|---|
| URL / Link | `NdefRecord.createUri()` |
| Text | `NdefRecord.createTextRecord(lang, text)` |
| Phone / Email / Location | `createUri("tel:" / "mailto:" / "geo:")` |
| Android App | `NdefRecord.createApplicationRecord(pkg)` (AAR) |
| Custom MIME | `NdefRecord.createMime(type, bytes)` — e.g. `text/vcard` |

The bridge exposes `startNdefWrite(recordsJson)` (returns the byte size, or `ERR:<msg>` on invalid input) and `cancelWrite()` to disarm a queued write when the user navigates away.

### 2. Host Card Emulation actually works now

**Bug:** `CardEmulationService.setEmulationData()` looked for a field named `ndefMessageHex` that the reader never wrote, so *every* "Emulate" tap served an empty NDEF file.

**Fix:** the reader now stores `ndefMessageHex` (the raw `NdefMessage.toByteArray()`) on every NDEF-bearing read (NDEF, NTAG/Ultralight, and the generic append path). The service prefers that exact byte stream and falls back to rebuilding a message from parsed URI/TEXT/MIME records. The Emulate button is now only shown for cards that actually carry NDEF data.

### 3. Library management

- **Import** — restore cards from an exported JSON file (single object or array); each card is re-keyed to avoid ID collisions.
- **Export All** — one-tap JSON backup of the entire library.
- **Search** — live filter by name, serial, tag type, or manufacturer.
- **Type filter chips** — generated from the card types actually present in the library.

### 4. Polish & correctness

- **Copy-to-clipboard** on every detail field via a native `copyToClipboard()` bridge (with `navigator.clipboard` / `execCommand` fallbacks in demo mode).
- **Deprecated API fixed** — `getParcelableExtra(EXTRA_TAG, Tag.class)` on Android 13+ (Tiramisu), with the legacy overload guarded for older devices.
- **Manufacturer table** expanded from 17 to ~110 entries (full ISO/IEC 7816-6 registration list).
- **NDEF parsing** now recognizes Android Application Records (`android.com:pkg`) and ASCII-decodes external-type payloads.

---

## Supported Card Types

### Complete Coverage Matrix

| # | Card Type | Android Tech Class | Detection | Data Read | Can Write |
|---|---|---|---|---|---|
| 1 | MIFARE Classic 1K/4K | `MifareClassic` | Direct | All sectors with 12-key dictionary | ✅ |
| 2 | MIFARE Plus | `MifareClassic` (TYPE_PLUS) | SAK + type check | Sectors (SL1 mode) | ✅ |
| 3 | MIFARE Ultralight | `MifareUltralight` | Direct | All pages + GET_VERSION | ❌ |
| 4 | MIFARE Ultralight C | `MifareUltralight` | Type check | All pages | ❌ |
| 5 | MIFARE Ultralight EV1 | `MifareUltralight` | GET_VERSION | All pages + chip info | ❌ |
| 6 | NTAG 210/212/213/215/216 | `MifareUltralight` | GET_VERSION | All pages + chip info | ❌ |
| 7 | NTAG I2C / I2C Plus | `MifareUltralight` | GET_VERSION | All pages + chip info | ❌ |
| 8 | MIFARE DESFire EV1/EV2/EV3 | `IsoDep` | GET_VERSION APDU | Version info, app list | ❌ |
| 9 | EMV Payment Cards | `IsoDep` | SELECT PPSE | Network, AIDs, labels | ❌ |
| 10 | ISO-DEP (generic) | `IsoDep` | Fallback | Historical bytes, APDU | ❌ |
| 11 | NDEF | `Ndef` | Direct | All records decoded | ❌ |
| 12 | NDEF Formatable | `NdefFormatable` | Direct | Format status | ❌ |
| 13 | NFC Barcode (Kovio) | `NfcBarcode` | Direct | Barcode hex + ASCII | ❌ |
| 14 | NFC-V / ISO 15693 | `NfcV` | Direct | Blocks + system info | ❌ |
| 15 | NFC-F / FeliCa | `NfcF` | Direct | IDm, PMm, system code | ❌ |
| 16 | NFC-A (generic) | `NfcA` | Fallback | ATQA, SAK | ❌ |
| 17 | NFC-B (generic) | `NfcB` | Fallback | App data, protocol info | ❌ |

### Payment Networks Identified

Visa · Mastercard · American Express · JCB · Discover · UnionPay · Dankort · girocard · DNA

### FeliCa Systems Identified

Suica/PASMO (transit) · WAON (e-money) · FeliCa Lite/Lite-S · NDEF on FeliCa · FeliCa Plug

### Manufacturer Identification (16 vendors)

NXP · STMicroelectronics · Infineon · Texas Instruments · EM Microelectronic · Motorola · Hitachi · Fujitsu · Matsushita · NEC · Oki Electric · Toshiba · Mitsubishi Electric · Samsung · Hyundai · LG Semiconductors

---

## File Structure

```
NFCvault/
├── build.gradle.kts                          # Root build (AGP 8.2.0)
├── settings.gradle.kts                       # Project settings
├── local.properties                          # SDK path
├── gradlew / gradlew.bat                     # Gradle wrapper scripts
├── gradle/
│   ├── libs.versions.toml                    # Version catalog
│   └── wrapper/
│       └── gradle-wrapper.properties         # Gradle 8.2
└── app/
    ├── build.gradle.kts                      # App build config
    └── src/main/
        ├── AndroidManifest.xml               # Permissions + NFC filter
        ├── java/com/nfcvault/
        │   └── NfcVaultActivity.java         # Main activity (~870 lines)
        ├── assets/www/
        │   └── index.html                    # React 18 frontend (~720 lines)
        └── res/
            ├── layout/activity_main.xml      # WebView layout
            ├── xml/nfc_tech_filter.xml        # 10 tech-list entries
            ├── values/
            │   ├── strings.xml               # App name
            │   ├── themes.xml                # AppTheme
            │   └── colors.xml                # Icon background
            ├── mipmap-mdpi/                  # 48px icons
            ├── mipmap-hdpi/                  # 72px icons
            ├── mipmap-xhdpi/                 # 96px icons
            ├── mipmap-xxhdpi/                # 144px icons
            ├── mipmap-xxxhdpi/               # 192px icons
            └── mipmap-anydpi-v26/            # Adaptive icon XML
```

---

## Key Technical Decisions

### 1. Detection Order Matters

The card detection order in `readCard()` is **intentional**:

```
MifareClassic → MifareUltralight → IsoDep → Ndef → NfcBarcode → NfcV → NfcF → NfcA → NfcB → NdefFormatable
```

- **MIFARE Classic/Ultralight first:** These are the most data-rich readers and need exclusive tag connection
- **IsoDep before Ndef:** DESFire and EMV cards also support NDEF, but we want the richer IsoDep data
- **NDEF fallback:** For any tag that also has NDEF records (e.g., NTAG), they're appended at the end regardless of the primary reader used

### 2. IsoDep Sub-Type Detection

`IsoDep` is a catch-all for ISO 14443-4 compliant cards. Rather than treating them all as "ISO-DEP", the `readIsoDepSmart()` method uses a **probe sequence**:

1. **Try DESFire GET_VERSION** (`90 60 00 00 00`) — if the response has status `91 AF` or `91 00`, it's a DESFire card
2. **Try EMV SELECT PPSE** (`2PAY.SYS.DDF01`) — if status word is `9000`, it's a payment card
3. **Fall back to generic** ISO-DEP with a simple SELECT command

### 3. Thread Safety

All NFC reads happen on **daemon background threads** to avoid blocking the UI. Results are posted back to the WebView via `runOnUiThread()`:

```java
Thread t = new Thread(() -> readCard(tag));
t.setDaemon(true);
t.start();
```

The JavaScript notification method (`notifyJS`) always marshals to the UI thread before calling `evaluateJavascript()`.

### 4. JSON String Escaping

Card data is passed from Java to JavaScript as a JSON string inside a single-quoted JS string literal. This requires escaping `\`, `'`, `\n`, and `\r` to prevent injection or parse errors.

---

## Security Considerations

### What the App CAN Read from Payment Cards

- Application Identifiers (AIDs)
- Payment network identification (Visa, Mastercard, etc.)
- Application labels (e.g., "VISA DEBIT")
- Public FCI (File Control Information) data

### What the App CANNOT Read

- **PAN** (card number) — modern cards encrypt this
- **CVV/CVC** — never transmitted via NFC
- **Cardholder name** — often not included in contactless interface
- **PIN** — never accessible via NFC
- **Track data** — encrypted on modern EMV cards

### MIFARE Classic Security Note

MIFARE Classic uses a proprietary encryption scheme (Crypto1) that has been publicly broken. The app uses a dictionary of 12 common default keys. Cards with custom keys will show sectors as "AUTH FAILED" but no data will be exposed.

---

## Build & Deployment

### Prerequisites

- Android SDK (API 34)
- JDK 11+
- Gradle 8.2 (auto-downloaded by wrapper)

### Build Commands

```bash
# Debug APK
.\gradlew.bat assembleDebug

# Clean + build
.\gradlew.bat clean assembleDebug

# Output location
app/build/outputs/apk/debug/app-debug.apk
```

### Installation

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### APK Size

~120 KB (extremely lightweight — no external dependencies)

---

## Known Limitations

| Limitation | Reason |
|---|---|
| **125 kHz RFID not supported** | Android NFC hardware only supports 13.56 MHz |
| **HID iCLASS proprietary data** | Requires vendor-specific authentication keys |
| **DESFire file contents** | Reading files requires application-specific authentication |
| **EMV transaction data** | Requires certified terminal infrastructure |
| **Raw block/page write limited to MIFARE Classic & NTAG/Ultralight** | Other tech classes expose no generic write API in Android |
| **HCE limited to NDEF Type 4** | ISO-DEP only; the radio cannot emulate proprietary MIFARE Classic Crypto1 |
| **MIFARE Classic requires NXP NFC chip** | Some phones (Samsung with Broadcom chips) don't expose `MifareClassic` |

---

## Future Improvements

### Planned Features

- [x] **NTAG/Ultralight write support** — Write NDEF messages and raw pages *(Phase 5/6)*
- [x] **HCE (Host Card Emulation)** — Emulate saved NDEF cards (fixed in Phase 6)
- [x] **NDEF write** — Write URI, text, MIME, tel, email, geo, and AAR records *(Phase 6)*
- [x] **NdefFormatable formatting** — Format blank tags with NDEF structure *(Phase 6)*
- [x] **Search & filter** — Search saved cards by type/name/serial *(Phase 6)*
- [x] **Import JSON** — Import previously exported card data *(Phase 6)*
- [x] **Offline mode** — React + Babel bundled locally *(Phase 5)*
- [ ] **Card comparison** — Side-by-side diff of two scanned cards
- [ ] **Card groups/folders** — Organize saved cards into categories
- [ ] **Dark/light theme toggle** — Currently dark-only
- [ ] **Wi-Fi handover records** — Write `application/vnd.wfa.wsc` credential payloads

### Technical Debt

- [ ] Bundle React/Babel locally instead of loading from CDN (works offline)
- [ ] Replace deprecated `getParcelableExtra()` with typed version for API 33+
- [ ] Add ProGuard rules for release builds
- [ ] Add unit tests for hex conversion utilities
- [ ] Consider migrating to Kotlin

---

*Last updated: July 1, 2026*
