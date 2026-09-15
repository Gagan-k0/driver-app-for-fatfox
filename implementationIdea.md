# FatFox Driver: Web Developer Integration Specification & Implementation Prompt

This document provides the complete technical specification, code examples, and integration rules for web application developers integrating **FatFox Driver** for 80mm thermal receipt printing on Android.

---

## 🚀 Overview & Integration Options

FatFox Driver runs as a background service on Android. Your web application can trigger direct, silent thermal receipt prints using either:

1. **Local Background HTTP API (`http://127.0.0.1:9123`) [RECOMMENDED]**
   - **User Experience**: 100% silent background printing.
   - The user stays on the web page / POS dashboard without tab reloads or popup windows.
   - Supports consecutive bulk prints (KOT + Customer Bill).

2. **Deep Link URI Scheme (`fatfox://print?...`)**
   - **User Experience**: Opens FatFox Driver transparently, executes background print, and finishes.
   - Ideal for mobile web browsers where local HTTP fetch might be constrained by mixed-content security.

---

## Method 1: Local Background HTTP API (`http://127.0.0.1:9123`)

The driver runs a loopback HTTP server on `http://127.0.0.1:9123`. Send a `POST` request with JSON content.

### Request Endpoint
- **URL**: `http://127.0.0.1:9123`
- **Method**: `POST`
- **Content-Type**: `application/json`

### Payload Parameters

| Parameter | Type | Required | Values | Description |
| :--- | :--- | :--- | :--- | :--- |
| `format` | `string` | **Yes** | `"kot"` \| `"bill"` | `"kot"` targets Kitchen Receipt printer role; `"bill"` targets Customer Bill printer role. |
| `name` | `string` | No | Text string | Job label for print logs (e.g. `"Order #1042"`). |
| `data` | `string` | **Yes** | HTML Markup | HTML string of the receipt or bill layout to print. |
| `paperWidth` | `string` | No | `"80mm"` \| `"58mm"` | Paper roll width. Default is `"80mm"`. |
| `kotAutoCut` | `boolean` | No | `true` \| `false` | Execute auto paper-cut after printing. Default is `true`. |

### Helper Function (JavaScript / TypeScript)

```javascript
/**
 * Silent Print via FatFox Driver Local HTTP Server
 * @param {'kot' | 'bill'} format - 'kot' for Kitchen Order Ticket, 'bill' for Customer Bill
 * @param {string} orderName - E.g. "Order #1042"
 * @param {string} htmlContent - HTML string of the receipt layout
 */
async function printSilent(format, orderName, htmlContent) {
  try {
    const response = await fetch('http://127.0.0.1:9123', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        format: format, // 'kot' or 'bill'
        name: orderName,
        data: htmlContent,
        paperWidth: '80mm',
        kotAutoCut: true,
      }),
    });

    const res = await response.json();
    if (res.success) {
      console.log(`Print job sent successfully for ${orderName}`);
      return true;
    } else {
      console.warn('Printer server returned warning:', res);
      return false;
    }
  } catch (err) {
    console.error('FatFox Driver local server unreachable:', err);
    // Fallback to Deep Link URI scheme if HTTP server is unreachable
    triggerFatFoxDeepLink(format, orderName, htmlContent);
  }
}
```

---

## Method 2: Deep Link URI Scheme (`fatfox://print`)

Invoke the `fatfox://` scheme directly from JavaScript or HTML links.

### Deep Link Format
```text
fatfox://print?type=<TYPE>&format=<FORMAT>&name=<NAME>&url=<ENCODED_HTTPS_URL>
```

### Parameters
- **`type`**: `"pdf"` | `"html"` | `"text"` | `"zpl"`
- **`format`**: `"kot"` (Kitchen Order Ticket) or `"bill"` (Customer Bill)
- **`name`**: Title of the job (e.g. `Order_1042`)
- **`url`**: URL-encoded HTTPS link to PDF or HTML receipt document (`encodeURIComponent(url)`)

### JavaScript Examples

#### 1. Kitchen Order Ticket (KOT) PDF Print
```javascript
const pdfUrl = "https://staging.fatfox.testfox.in/api/orders/1042/kot-pdf";
const uri = `fatfox://print?type=pdf&format=kot&name=KOT_1042&url=${encodeURIComponent(pdfUrl)}`;

// Trigger deep link
window.location.href = uri;
```

#### 2. Customer Bill PDF Print
```javascript
const billUrl = "https://staging.fatfox.testfox.in/api/orders/1042/bill-pdf";
const uri = `fatfox://print?type=pdf&format=bill&name=Bill_1042&url=${encodeURIComponent(billUrl)}`;

window.location.href = uri;
```

---

## 🔄 Sequential Printing (KOT + Customer Bill)

To print both a KOT and a Customer Bill when an order is completed:

```javascript
async function handleCheckoutAndPrint(order) {
  const kotHtml = generateKotHtml(order);
  const billHtml = generateBillHtml(order);

  // 1. Print Kitchen Order Ticket (KOT)
  console.log('Printing KOT...');
  await printSilent('kot', `KOT #${order.id}`, kotHtml);

  // Short pause (300ms) between print jobs
  await new Promise((resolve) => setTimeout(resolve, 300));

  // 2. Print Customer Bill
  console.log('Printing Customer Bill...');
  await printSilent('bill', `Bill #${order.id}`, billHtml);
}
```

---

## 🎨 Layout Design Best Practices

1. **Receipt Dimensions**:
   - Design thermal receipt CSS with `width: 384px;` (58mm) or `width: 576px;` (80mm).
2. **High Contrast**:
   - Use high-contrast black text on white background (`color: #000; background: #fff;`).
3. **Avoid Margins**:
   - Set body margins to zero (`margin: 0; padding: 0;`).
