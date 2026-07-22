# NFC Vault

NFC Vault is an offline Android application for inspecting compatible NFC cards and tags, organizing permitted data in an encrypted local vault, writing standard NDEF records, and sharing standard NDEF messages through Android Host Card Emulation (HCE).

## What it supports

- Android NFC technologies: NFC-A, NFC-B, NFC-F/FeliCa, NFC-V/ISO 15693, ISO-DEP, NDEF, NDEF-formatable, MIFARE Classic, MIFARE Ultralight/NTAG, and NFC Barcode.
- User categories: access, transit, payment, hotel key, identity, loyalty, tickets, health, mobility, smart home, NFC tag, product/asset, and other.
- NDEF creation: websites, text, telephone actions, email actions, map locations, and vCards.
- NDEF copying: standards-compliant NDEF messages can be copied to compatible writable tags.
- NDEF sharing: eligible non-secure NDEF content can be presented as an NFC Forum Type 4 Tag on devices with HCE.
- Encrypted local storage using AES-GCM and an Android Keystore-held key.
- JSON import/export using Android's system document picker.
- Larger-text, high-contrast, reduced-motion, screen-reader, and keyboard-focus support.

## Important compatibility limits

Scanning a card does not mean it can be copied or emulated. Payment cards, access badges, transit passes, hotel keys, government or employee IDs, and many other credentials use issuer-controlled cryptography or secure hardware. NFC Vault can identify their public technology information but does not copy protected credentials, extract secret keys, or emulate payment cards. Use the issuer's official app or wallet enrollment when digital use is supported.

Phones also cannot read low-frequency RFID, UHF RFID, Bluetooth-only, or UWB-only credentials through the Android NFC API.

## Privacy and security

- The application does not request internet permission.
- Cleartext networking and WebView cross-origin file access are disabled.
- Saved data is encrypted before being written to app preferences.
- Android cloud backup and device-transfer backup are disabled for all app data.
- HCE requires the device to be unlocked and is limited to standard NDEF content.
- Only public NFC Forum/default MIFARE keys are tried; issuer-specific keys are not included.

## Build

Requirements:

- JDK 11 or newer
- Android SDK Platform 34 and Build Tools 34.0.0

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The installable development APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Production releases require a private signing configuration supplied by the application owner. The normal `assembleRelease` task creates an unsigned release artifact when no production key is configured.

## Architecture

- `NfcVaultActivity`: NFC dispatch, card inspection, safe NDEF writing, file picker integration, and the restricted JavaScript bridge.
- `SecureVaultStore`: AES-GCM encryption backed by Android Keystore.
- `CardEmulationService`: crash-safe NFC Forum Type 4 NDEF HCE service.
- `NfcDataUtils`: validated hex decoding and safe JSON filenames.
- `assets/www/index.html`: self-contained responsive React interface; no network-loaded resources.

## Testing notes

Automated checks cover utility validation, Java compilation, Android resources/manifests, lint, and APK packaging. NFC behavior must also be tested on physical Android devices because NFC chipsets, antenna placement, tag support, and vendor firmware differ.
