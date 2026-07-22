# NFC Vault v3 Walkthrough

## Home and scanning

Open **Home** and choose **Start NFC scan**. Hold the card or tag near the back of the phone and move it slowly until detection completes. The result explains the detected NFC technology, publicly readable data, and which actions are actually available.

If NFC is disabled, use **Turn on NFC** to open the Android NFC settings. Cards may also be detected automatically while NFC Vault is open.

## Saving and organizing

Choose **Save securely** to add an item to the encrypted on-device vault. In **Vault**, you can search by name, serial, manufacturer, or technology and filter by category.

Supported categories include access, transit, payment, hotel keys, identity, loyalty, tickets, health, mobility, smart home, NFC tags, product/asset tags, and other items. A category is organizational metadata—it does not grant access to protected card data.

Use **Rename** or the category selector to improve organization. Import and export use Android's system document picker and JSON files. Exported JSON is not encrypted, so store it carefully.

## Creating an NFC tag

Open **Create**, select a record type, and enter its content. NFC Vault can create links, text notes, phone and email actions, map locations, and vCards. Choose **Scan destination tag**, then hold a compatible writable NDEF tag near the phone.

For an existing saved item with a standard NDEF message, open **Create**, select **Copy saved card**, choose the source, and then select **Scan destination and copy**. Hold the writable destination near the phone until the result is displayed. This copies the public NDEF content only; it does not clone secure access, payment, hotel, identity, or transit credentials.

Normal NDEF writes are read back and compared before success is reported. Blank NDEF-formatable tags are formatted when Android supports them.

## Sharing by phone

On devices with Android Host Card Emulation, eligible non-secure NDEF items show **Share by phone**. This presents the standard NDEF message as an NFC Forum Type 4 Tag while the device is unlocked. It is useful for links, contact cards, notes, and similar content.

This feature does not emulate bank cards, MIFARE Classic credentials, DESFire applications, hotel keys, transit passes, or other protected credentials.

## Accessibility and compatibility

Open **Guide** for the compatibility matrix and the following display controls:

- Larger text
- High contrast
- Reduced motion

The interface also provides labeled controls, visible keyboard focus, screen-reader status announcements, large touch targets, and information that does not rely on color alone.

Physical-device testing is still essential. Antenna position, NFC chipset support, Android version, issuer security, and tag condition all affect real-world behavior.
