# Website team prompt — migrate Android print to PrintFox (`printfox://`)

Copy everything below the line into a ticket / Slack / Cursor prompt for the website team.

---

## Prompt for website engineers

### Goal
On **Android**, replace the old **two-app** print flows with the **single** Foxwel Printer Plugin app.

The tablet now uses **two Bluetooth printers at once** (assigned inside the app):

| Website `format` | Opens same app | Physical printer | What to generate |
|------------------|----------------|------------------|------------------|
| `a5` | Foxwel Printer Plugin | **80mm thermal receipt** (Bluetooth) | PDF sized / laid out for **80mm receipt** (not A5 paper) |
| `label` | Foxwel Printer Plugin | **Label** Bluetooth printer | Label PDF as today |

| Old (remove on Android) | New (use instead) |
|-------------------------|-------------------|
| `canfox://…` (A5 / Canon bill) | `printfox://…&format=a5` → **80mm receipt** |
| `lp46://…` (label / LP46) | `printfox://…&format=label` → **label printer** |

- Package to install: **`com.foxwelai.printfox`**
- App display name: **Foxwel Printer Plugin**
- Deep link scheme: **`printfox`**
- **Do not** open Play Store / Intent chooser with `package=` fallback that redirects to store.
- **Do not** use the system Print dialog for this flow on Android.
- Non-Android (iOS / desktop): keep existing behavior (`window.print()` or current fallback).

---

### What changed for website (important)

1. **Deep link scheme change only** for routing — still `format=a5` and `format=label` (wire values unchanged so existing `printfox` migrations keep working).
2. **`format=a5` no longer means A5 paper.** It means: send this job to the **80mm thermal receipt** Bluetooth printer. Generate / render the PDF for **~80mm width** (thermal receipt), not A5 landscape Canon paper.
3. **`format=label`** still means label — app routes to the **second** Bluetooth printer. Website keeps sending the label PDF (+ `format=label`).
4. Website does **not** choose MAC addresses, connect Bluetooth, or send cut commands. The driver app:
   - keeps **two** BT connections (receipt + label),
   - routes by `format`,
   - applies **auto-cut** when the printer supports it.
5. No mode switch in the app between jobs — website only sets `format` on each deep link.

---

### Critical rules

1. **Only change Android** — gate with `isAndroid`.
2. **Always pass `format`** — `a5` = 80mm receipt printer; `label` = label printer.
3. **`url` must be an absolute `https://` PDF URL** (encode with `encodeURIComponent`).
4. Prefer **custom scheme only** — not Android Intent URLs that bounce to Play Store.
5. **Bill/receipt PDF content**: design for **80mm thermal** (narrow receipt), not A5 / A4 page. If you still have an A5 bill PDF generator, replace or add an **80mm receipt** PDF generator for Android `format=a5`.

#### ❌ Do NOT use (Android)
```js
`canfox://print?type=pdf&url=...`
`lp46://print?type=pdf&url=...`
`intent://print?...#Intent;scheme=printfox;package=com.foxwelai.printfox;end`
`intent://print?...#Intent;scheme=canfox;...`
`intent://print?...#Intent;scheme=lp46;...`
```

#### ✅ Use (Android)
```js
// Receipt → 80mm Bluetooth thermal (format value stays "a5" for compatibility)
`printfox://print?type=pdf&url=${encodeURIComponent(httpsPdfUrl)}&name=${encodeURIComponent(title)}&format=a5`

// Label → Bluetooth label printer
`printfox://print?type=pdf&url=${encodeURIComponent(httpsPdfUrl)}&name=${encodeURIComponent(title)}&format=label`
```

---

### Deep link contract

```
printfox://print?type=pdf&url=<ENCODED_HTTPS_PDF>&name=<ENCODED_TITLE>&format=<a5|label>
```

| Param | Required | Values | Notes |
|-------|----------|--------|--------|
| `type` | optional | `pdf` (default), `image`, `text`, `zpl` | Receipts/labels use `pdf` |
| `url` | **yes** for pdf/image | Absolute `https://…` | Must be reachable by the device |
| `name` | optional | any string | Shown on preview title |
| `format` | **yes** | `a5` or `label` | **Router only.** `a5` → 80mm receipt BT; `label` → label BT. Do **not** omit. |

#### Mapping from old flows

| Old website flow | Old URL | New URL | PDF content change |
|------------------|---------|---------|-------------------|
| Print **bill / A5 / Canon** | `canfox://…` | `printfox://…&format=a5` | **Yes** — generate **80mm receipt** PDF (not A5 paper) |
| Print **shipping / product label** | `lp46://…` | `printfox://…&format=label` | Usually **no** — same label PDF as before |

Aliases accepted by the app for receipt (optional; prefer `a5` for stability): `receipt`, `80mm`, `bill`, `thermal`.  
Aliases for label: `label`, `labels`, `4x6`, `lp46`.

---

### PDF / layout guidance

#### Receipt (`format=a5`)
- Target physical paper: **80mm thermal roll** (~72 mm printable width).
- Prefer a **narrow portrait** PDF (receipt layout: shop header, lines, totals, footer).
- Avoid A5/A4 full-page bill layouts — they scale poorly on 80mm and look tiny/wide.
- App rasters the PDF to ~**576 dots** width @ 203 DPI and can **auto-cut** after print.
- Suggested title param: `Receipt` or `Bill` (display only).

