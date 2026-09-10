# Foxwel Printer Plugin (`unified_driver`)

Package: `com.foxwelai.printfox`  
Deep link: `printfox://print?…&format=a5|label`

## Features
- Dual Bluetooth printers at once: **80mm receipt** + **label**
- `format=a5` → 80mm thermal receipt (ESC/POS + auto-cut)
- `format=label` → label Bluetooth printer
- USB / Wi‑Fi (GDI) fallbacks still available in Settings
- Website deep-link contract: see [`docs/WEBSITE_PRINT_DEEP_LINK_PROMPT.md`](docs/WEBSITE_PRINT_DEEP_LINK_PROMPT.md)

## Build
```bash
flutter build apk --release
```
