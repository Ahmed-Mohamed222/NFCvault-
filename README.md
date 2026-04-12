# 📱 NFC Vault

NFC Vault is a native Android application that operates on a hybrid architecture, acting as a bridge between the Android device's physical NFC transceiver and a web-based user interface. It is designed as a "Universal Reader" capable of detecting, connecting to, and extracting data from a wide variety of NFC tag types, while providing specialized write capabilities for MIFARE Classic cards.

## ⚙️ Core Architecture

* **Hybrid Frontend-Backend Bridge:** * **UI Layer:** Rendered using an Android `WebView` loading a local HTML interface.
  * **JavaScript Interface:** A custom `NfcBridge` allows the web frontend to trigger native Java methods for NFC operations.
  * **Asynchronous Processing:** All NFC I/O operations are offloaded to background daemon threads to prevent UI freezing.
* **NFC Dispatch System:** Uses Android's **Foreground Dispatch** to intercept NFC tags natively while the app is active on the screen.

## 📡 Supported NFC Technologies

The application implements auto-detection and specific read logic for:
1. **MIFARE Classic (1K/4K):** Comprehensive sector/block reading using a predefined cryptographic key dictionary.
2. **MIFARE Ultralight / NTAG:** Reads raw pages and auto-identifies capacities (e.g., NTAG 213/215/216) via the Capability Container.
3. **NDEF:** Fully parses records, decoding URIs, Text, and MIME types.
4. **ISO-DEP (Smart Cards):** Extracts Historical Bytes and Higher Layer Responses.
5. **NFC-V (ISO 15693):** Connects and reads single blocks.
6. **NFC-F (FeliCa):** Extracts IDm, PMm, and System Codes.
7. **NFC-A & NFC-B (Generic):** Extracts standard ATQA, SAK, and Protocol Information.

## 🔑 Key Features

### MIFARE Classic Authentication
The app includes a hardcoded dictionary (`KEY_DICT`) of 10 cryptographic keys (including Default, MAD, NFC Forum, and specific manufacturer keys). It iterates through sectors attempting to authenticate with Key A and Key B to extract protected hex data.

### Card Writing Operations
Writing is currently exclusively supported for **MIFARE Classic** tags. The write method accepts a JSON payload and systematically writes hex data to blocks. 
* *Safety Feature:* The write method explicitly skips `isTrailer` blocks to prevent accidental overwriting of sector access conditions and permanent card locking.

## 💻 JavaScript API Reference

The frontend interacts with the native Android code through the following injected methods (`window.NfcBridge`):

| Method | Description |
| :--- | :--- |
| `isNfcAvailable()` | Returns `true` if the device has an NFC chip. |
| `isNfcEnabled()` | Returns `true` if NFC is turned on in settings. |
| `startReadMode()` | Sets the app to read the next tapped tag. |
| `startWriteMode(json)` | Primes the app to write the provided JSON configuration to the next tapped MIFARE Classic tag. |

Callbacks invoked in JavaScript (`window.NfcCallbacks`):

| Callback | Trigger Condition |
| :--- | :--- |
| `onCardRead(json)` | Fired successfully after ANY tag is read and parsed. |
| `onReadError(error)` | Fired if a tag is dropped during read or IO exceptions occur. |
| `onWriteComplete(json)`| Fired after a write operation, returning success/fail metrics. |
| `onWriteError(error)` | Fired if the tag isn't MIFARE Classic, or if a write fails. |