#### Label (`format=label`)
- Keep existing label PDF size / design used with LP46 (e.g. 4×6 or your current label art).
- App sends the page to the **label** Bluetooth printer (ESC/POS + cut when enabled).
- Sheet grid / start-cell UI in the app is mainly for Wi‑Fi/USB sheet workflows; Bluetooth label jobs print the label page directly.

Website does **not** need to:
- pick printer IPs / Bluetooth MACs,
- send ESC/POS or cut commands,
- open two different apps.

---

### Suggested implementation (drop-in)

```js
function isAndroid() {
  return /Android/i.test(navigator.userAgent);
}

/**
 * Open Foxwel Printer Plugin on Android.
 *
 * @param {'a5' | 'label'} format
 *   - 'a5'    → 80mm thermal receipt Bluetooth printer
 *   - 'label' → label Bluetooth printer
 * @param {string} pdfUrl  - absolute https PDF URL (80mm layout for a5; label layout for label)
 * @param {string} [title] - preview title
 */
function openFoxwelPrint({ format, pdfUrl, title }) {
  if (!pdfUrl || !/^https?:\/\//i.test(pdfUrl)) {
    console.error('PrintFox requires an absolute http(s) PDF url');
    return;
  }
  if (format !== 'a5' && format !== 'label') {
    console.error('PrintFox format must be "a5" (80mm receipt) or "label"');
    return;
  }

  const name =
    title || (format === 'label' ? 'Label' : 'Receipt');
  const href =
    `printfox://print?type=pdf` +
    `&url=${encodeURIComponent(pdfUrl)}` +
    `&name=${encodeURIComponent(name)}` +
    `&format=${encodeURIComponent(format)}`;

  window.location.href = href;
}

// --- Receipt / bill (replaces canfox://) — use 80mm PDF ---
async function onPrintReceiptClick(e) {
  e.preventDefault();
  if (!isAndroid()) {
    window.print(); // or existing non-Android path
    return;
  }
  // IMPORTANT: 80mm receipt PDF, not A5 bill PDF
  const pdfUrl = await getReceipt80mmPdfDownloadUrl();
  openFoxwelPrint({ format: 'a5', pdfUrl, title: 'Receipt' });
}

// --- Label (replaces lp46://) ---
async function onPrintLabelClick(e) {
  e.preventDefault();
  if (!isAndroid()) {
    window.print();
    return;
  }
  const pdfUrl = await getLabelPdfDownloadUrl(); // existing label PDF helper
  openFoxwelPrint({ format: 'label', pdfUrl, title: 'Label' });
}
```

---

### Search & replace checklist

In the Android print code paths only:

1. Find all `canfox://` → `printfox://` and **add** `&format=a5`.
2. Find all `lp46://` → `printfox://` and **add** `&format=label`.
3. For bill/receipt Android path: switch PDF generator from **A5 paper** → **80mm thermal receipt** (keep `format=a5` in the URL).
4. Remove Play Store / `intent://…package=com.foxwelai…` redirects for print.
5. Keep `isAndroid()` guard — desktop/iOS unchanged.
6. If app is missing, show: **“Install Foxwel Printer Plugin APK on this device”** — do **not** send users to Play Store.
7. Do **not** add printer MAC / Bluetooth / cut parameters to the URL — app handles those.

---

### Acceptance tests (Android tablet with plugin + both printers paired)

**App setup (one-time on tablet, not website):**  
Settings → Bluetooth → assign **Receipt (80mm)** + **Label** → Connect both. Auto-cut on.

**Website / deep link:**

- [ ] Receipt/Bill Print → opens **Foxwel Printer Plugin** with receipt preview (not label sheet grid).
- [ ] Receipt job prints on the **80mm** Bluetooth printer (not the label printer).
- [ ] Label Print → same app, label preview; prints on the **label** Bluetooth printer.
- [ ] Print receipt, then label, then receipt again — **no** website or app mode switch required.
- [ ] After print, paper **cuts** when the printer has a cutter (app auto-cut enabled).
- [ ] `url` with special characters / query params still works (`encodeURIComponent`).
- [ ] Non-Android still uses old `window.print()` / existing flow.
- [ ] No Play Store open when app is installed.
- [ ] If app not installed, user sees install-APK message (not store).
- [ ] Receipt PDF is readable on 80mm (not a squashed A5 page).

---

### Notes for QA / support

| Item | Value |
|------|--------|
| App package | `com.foxwelai.printfox` |
| Display name | Foxwel Printer Plugin |
| Deep link | `printfox://print?…&format=a5\|label` |
| Receipt printer | Bluetooth #1 — 80mm thermal (`format=a5`) |
| Label printer | Bluetooth #2 — labels (`format=label`) |
| Auto-cut | App setting (default on); website does nothing |
| Old apps | `canfox` / `lp46` **not** required |

- Pair both printers in **Android Bluetooth settings** first, then assign them in the plugin Settings.
- USB / Wi‑Fi remain available as fallbacks inside the app; website deep link contract is unchanged.
- Label sheet rows/cols (for Wi‑Fi laser sheets) are configured **inside the app**; for Bluetooth labels the website only sends the label PDF + `format=label`.
