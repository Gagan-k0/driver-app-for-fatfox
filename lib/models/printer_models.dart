enum PrintFormat {
  /// Kitchen Order Ticket – 80mm thermal receipt.
  kot,

  /// Customer Bill – 80mm thermal receipt.
  bill;

  /// Parse wire/deep-link values with backward compatibility.
  static PrintFormat parse(String? raw) {
    final v = (raw ?? '').trim().toLowerCase();
    switch (v) {
      case 'bill':
      case 'receipt':
      case 'label': // backward compat
      case 'labels':
        return PrintFormat.bill;
      case 'kot':
      case 'kitchen':
      case 'a5': // backward compat
      case '80mm':
      case 'thermal':
      case 'a5_landscape':
      case 'a5-landscape':
      case 'canfox':
      default:
        return PrintFormat.kot;
    }
  }

  String get wireValue => this == PrintFormat.bill ? 'bill' : 'kot';

  String get displayName =>
      this == PrintFormat.bill ? 'Bill (80mm)' : 'KOT (80mm)';
}

enum PrinterTransport {
  bluetooth,
  usb,
  wifi;

  static PrinterTransport parse(String? raw) {
    final v = (raw ?? '').trim().toLowerCase();
    if (v == 'wifi' || v == 'wi-fi' || v == 'network') {
      return PrinterTransport.wifi;
    }
    if (v == 'usb') return PrinterTransport.usb;
    return PrinterTransport.bluetooth;
  }

  String get wireValue => switch (this) {
        PrinterTransport.wifi => 'wifi',
        PrinterTransport.usb => 'usb',
        PrinterTransport.bluetooth => 'bluetooth',
      };

  String get displayName => switch (this) {
        PrinterTransport.wifi => 'Wi‑Fi',
        PrinterTransport.usb => 'USB',
        PrinterTransport.bluetooth => 'Bluetooth',
      };
}

class BluetoothPrinterDevice {
  const BluetoothPrinterDevice({
    required this.name,
    required this.address,
    this.bonded = true,
    this.connectedRoles = const [],
  });

  final String name;
  final String address;
  final bool bonded;
  final List<String> connectedRoles;

  factory BluetoothPrinterDevice.fromMap(Map<dynamic, dynamic> map) {
    final roles = (map['connectedRoles'] as List?) ?? const [];
    return BluetoothPrinterDevice(
      name: map['name']?.toString() ?? 'Bluetooth printer',
      address: map['address']?.toString() ?? '',
      bonded: map['bonded'] != false,
      connectedRoles: roles.map((e) => e.toString()).toList(),
    );
  }

  String get displayName {
    final n = name.trim().isEmpty ? 'Bluetooth printer' : name.trim();
    return '$n ($address)';
  }
}

class BluetoothRoleStatus {
  const BluetoothRoleStatus({
    this.connected = false,
    this.address,
    this.name,
  });

  final bool connected;
  final String? address;
  final String? name;

  factory BluetoothRoleStatus.fromMap(Map<dynamic, dynamic>? map) {
    if (map == null) return const BluetoothRoleStatus();
    return BluetoothRoleStatus(
      connected: map['connected'] == true,
      address: map['address']?.toString(),
      name: map['name']?.toString(),
    );
  }
}

class BluetoothHubStatus {
  const BluetoothHubStatus({
    this.available = false,
    this.enabled = false,
    this.receipt = const BluetoothRoleStatus(),
  });

  final bool available;
  final bool enabled;
  final BluetoothRoleStatus receipt;

  factory BluetoothHubStatus.fromMap(Map<dynamic, dynamic>? map) {
    if (map == null) return const BluetoothHubStatus();
    return BluetoothHubStatus(
      available: map['available'] == true,
      enabled: map['enabled'] == true,
      receipt: BluetoothRoleStatus.fromMap(
        map['receipt'] is Map ? Map<dynamic, dynamic>.from(map['receipt'] as Map) : null,
      ),
    );
  }
}

class PrinterDevice {
  PrinterDevice({
    required this.deviceName,
    required this.deviceId,
    required this.vendorId,
    required this.productId,
    required this.vendorIdHex,
    required this.productIdHex,
    required this.manufacturer,
    required this.product,
    required this.hasPermission,
  });

