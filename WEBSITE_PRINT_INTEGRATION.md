# Website Print Integration Guide: Direct 80mm Thermal Printing

> **Strict Modification Guide for Web Engineering Team**  
> **App Identifier:** PrintFox / FatFox Driver App  
> **Supported Paper:** 80mm Thermal Roll (ESC/POS)  
> **Print Latency:** ~300ms (Direct Background Print)

---

## Technical Clarifications & Security Protocol Fixes

### 1. Handling Asynchronous `window.print()` Gesture Blocking
**The Issue:** Web browsers block `window.print()` if invoked inside an asynchronous callback (e.g. after waiting for a failed `fetch()` promise timeout).  
**The Solution:**
1. **Background Heartbeat (`initPrintFoxHeartbeat()`):** The web app initializes a lightweight background status check on page load. If the app is online (`127.0.0.1:9123`), the print button sends the POST request. If the app is offline, the print button triggers `window.print()` **immediately and synchronously** inside the user click event handler tick, completely avoiding browser gesture block.
2. **Android Silent Intent Fallback:** On Android devices, if the local HTTP server is unreachable, the client redirects to `intent://print?...`. Android OS handles deep link intents regardless of async promise delays.

### 2. Private Network Access (PNA) & CORS Header Compliance
**The Issue:** HTTPS websites (`https://your-domain.com`) calling a local device IP (`http://127.0.0.1:9123`) trigger Chrome's **Private Network Access (PNA)** preflight checks.  
**Driver App Implementation Status (VERIFIED & IMPLEMENTED):**
The FatFox Driver App HTTP server on port 9123 explicitly handles `OPTIONS` preflight requests and responds with:
- `HTTP/1.1 204 No Content`
- `Access-Control-Allow-Origin: *`
- `Access-Control-Allow-Methods: POST, GET, OPTIONS`
- `Access-Control-Allow-Headers: Content-Type, Authorization, Access-Control-Request-Private-Network`
- `Access-Control-Allow-Private-Network: true`
- `Access-Control-Max-Age: 86400`

All `POST` and `GET` responses also return `Access-Control-Allow-Private-Network: true` and CORS headers.

---

## Architectural Workflow

```
┌────────────────────────────────┐
│      Website (Chrome/Web)      │
│ User Clicks "Print KOT/Bill"   │
└───────────────┬────────────────┘
                │
                │ POST http://127.0.0.1:9123/ (JSON)
                ▼
┌────────────────────────────────┐
│   PrintFox Local HTTP Server   │ (Runs silently in background)
└───────────────┬────────────────┘
                │
                │ 80mm ESC/POS Rasterization
                ▼
┌────────────────────────────────┐
│   80mm Thermal Printer         │
│   (Bluetooth / USB / Wi-Fi)    │
└────────────────────────────────┘
```

---

## Integration Code for Website (`printfox-web-client.js`)

Add `printfox-web-client.js` to your web app codebase and initialize it on page load:

```javascript
import { initPrintFoxHeartbeat, printThermal80mm } from './printfox-web-client';

// 1. Initialize heartbeat once when website loads
initPrintFoxHeartbeat(5000);

// 2. Click Handler for KOT / Bill Button
async function handlePrintClick(receiptHtml, format = 'kot', jobName = 'Bill #1001') {
  const result = await printThermal80mm({
    html: receiptHtml,
    format: format,
    name: jobName,
    paperWidth: '80mm',
    autoCut: true,
  });
  console.log('Print result:', result);
}
```

---

## 80mm HTML & CSS Template Guidelines

To ensure bills and KOTs fit perfectly on 80mm thermal paper roll (576 dots / printable width ~72mm), apply these CSS rules:

```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    /* 80mm Thermal Paper Reset */
    @page {
      size: 80mm auto;
      margin: 0;
    }
    body {
      width: 280px; /* Optimal printable width for 80mm roll */
      margin: 0 auto;
      padding: 4px;
      font-family: 'Courier New', Courier, monospace, sans-serif;
      font-size: 13px;
      line-height: 1.3;
      color: #000;
      background: #fff;
    }
    h1, h2, h3 {
      text-align: center;
      margin: 4px 0;
      font-weight: bold;
    }
    .text-center { text-align: center; }
    .text-right { text-align: right; }
    .bold { font-weight: bold; }
    .divider {
      border-top: 1px dashed #000;
      margin: 6px 0;
    }
    table {
      width: 100%;
      border-collapse: collapse;
    }
    th, td {
      padding: 2px 0;
      vertical-align: top;
    }
  </style>
</head>
<body>
  <h2>RESTAURANT NAME</h2>
  <div class="text-center">KOT #1024 | Table 5</div>
  <div class="divider"></div>
  <table>
    <thead>
      <tr>
        <th align="left">Item</th>
        <th align="right">Qty</th>
      </tr>
    </thead>
    <tbody>
      <tr>
        <td>Paneer Butter Masala</td>
        <td align="right">2</td>
      </tr>
      <tr>
        <td>Butter Naan</td>
        <td align="right">4</td>
      </tr>
    </tbody>
  </table>
  <div class="divider"></div>
  <div class="text-center">Printed: 15-09-2026 13:20</div>
</body>
</html>
```
