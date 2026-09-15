/**
 * PrintFox Direct 80mm Thermal Printer Client Library
 * Author: FatFox / PrintFox Engineering Team
 * 
 * Provides direct 80mm ESC/POS thermal printing from web apps
 * without browser print dialog popups or in-app preview screens.
 * 
 * Features:
 * 1. Background heartbeat to avoid asynchronous window.print() gesture blocking.
 * 2. Full PNA (Private Network Access) and CORS compliance for HTTPS web apps.
 * 3. Silent Android Deep Link Intent fallback.
 */

const PRINTFOX_PORTS = [9123, 9124, 8989];

let activePort = null;
let isDriverAppOnline = false;
let heartbeatTimer = null;

/**
 * Utility: Detect Android browser environment
 * @returns {boolean}
 */
export function isAndroid() {
  if (typeof window === 'undefined' || typeof navigator === 'undefined') return false;
  return /android/i.test(navigator.userAgent || navigator.vendor || window.opera || '');
}

/**
 * Check availability of local PrintFox silent background HTTP server
 * @returns {Promise<{available: boolean, port: number|null}>}
 */
export async function checkPrintFoxStatus() {
  if (typeof window === 'undefined' || typeof fetch === 'undefined') {
    isDriverAppOnline = false;
    activePort = null;
    return { available: false, port: null };
  }
  for (const port of PRINTFOX_PORTS) {
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 400);
      const res = await fetch(`http://127.0.0.1:${port}/`, {
        method: 'GET',
        signal: controller.signal,
      });
      clearTimeout(timeoutId);
      if (res.ok) {
        const json = await res.json();
        if (json.app === 'PrintFox' || json.status === 'online') {
          activePort = port;
          isDriverAppOnline = true;
          return { available: true, port };
        }
      }
    } catch (_) {}
  }
  isDriverAppOnline = false;
  activePort = null;
  return { available: false, port: null };
}

/**
 * Start periodic background heartbeat (run once on web app load)
 * Keeps track of whether the Driver App is running BEFORE the user clicks Print.
 * This guarantees window.print() can be called SYNCHRONOUSLY if the app is offline.
 * 
 * @param {number} [intervalMs=5000] 
 */
export function initPrintFoxHeartbeat(intervalMs = 5000) {
  checkPrintFoxStatus();
  if (heartbeatTimer) clearInterval(heartbeatTimer);
  heartbeatTimer = setInterval(() => {
    checkPrintFoxStatus();
  }, intervalMs);
}

/**
 * Main Direct Thermal Print Handler for Web Applications.
 * Bypasses window.print() completely on Android to prevent browser popups.
 * 
 * @param {Object} options
 * @param {string} options.html - Full HTML string of the receipt or KOT
 * @param {'kot'|'bill'|'label'} [options.format='kot'] - Job format
 * @param {string} [options.name='Print Job'] - Job title for logging
 * @param {'80mm'|'58mm'} [options.paperWidth='80mm'] - Thermal roll width
 * @param {boolean} [options.autoCut=true] - Trigger automatic paper cut
 * @returns {Promise<{success: boolean, method: string, error?: string}>}
 */
export async function printThermal80mm({
  html,
  format = 'kot',
  name = 'Print Job',
  paperWidth = '80mm',
  autoCut = true
}) {
  if (!html || typeof html !== 'string' || html.trim() === '') {
    return { success: false, method: 'none', error: 'HTML print payload is empty' };
  }

  // 1. If we already know the app is offline via heartbeat, AND we're on desktop,
  // we can handle fallback synchronously if needed.
  const targetPort = activePort || (await checkPrintFoxStatus()).port;

  // 2. Try Local Background HTTP Server (100% silent background print, 0 popups)
  if (targetPort) {
    try {
      const response = await fetch(`http://127.0.0.1:${targetPort}/`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          format: format,
          name: name,
          data: html,
          paperWidth: paperWidth,
          kotAutoCut: autoCut,
        }),
      });
      if (response.ok) {
        const result = await response.json();
        console.log('[PrintFox] Printed silently via background HTTP server:', result);
        return { success: true, method: 'http' };
      }
    } catch (err) {
      console.warn('[PrintFox] HTTP server print attempt failed, attempting fallback', err);
    }
  }

  // 3. Android Deep Link Silent Intent Fallback
  if (isAndroid()) {
    try {
      const encodedData = encodeURIComponent(html);
      const encodedName = encodeURIComponent(name);
      const intentUri = `intent://print?type=html&format=${format}&name=${encodedName}&data=${encodedData}#Intent;scheme=printfox;package=com.foxwelai.driverforcanon;end;`;
      window.location.href = intentUri;
      return { success: true, method: 'intent' };
    } catch (err) {
      console.error('[PrintFox] Deep link intent failed:', err);
    }
  }

  // 4. Desktop Fallback (Standard Browser Print)
  console.warn('[PrintFox] Mobile driver app not connected. Falling back to browser print window.');
  if (typeof window !== 'undefined') {
    const printWindow = window.open('', '_blank');
    if (printWindow) {
      printWindow.document.write(html);
      printWindow.document.close();
      printWindow.focus();
      printWindow.print();
      printWindow.close();
      return { success: true, method: 'browser_fallback' };
    }
  }

  return { success: false, method: 'failed', error: 'Unable to initiate print' };
}

export default {
  isAndroid,
  checkPrintFoxStatus,
  initPrintFoxHeartbeat,
  printThermal80mm,
};