  final String deviceName;
  final int deviceId;
  final int vendorId;
  final int productId;
  final String vendorIdHex;
  final String productIdHex;
  final String manufacturer;
  final String product;
  final bool hasPermission;

  factory PrinterDevice.fromMap(Map<dynamic, dynamic> map) {
    return PrinterDevice(
      deviceName: map['deviceName']?.toString() ?? '',
      deviceId: (map['deviceId'] as num?)?.toInt() ?? 0,
      vendorId: (map['vendorId'] as num?)?.toInt() ?? 0,
      productId: (map['productId'] as num?)?.toInt() ?? 0,
      vendorIdHex: map['vendorIdHex']?.toString() ?? '',
      productIdHex: map['productIdHex']?.toString() ?? '',
      manufacturer: map['manufacturer']?.toString() ?? '',
      product: map['product']?.toString() ?? 'USB Printer',
      hasPermission: map['hasPermission'] == true,
    );
  }

  String get displayName {
    final name = product.trim().isEmpty ? 'USB Printer' : product.trim();
    final cleaned = name
        .replaceAll(RegExp(r'TVS', caseSensitive: false), '')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();
    final shown = cleaned.isEmpty ? 'USB Printer' : cleaned;
    return '$shown (VID:$vendorIdHex PID:$productIdHex)';
  }
}

class PrinterStatus {
  PrinterStatus({
    required this.connected,
    required this.hasDevice,
    this.device,
    this.devices = const [],
    this.transport = PrinterTransport.bluetooth,
    this.wifiHost,
    this.wifiPort,
    this.receiptConnected = false,
    this.bluetooth = const BluetoothHubStatus(),
  });

  final bool connected;
  final bool hasDevice;
  final PrinterDevice? device;
  final List<PrinterDevice> devices;
  final PrinterTransport transport;
  final String? wifiHost;
  final int? wifiPort;
  final bool receiptConnected;
  final BluetoothHubStatus bluetooth;

  factory PrinterStatus.fromMap(Map<dynamic, dynamic> map) {
    final deviceMap = map['device'];
    final list = (map['devices'] as List?) ?? const [];
    final btRaw = map['bluetooth'];
    final bt = BluetoothHubStatus.fromMap(
      btRaw is Map ? Map<dynamic, dynamic>.from(btRaw) : null,
    );
    return PrinterStatus(
      connected: map['connected'] == true,
      hasDevice: map['hasDevice'] == true,
      device: deviceMap is Map ? PrinterDevice.fromMap(deviceMap) : null,
      devices: list
          .whereType<Map>()
          .map((e) => PrinterDevice.fromMap(e))
          .toList(),
      transport: PrinterTransport.parse(map['transport']?.toString()),
      wifiHost: map['wifiHost']?.toString(),
      wifiPort: (map['wifiPort'] as num?)?.toInt(),
      receiptConnected:
          map['receiptConnected'] == true || bt.receipt.connected,
      bluetooth: bt,
    );
  }
}

class WifiPrinterConfig {
  const WifiPrinterConfig({
    this.host = '',
    this.port = 9100,
    this.useGdi = true,
  });

  final String host;
  final int port;

  /// TVSE Blaze SD-30NW and similar host-based lasers.
  final bool useGdi;

  WifiPrinterConfig copyWith({String? host, int? port, bool? useGdi}) {
    return WifiPrinterConfig(
      host: host ?? this.host,
      port: port ?? this.port,
      useGdi: useGdi ?? this.useGdi,
    );
  }

  Map<String, Object> toMap() => {
        'host': host,
        'port': port,
        'useGdi': useGdi,
      };

  factory WifiPrinterConfig.fromMap(Map<dynamic, dynamic>? map) {
    if (map == null) return const WifiPrinterConfig();
    return WifiPrinterConfig(
      host: map['host']?.toString() ?? '',
      port: (map['port'] as num?)?.toInt() ?? 9100,
      useGdi: map['useGdi'] != false,
    );
  }
}

class LabelSettings {
  /// 80mm thermal receipt printable width @ 203 DPI (~72 mm).
  static const int receipt80mmWidthDots = 576;
  static const int receipt80mmHeightDots = 800;

  /// Legacy A5 landscape @ 203 DPI (USB Canon fallback).
  static const int a5LandscapeWidthDots = 1678;
  static const int a5LandscapeHeightDots = 1183;

