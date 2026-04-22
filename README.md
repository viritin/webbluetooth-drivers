# webbluetooth-drivers

Java APIs for various BLE devices implemented using Vaadin + Web Bluetooth API. Each device driver is a Vaadin component that handles the Bluetooth connection and device-specific protocol, allowing Java developers to interact with BLE hardware directly from server-side code.

## Modules

### ble-label-common

Shared base module for BLE label printers. Contains:

- **`LabelImage`** — base class for label rendering with QR code generation (ZXing), text drawing, trimming, and PNG export. Extends Vaadin's `Image` component for in-browser preview.
- **`LabelPrinter`** — common interface implemented by all printer drivers: `requestConnection()`, `print(BufferedImage)`, `disconnect()`, `isConnected()`, `addConnectionListener()`.
- **`UnifiedLabelPrinter`** — a single component that supports multiple printer types through one Bluetooth pairing dialog. Auto-detects the connected printer (Phomemo or Dymo) and uses the correct protocol. Reports the detected `PrinterType` so the app can adjust label format accordingly.

### phomemo-driver

Driver for Phomemo thermal label printers. Tested on Phomemo M110 (Clas Ohlson branded version). Converts images to the M110 raster protocol and sends via BLE in 128-byte chunks. Includes `LabelImage` subclass with 203 DPI defaults (40mm x 30mm).

### dymo-letratag-200b

Driver for the Dymo LetraTag 200B Bluetooth label printer. Implements the LetraTag protocol with 9-byte header, checksum, 500-byte chunked transfer, and chunk index 27 skip (vendor quirk). Includes `LetraTagLabelImage` with fixed 32-pixel height and 2x horizontal stretching to match the device's print characteristics.

### wozinsky-wik-10

Driver for the Wozinsky WIK-10 BLE kitchen scale (Chipsea CST34XX chipset). Parses weight notifications from the scale's GATT service and reports weight in grams with stable/measuring status. Includes a built-in UI component with weight display and connect/disconnect button.

### label-printer-demo

Demo application combining the printer drivers and scale into a product labeling workflow. Features:

- Hierarchical product selector (category → species → cut) optimized for tablet use
- Unified printer connection supporting both Phomemo and Dymo printers
- Wozinsky scale integration with auto-print on stable weight
- Label preview with product name, weight, and date

Run with `mvn spring-boot:test-run` from the `label-printer-demo` directory.

## Requirements

- Java 21+
- Chrome or Edge with Web Bluetooth support
- HTTPS (required by Web Bluetooth API; dev server provides this)
- For auto-reconnect: enable `chrome://flags/#enable-web-bluetooth-new-permissions-backend`
