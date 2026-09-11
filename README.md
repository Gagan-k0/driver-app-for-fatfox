# 🦊 FatFox Driver App (`com.foxwelai.printfox`)

An Android background driver and printing engine built with **Flutter** and **Kotlin Native Services**. It provides **100% silent, background thermal receipt & label printing** for the **FatFox Admin Panel POS website** running in Android mobile Chrome or WebView environments.

- **GitHub Repository**: [https://github.com/Gagan-k0/driver-app-for-fatfox.git](https://github.com/Gagan-k0/driver-app-for-fatfox.git)
- **Package ID**: `com.foxwelai.printfox`
- **Embedded Local HTTP Port**: `http://127.0.0.1:9123`

---

## 📐 Architecture & Operational Flow

```
[ FatFox Admin Panel (Mobile Chrome) ]
                  │
                  │ POST http://127.0.0.1:9123/print
                  │ (Payload: JSON rendered HTML, keepalive: true)
                  ▼
┌─────────────────────────────────────────────────────────┐
│               FatFox Driver App (Android)               │
│                                                         │
│  1. LocalHttpServer (Port 9123 socket listener)         │
│  2. Headless WebView (Off-screen HTML renderer)         │
│  3. Margin Crop (Auto-removes white space to save paper)│
│  4. EscPosEncoder (Monochrome 203 DPI rasterizer)       │
│  5. BluetoothPrinterHub (Dual SPP RFCOMM sockets)       │
└──────────────────────────┬──────────────────────────────┘
                           │
                           │ ESC/POS Binary Stream (GS v 0)
                           ▼
          🖨️ [ 80mm Bluetooth Thermal Printer ]
```

---

## ⚡ Technical Working Flow (Step-by-Step)

### **1. Web App Request Dispatch**
- When staff clicks **KOT**, **KOT & Print**, or **KOT + Bill** on the POS website, Angular (`SilentPrintService`) generates the formatted 80mm HTML receipt.
- It dispatches a background HTTP POST request directly to the local phone port:
  ```http
  POST http://127.0.0.1:9123/print HTTP/1.1
  Content-Type: application/json
  
  {
    "type": "html",
    "format": "kot",
    "name": "Kitchen Order Ticket",
    "data": "<div class=\"main_print_container\">...</div>"
  }
  ```
- **`keepalive: true`**: Configured on the fetch call so Chrome **never cancels** the request during route transitions (e.g. KOT ➔ Bill ➔ Order screen).

### **2. Embedded Local HTTP Server (`LocalHttpServer.kt` & `PrintFoxServerService.kt`)**
- Bound exclusively to loopback `127.0.0.1:9123` with `reuseAddress = true`.
- Starts as a persistent Android **Foreground Service** (`PrintFoxServerService`) with an ongoing notification (`FatFox Print Server Active`), preventing MIUI / HyperOS / Android battery managers from killing the background server.
- Implements Private Network Access (PNA) CORS headers:
  ```http
  Access-Control-Allow-Origin: *
  Access-Control-Allow-Methods: POST, GET, OPTIONS
  Access-Control-Allow-Headers: Content-Type, Authorization, Access-Control-Request-Private-Network
  Access-Control-Allow-Private-Network: true
  ```
- Returns an instant `200 OK` JSON response to Chrome without blocking web navigation.

### **3. Off-Screen Headless WebView Rendering**
- Instantiates `android.webkit.WebView` using `ContextThemeWrapper(applicationContext, android.R.style.Theme_DeviceDefault)`.
- Sets layout to 576 dots width (80mm @ 203 DPI) and draws HTML content to an in-memory `Bitmap(ARGB_8888)`.
- **Zero Activity Launches**: Solves mobile screen blanking, flashing, and phone screen locking.

### **4. Paper Margin Auto-Cropping (`cropAndScaleReceiptBitmap`)**
- Scans bitmap pixel luminance row-by-row.
- Automatically crops top and bottom blank white space before encoding, drastically reducing thermal paper roll consumption.

### **5. ESC/POS Rasterization (`EscPosEncoder.kt`)**
- Encodes the cropped monochrome bitmap into standard ESC/POS raster graphics commands (`GS v 0`).
- Adds automatic paper feed and cutter commands (`GS V 66 0`).

### **6. Dual Bluetooth SPP Socket Management (`BluetoothPrinterHub.kt`)**
- Maintains active RFCOMM SPP connections (`00001101-0000-1000-8000-00805F9B34FB`) for receipt and label printers.
- Shares SPP sockets if the same printer MAC address is assigned to both roles.
- Transmits data in 1024-byte chunks with automatic 100ms write retry loops.

---

## 🛠️ Key Technical Solutions & Solved Issues

| Issue | Solution Implemented |
| :--- | :--- |
| **"Continue to FatFox Driver?" Chrome Popup** | Bypassed custom deep link URIs (`printfox://`) by sending background HTTP requests to local port 9123. |
| **Chrome Aborting Request on Navigation** | Added `keepalive: true` to the `fetch()` call in `silent-print.service.ts`. |
| **PNA / Mixed Content Cross-Origin Block** | Added `Access-Control-Allow-Private-Network: true` CORS header in `LocalHttpServer.kt`. |
| **Screen Blanking / Phone Screen Lock** | Rendered HTML off-screen in background `WebView` using `applicationContext` (no transparent Activity). |
| **Printing Unauthenticated Login Pages** | Passed raw rendered HTML strings in POST body rather than web hash URLs. |
| **Paper Wasted on Long White Margins** | Added luminance row scanning to crop empty white top/bottom margins. |

---

## 📁 Source Code Sitemap for Developers & AI Coding Agents

- **`android/app/src/main/kotlin/com/foxwelai/driverforcanon/`**
  - **`server/LocalHttpServer.kt`**: Embedded HTTP server on port 9123, headless WebView renderer, and bitmap cropper.
  - **`bt/BluetoothPrinterHub.kt`**: Dual Bluetooth SPP socket connection manager and chunked write writer.
  - **`print/EscPosEncoder.kt`**: High-performance monochrome ESC/POS thermal rasterizer (203 DPI).
  - **`MainActivity.kt`**: Main Flutter Activity, Bluetooth permission handler, and server lifecycle starter.
  - **`TransparentPrintActivity.kt`**: Legacy custom URI scheme deep link handler (`printfox://`).
- **`lib/`**: Flutter UI screens for printer configuration, Bluetooth discovery, and manual test printing.

---

## 🚀 Build & Deployment Commands

### **Build Release APK**
```bash
flutter build apk --release
```

### **Install & Launch via ADB**
```bash
adb install -r build/app/outputs/flutter-apk/app-release.apk
adb shell am start -n com.foxwelai.printfox/com.foxwelai.driverforcanon.MainActivity
```

### **Test Local HTTP Print Server (via ADB Port Forward)**
```bash
adb forward tcp:9123 tcp:9123
Invoke-RestMethod -Uri "http://127.0.0.1:9123/print" -Method Post -ContentType "application/json" -Body '{"type":"html","format":"kot","name":"Test KOT","data":"<html><body style=\"text-align:center;\"><h2>*** TEST KOT ***</h2><p>Apple Juice x 1</p></body></html>"}'
```

