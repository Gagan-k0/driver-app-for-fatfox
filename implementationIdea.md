# FatFox Driver: Web Developer Integration Specification & Implementation Guide

This document provides the complete technical specification, security rules, and code examples for web application developers integrating **FatFox Driver / PrintFox** for direct, silent 80mm thermal receipt printing on Android.

---

## 🚀 Key Problems Solved & Features

1. **0 Browser Print Dialogs:** Replaces `window.print()` with a direct local HTTP API so Chrome's print popup never appears.
2. **0 In-App Preview Screens:** Eliminates app preview screens so users never have to tap "PRINT" manually inside the app.
3. **Direct 80mm Roll Support:** Direct ESC/POS thermal rendering for KOTs and Customer Bills with auto-cut support.
4. **Chrome PNA & CORS Compliant:** Handles Private Network Access (`Access-Control-Allow-Private-Network: true`) and preflight `OPTIONS` requests seamlessly on HTTPS websites.
5. **Gesture Blocking Prevention:** Uses background status tracking to prevent browsers from blocking fallback print windows.

---

## 🛠️ Architectural Workflow

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

## Method 1: Local Background HTTP API (`http://127.0.0.1:9123`) [RECOMMENDED]

The driver runs a background loopback HTTP server on `http://127.0.0.1:9123` (fallback ports: `9124`, `8989`).

### Request Endpoint
- **URL**: `http://127.0.0.1:9123`
- **Method**: `POST`
- **Content-Type**: `application/json`

### Payload Parameters

| Parameter | Type | Required | Options | Description |
| :--- | :--- | :--- | :--- | :--- |
| `format` | `string` | **Yes** | `"kot"` \| `"bill"` \| `"label"` | `"kot"` targets Kitchen Receipt printer role; `"bill"` targets Customer Bill printer role. |
| `name` | `string` | No | Text string | Job label for print logs (e.g. `"Order #1042"`). |
| `data` | `string` | **Yes** | HTML Markup | Full HTML string of the receipt layout. |
| `paperWidth` | `string` | No | `"80mm"` \| `"58mm"` | Paper roll width. Default is `"80mm"`. |
| `kotAutoCut` | `boolean` | No | `true` \| `false` | Execute auto paper-cut after printing. Default is `true`. |

---

## Method 2: Android Silent Intent Scheme (`intent://` / `printfox://`)

For mobile browsers where local HTTP fetch might be constrained, trigger the silent Android Intent:

```javascript
const intentUri = `intent://print?type=html&format=${format}&name=${encodeURIComponent(name)}&data=${encodeURIComponent(htmlData)}#Intent;scheme=printfox;package=com.foxwelai.driverforcanon;end;`;
window.location.href = intentUri;
```

---

## 🔒 Security & CORS Headers (Chrome PNA Standard)

When a secure HTTPS website (`https://yourdomain.com`) calls `http://127.0.0.1:9123`, Chrome sends an `OPTIONS` preflight request asking for Private Network Access.

The FatFox Driver HTTP server responds with:
```http
HTTP/1.1 204 No Content
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: POST, GET, OPTIONS
Access-Control-Allow-Headers: Content-Type, Authorization, Access-Control-Request-Private-Network
Access-Control-Allow-Private-Network: true
Access-Control-Max-Age: 86400
```

---

## 📦 Web Client Helper Library (`printfox-web-client.js`)

Add `printfox-web-client.js` to your web codebase:

```javascript
import { initPrintFoxHeartbeat, printThermal80mm } from './printfox-web-client';

// Initialize background heartbeat on web app load (avoids gesture blocking)
initPrintFoxHeartbeat(5000);

// Call when user clicks Print
async function handlePrint(order) {
  const htmlContent = `
    <html>
      <body style="width:280px; font-family:monospace; margin:0; padding:4px;">
        <h2 style="text-align:center;">FATFOX KITCHEN</h2>
        <p style="text-align:center;">Order #${order.id} | Table ${order.table}</p>
        <hr style="border-top:1px dashed #000;"/>
        <ul>
          ${order.items.map(item => `<li>${item.qty}x ${item.name}</li>`).join('')}
        </ul>
      </body>
    </html>
  `;

  await printThermal80mm({
    html: htmlContent,
    format: 'kot',
    name: `KOT #${order.id}`,
    paperWidth: '80mm',
    autoCut: true,
  });
}
```

---

## 🔄 Sequential Printing (KOT + Customer Bill)

```javascript
async function handleCheckoutAndPrint(order) {
  const kotHtml = generateKotHtml(order);
  const billHtml = generateBillHtml(order);

  // 1. Print Kitchen Order Ticket (KOT)
  await printThermal80mm({ html: kotHtml, format: 'kot', name: `KOT #${order.id}` });

  // 300ms pause between jobs
  await new Promise((resolve) => setTimeout(resolve, 300));

  // 2. Print Customer Bill
  await printThermal80mm({ html: billHtml, format: 'bill', name: `Bill #${order.id}` });
}
```

---

## 🎨 Layout Design Best Practices

1. **Receipt Dimensions**:
   - Design thermal receipt CSS with `width: 280px;` (for 80mm roll, printable width ~72mm @ 203 DPI).
2. **High Contrast**:
   - Use black text on crisp white background (`color: #000; background: #fff;`).
3. **Avoid Default Margins**:
   - Reset body margins (`margin: 0; padding: 4px;`).