  const LabelSettings({
    this.widthDots = receipt80mmWidthDots,
    this.heightDots = receipt80mmHeightDots,
    this.threshold = 160,
    this.format = PrintFormat.kot,
    this.transport = PrinterTransport.bluetooth,
    this.wifi = const WifiPrinterConfig(),
    this.autoCut = true,
    this.receiptBtAddress = '',
  });

  factory LabelSettings.forFormat(PrintFormat format, {int threshold = 160}) {
    return LabelSettings(
      widthDots: receipt80mmWidthDots,
      heightDots: receipt80mmHeightDots,
      threshold: threshold,
      format: format,
    );
  }

  final int widthDots;
  final int heightDots;
  final int threshold;
  final PrintFormat format;
  final PrinterTransport transport;
  final WifiPrinterConfig wifi;
  final bool autoCut;
  final String receiptBtAddress;

  double get widthInches => widthDots / 203.0;
  double get heightInches => heightDots / 203.0;
  double get widthMm => widthInches * 25.4;
  double get heightMm => heightInches * 25.4;

  String get sizeCaption {
    return '${format.displayName} · ${widthMm.toStringAsFixed(0)} mm wide  ·  '
        '$widthDots×$heightDots @ 203 DPI';
  }

  LabelSettings copyWith({
    int? widthDots,
    int? heightDots,
    int? threshold,
    PrintFormat? format,
    PrinterTransport? transport,
    WifiPrinterConfig? wifi,
    bool? autoCut,
    String? receiptBtAddress,
  }) {
    return LabelSettings(
      widthDots: widthDots ?? this.widthDots,
      heightDots: heightDots ?? this.heightDots,
      threshold: threshold ?? this.threshold,
      format: format ?? this.format,
      transport: transport ?? this.transport,
      wifi: wifi ?? this.wifi,
      autoCut: autoCut ?? this.autoCut,
      receiptBtAddress: receiptBtAddress ?? this.receiptBtAddress,
    );
  }

  /// No-op kept for backward compat with callers.
  LabelSettings syncedLabelDots() => this;

  Map<String, Object> toMap() => {
        'widthDots': widthDots,
        'heightDots': heightDots,
        'threshold': threshold,
        'format': format.wireValue,
        'transport': transport.wireValue,
        'wifiHost': wifi.host,
        'wifiPort': wifi.port,
        'wifiUseGdi': wifi.useGdi,
        'autoCut': autoCut,
        'receiptBtAddress': receiptBtAddress,
      };

  factory LabelSettings.fromMap(Map<dynamic, dynamic> map) {
    final format = PrintFormat.parse(map['format']?.toString());
    return LabelSettings(
      widthDots: (map['widthDots'] as num?)?.toInt() ?? receipt80mmWidthDots,
      heightDots: (map['heightDots'] as num?)?.toInt() ?? receipt80mmHeightDots,
      threshold: (map['threshold'] as num?)?.toInt() ?? 160,
      format: format,
      transport: PrinterTransport.parse(map['transport']?.toString()),
      wifi: WifiPrinterConfig(
        host: map['wifiHost']?.toString() ?? '',
        port: (map['wifiPort'] as num?)?.toInt() ?? 9100,
        useGdi: map['wifiUseGdi'] != false,
      ),
      autoCut: map['autoCut'] != false,
      receiptBtAddress: map['receiptBtAddress']?.toString() ??
          map['labelBtAddress']?.toString() ?? // backward compat: migrate old labelBtAddress
          '',
    );
  }

  static const receiptPresets = <String, LabelSettings>{
    '80mm thermal receipt': LabelSettings(
      widthDots: receipt80mmWidthDots,
      heightDots: receipt80mmHeightDots,
      format: PrintFormat.kot,
    ),
    'Legacy A5 landscape': LabelSettings(
      widthDots: a5LandscapeWidthDots,
      heightDots: a5LandscapeHeightDots,
      format: PrintFormat.kot,
    ),
  };

  static Map<String, LabelSettings> presetsFor(PrintFormat format) =>
      receiptPresets;

  static Map<String, LabelSettings> get presets => receiptPresets;
}

class IncomingPrintJob {
  IncomingPrintJob({
    required this.path,
    required this.name,
    this.loading = false,
    this.format = PrintFormat.kot,
  });

  final String path;
  final String name;
  final bool loading;
  final PrintFormat format;
}
