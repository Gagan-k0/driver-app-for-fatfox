import 'package:hive_flutter/hive_flutter.dart';

import '../models/printer_models.dart';

/// Persists assigned + last known connection state for Bluetooth printers.
/// (So on app reopen the UI knows which printer was connected last.)
class PrinterStore {
  static const String _boxName = 'printer_state';

  static const String _kReceiptAddress = 'receiptAddress';
  static const String _kReceiptName = 'receiptName';
  static const String _kReceiptConnected = 'receiptConnected';
  static const String _kReceiptWasConnected = 'receiptWasConnected';
  static const String _kLabelAddress = 'labelAddress';
  static const String _kLabelName = 'labelName';
  static const String _kLabelConnected = 'labelConnected';
  static const String _kLabelWasConnected = 'labelWasConnected';

  static const String _kTransport = 'transport';
  static const String _kAutoCut = 'autoCut';

  static Box<dynamic>? _box;

  static Future<void> init() async {
    await Hive.initFlutter();
    _box = await Hive.openBox<dynamic>(_boxName);
  }

  static bool get _ready => _box != null;

  static String get receiptAddress =>
      (_box?.get(_kReceiptAddress) as String?)?.trim() ?? '';

  static String get receiptName =>
      (_box?.get(_kReceiptName) as String?)?.trim() ?? '';

  static bool get receiptConnected =>
      (_box?.get(_kReceiptConnected) as bool?) ?? false;

  static bool get receiptWasConnected =>
      (_box?.get(_kReceiptWasConnected) as bool?) ?? false;

  static String get labelAddress =>
      (_box?.get(_kLabelAddress) as String?)?.trim() ?? '';

  static String get labelName =>
      (_box?.get(_kLabelName) as String?)?.trim() ?? '';

  static bool get labelConnected =>
      (_box?.get(_kLabelConnected) as bool?) ?? false;

  static bool get labelWasConnected =>
      (_box?.get(_kLabelWasConnected) as bool?) ?? false;

  static PrinterTransport get transport {
    final raw = (_box?.get(_kTransport) as String?);
    return PrinterTransport.parse(raw);
  }

  static bool get autoCut => (_box?.get(_kAutoCut) as bool?) ?? true;

  static Future<void> setAssigned({
    required String receiptAddress,
    required String labelAddress,
  }) async {
    if (!_ready) return;
    await _box!.put(_kReceiptAddress, receiptAddress.trim());
    await _box!.put(_kLabelAddress, labelAddress.trim());
  }

  static Future<void> setNames({
    String? receiptName,
    String? labelName,
  }) async {
    if (!_ready) return;
    if (receiptName != null) {
      await _box!.put(_kReceiptName, receiptName);
    }
    if (labelName != null) {
      await _box!.put(_kLabelName, labelName);
    }
  }

  static Future<void> updateFromStatus(PrinterStatus status) async {
    if (!_ready) return;
    final bt = status.bluetooth;
    await _box!.put(_kReceiptConnected, bt.receipt.connected);

    if (bt.receipt.connected) {
      await _box!.put(_kReceiptWasConnected, true);
    }

    if (bt.receipt.address != null) {
      await _box!.put(_kReceiptAddress, bt.receipt.address);
    }
    if (bt.receipt.name != null) {
      await _box!.put(_kReceiptName, bt.receipt.name);
    }
    await _box!.put(_kTransport, status.transport.wireValue);
  }

  static Future<void> setTransport(PrinterTransport transport) async {
    if (!_ready) return;
    await _box!.put(_kTransport, transport.wireValue);
  }

  static Future<void> setAutoCut(bool value) async {
    if (!_ready) return;
    await _box!.put(_kAutoCut, value);
  }

  static Future<void> setReceiptConnection(bool connected) async {
    if (!_ready) return;
    await _box!.put(_kReceiptConnected, connected);
    if (connected) {
      await _box!.put(_kReceiptWasConnected, true);
    }
  }

  static Future<void> setReceiptWasConnected(bool value) async {
    if (!_ready) return;
    await _box!.put(_kReceiptWasConnected, value);
  }
}

