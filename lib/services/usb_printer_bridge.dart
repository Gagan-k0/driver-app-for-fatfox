import 'dart:async';

import 'package:flutter/services.dart';

import '../models/printer_models.dart';

class UsbPrinterBridge {
  UsbPrinterBridge._();
  static final UsbPrinterBridge instance = UsbPrinterBridge._();

  static const _method = MethodChannel('com.foxwelai.printfox/usb');
  static const _events = EventChannel('com.foxwelai.printfox/events');

  Stream<Map<dynamic, dynamic>>? _eventStream;

  Stream<Map<dynamic, dynamic>> get events {
    _eventStream ??= _events
        .receiveBroadcastStream()
        .map((e) => Map<dynamic, dynamic>.from(e as Map));
    return _eventStream!;
  }

  Future<PrinterStatus> getStatus() async {
    final raw = await _method.invokeMethod<Map>('getStatus');
    return PrinterStatus.fromMap(raw ?? {});
  }

  Future<Map<dynamic, dynamic>> connect() async {
    final raw = await _method.invokeMethod<Map>('connect');
    return raw ?? {};
  }

  Future<void> disconnect() => _method.invokeMethod('disconnect');

  Future<List<PrinterDevice>> listDevices() async {
    final raw = await _method.invokeMethod<List>('listDevices');
    return (raw ?? const [])
        .whereType<Map>()
        .map(PrinterDevice.fromMap)
        .toList();
  }

  Future<Map<dynamic, dynamic>> connectWifi(WifiPrinterConfig config) async {
    final raw = await _method.invokeMethod<Map>('connectWifi', config.toMap());
    return raw ?? {};
  }

  Future<Map<dynamic, dynamic>> testWifi(WifiPrinterConfig config) async {
    final raw = await _method.invokeMethod<Map>('testWifi', config.toMap());
    return raw ?? {};
  }

  Future<Map<dynamic, dynamic>> printTestLabel(
    LabelSettings settings, {
    String mode = 'ufr',
  }) async {
    final raw = await _method.invokeMethod<Map>('printTestLabel', {
      ...settings.toMap(),
      'mode': mode,
    });
    return raw ?? {};
  }

  Future<Map<dynamic, dynamic>> diagnose() async {
    final raw = await _method.invokeMethod<Map>('diagnose');
    return raw ?? {};
  }

  Future<Map<dynamic, dynamic>> printText(
    String text,
    LabelSettings settings,
  ) async {
    final raw = await _method.invokeMethod<Map>('printText', {
      'text': text,
      ...settings.toMap(),
    });
    return raw ?? {};
  }

  Future<Map<dynamic, dynamic>> printImageFile(
    String path,
    LabelSettings settings,
  ) async {
    final raw = await _method.invokeMethod<Map>('printImageFile', {
      'path': path,
      ...settings.toMap(),
    });
    return raw ?? {};
  }

  Future<Map<dynamic, dynamic>> printPdfFile(
    String path,
    LabelSettings settings, {
    int pageIndex = 0,
  }) async {
    final raw = await _method.invokeMethod<Map>('printPdfFile', {
      'path': path,
      'pageIndex': pageIndex,
      ...settings.toMap(),
    });
    return raw ?? {};
  }

  /// Print a precomposed sheet PNG (label grid) via USB ZPL or Wi‑Fi GDI PDF.
  Future<Map<dynamic, dynamic>> printSheetPng(
    String path,
    LabelSettings settings,
  ) async {
    final raw = await _method.invokeMethod<Map>('printSheetPng', {
      'path': path,
      ...settings.toMap(),
    });
    return raw ?? {};
  }

  Future<Map<dynamic, dynamic>> writeText(String text) async {
    final raw = await _method.invokeMethod<Map>('writeText', {'text': text});
    return raw ?? {};
  }

  Future<String?> renderPdfPreview(
    String path,
    LabelSettings settings, {
    int pageIndex = 0,
  }) async {
    final raw = await _method.invokeMethod<Map>('renderPdfPreview', {
      'path': path,
      // Both "bill" and "label" are printed on 80mm thermal now.
      'widthDots': settings.widthDots,
      'heightDots': settings.heightDots,
      'pageIndex': pageIndex,
    });
    if (raw == null || raw['ok'] != true) return null;
    return raw['path']?.toString();
  }

  Future<LabelSettings> getLabelSettings() async {
    final raw = await _method.invokeMethod<Map>('getLabelSettings');
    return LabelSettings.fromMap(raw ?? {});
  }

  Future<void> saveLabelSettings(LabelSettings settings) async {
    await _method.invokeMethod('saveLabelSettings', settings.toMap());
  }

  Future<List<BluetoothPrinterDevice>> listBluetoothPrinters() async {
    final raw = await _method.invokeMethod<List>('listBluetoothPrinters');
    return (raw ?? const [])
        .whereType<Map>()
        .map(BluetoothPrinterDevice.fromMap)
        .toList();
  }

  Future<BluetoothHubStatus> getBluetoothStatus() async {
    final raw = await _method.invokeMethod<Map>('getBluetoothStatus');
    return BluetoothHubStatus.fromMap(raw);
  }

  Future<Map<dynamic, dynamic>> connectBluetooth({
    required String role,
    required String address,
  }) async {
    final raw = await _method.invokeMethod<Map>('connectBluetooth', {
      'role': role,
      'address': address,
    });
    return raw ?? {};
  }

  Future<Map<dynamic, dynamic>> disconnectBluetooth({String? role}) async {
    final raw = await _method.invokeMethod<Map>(
      'disconnectBluetooth',
      role == null ? null : {'role': role},
    );
    return raw ?? {};
  }

  Future<Map<dynamic, dynamic>> connectAllBluetooth() async {
    final raw = await _method.invokeMethod<Map>('connectAllBluetooth');
    return raw ?? {};
  }

  Future<IncomingPrintJob?> getPendingPrintJob() async {
    final raw = await _method.invokeMethod<Map>('getPendingPrintJob');
    if (raw == null) return null;
    final format = PrintFormat.parse(raw['format']?.toString());
    if (raw['ready'] == false || raw['loading'] == true) {
      return IncomingPrintJob(
        path: '',
        name: raw['name']?.toString() ?? 'Website print',
        loading: true,
        format: format,
      );
    }
    final path = raw['path']?.toString() ?? '';
    if (path.isEmpty) return null;
    return IncomingPrintJob(
      path: path,
      name: raw['name']?.toString() ?? 'Print job',
      format: format,
    );
  }

  Future<void> clearPendingPrintJob() =>
      _method.invokeMethod('clearPendingPrintJob');

  Future<IncomingPrintJob?> waitForPendingPrintJob({
    Duration timeout = const Duration(seconds: 25),
  }) async {
    final end = DateTime.now().add(timeout);
    while (DateTime.now().isBefore(end)) {
      final job = await getPendingPrintJob();
      if (job != null && !job.loading && job.path.isNotEmpty) {
        return job;
      }
      await Future<void>.delayed(const Duration(milliseconds: 400));
    }
    return getPendingPrintJob();
  }

  Future<bool> isPrintServiceEnabled() async {
    final raw = await _method.invokeMethod<bool>('isPrintServiceEnabled');
    return raw == true;
  }

  Future<Map<dynamic, dynamic>> getPrintServiceStatus() async {
    final raw = await _method.invokeMethod<Map>('getPrintServiceStatus');
    return raw ?? {};
  }

  Future<void> openSystemPrintSettings() =>
      _method.invokeMethod('openPrintSettings');

  Future<void> returnToCaller() async {
    try {
      await _method.invokeMethod('returnToCaller');
    } catch (_) {}
  }
}
