import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';

import '../models/printer_models.dart';
import '../services/usb_printer_bridge.dart';
import '../storage/printer_store.dart';
import 'print_preview_screen.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _bridge = UsbPrinterBridge.instance;

  PrinterStatus _status = PrinterStatus(
    connected: false,
    hasDevice: false,
  );

  LabelSettings _settings = const LabelSettings();

  StreamSubscription<Map<dynamic, dynamic>>? _eventSub;
  String? _message;
  bool _busy = false;
  bool _openingJob = false;

  bool get _connected => _status.connected;
  bool get _bluetooth => _settings.transport == PrinterTransport.bluetooth;
  bool get _wifi => _settings.transport == PrinterTransport.wifi;

  @override
  void initState() {
    super.initState();
    _bootstrap();
  }

  Future<void> _bootstrap() async {
    await _refresh();
    _settings = await _bridge.getLabelSettings();

    final receiptAddr = PrinterStore.receiptAddress;
    if (receiptAddr.isNotEmpty) {
      _settings = _settings.copyWith(receiptBtAddress: receiptAddr);
      _settings = _settings.copyWith(transport: PrinterTransport.bluetooth);
    }

    if (_settings.transport == PrinterTransport.bluetooth) {
      final bt = await _bridge.getBluetoothStatus();
      if (PrinterStore.receiptWasConnected &&
          receiptAddr.isNotEmpty &&
          !bt.receipt.connected) {
        await _bridge.connectBluetooth(role: 'receipt', address: receiptAddr);
      }
      await _refresh();
    }

    await _checkPrintService();
    if (mounted) setState(() {});

    _eventSub = _bridge.events.listen((event) {
      final type = event['type']?.toString();
      if (type == 'deviceChanged' || type == 'permission') {
        final statusMap = event['status'];
        if (statusMap is Map) {
          setState(() => _status = PrinterStatus.fromMap(statusMap));
        } else {
          _refresh();
        }
        if (type == 'permission' && event['granted'] == true) {
          _setMessage('USB permission granted — printer ready.');
        }
      }
    });
  }

  Future<void> _checkPrintService() async {
    try {
      await _bridge.isPrintServiceEnabled();
    } catch (_) {}
  }

  @override
  void dispose() {
    _eventSub?.cancel();
    super.dispose();
  }

  void _setMessage(String msg) {
    if (!mounted) return;
    setState(() => _message = msg);
  }

  Future<void> _refresh() async {
    try {
      final s = await _bridge.getStatus();
      if (mounted) {
        setState(() {
          _status = s;
        });
      }
    } catch (e) {
      _setMessage('Status error: $e');
    }
  }

  void _openJob(String path, String name, PrintFormat format) {
    if (_openingJob) return;
    if (!File(path).existsSync()) {
      _setMessage('Print file missing: $path');
      return;
    }
    final jobSettings = _settings.copyWith(
      format: format,
      widthDots: LabelSettings.receipt80mmWidthDots,
      heightDots: LabelSettings.receipt80mmHeightDots,
    );
    _openingJob = true;
    Navigator.of(context)
        .push(
          MaterialPageRoute(
            builder: (_) => PrintPreviewScreen(
              path: path,
              name: name,
              settings: jobSettings,
            ),
          ),
        )
        .whenComplete(() {
      _openingJob = false;
    });
  }

  Future<void> _connect() async {
    setState(() => _busy = true);
    try {
      Map<dynamic, dynamic> result;
      if (_bluetooth) {
        if (_settings.receiptBtAddress.isEmpty) {
          _setMessage('Open Settings and assign receipt Bluetooth printer first.');
          return;
        }
        await _bridge.saveLabelSettings(_settings);
        result = await _bridge.connectAllBluetooth();
        await _refresh();
        final receipt = result['receipt'];
        final rOk = receipt is! Map || receipt['ok'] == true;
        if (rOk) {
          _setMessage(
            _status.receiptConnected
                ? 'Bluetooth printer ready'
                : 'Bluetooth connect finished',
          );
        } else {
          _setMessage('BT connect failed: ${_btOk(receipt)}');
        }
        return;
      }
      if (_wifi) {
        if (_settings.wifi.host.trim().isEmpty) {
          _setMessage('Open Settings and enter the Wi‑Fi printer IP first.');
          return;
        }
        result = await _bridge.connectWifi(_settings.wifi);
      } else {
        result = await _bridge.connect();
      }
      await _refresh();
      if (result['ok'] == true) {
        _setMessage(
          _wifi
              ? 'Wi‑Fi ready · ${_settings.wifi.host}:${_settings.wifi.port}'
              : 'Connected to ${_status.device?.displayName ?? "printer"}',
        );
      } else if (result['pendingPermission'] == true) {
        _setMessage('Allow USB access when prompted…');
      } else {
        _setMessage(result['error']?.toString() ?? 'Connect failed');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String _btOk(Object? v) {
    if (v is Map) {
      return v['ok'] == true ? 'ok' : (v['error']?.toString() ?? 'fail');
    }
    return 'skipped';
  }

  Future<void> _disconnect() async {
    if (_bluetooth) {
      await _bridge.disconnectBluetooth();
      await PrinterStore.setReceiptConnection(false);
      await PrinterStore.setReceiptWasConnected(false);
    } else {
      await _bridge.disconnect();
    }
    await _refresh();
    _setMessage('Disconnected');
  }

  Future<void> _disconnectRole(String role) async {
    if (!_bluetooth) return;
    setState(() => _busy = true);
    try {
      await _bridge.disconnectBluetooth(role: role);
      await _refresh();
      await PrinterStore.setReceiptConnection(false);
      await PrinterStore.setReceiptWasConnected(false);
      _setMessage('Disconnected printer');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _testPrint(PrintFormat format) async {
    setState(() => _busy = true);
    try {
      final settings = _settings.copyWith(format: format);
      final result = await _bridge.printTestLabel(settings);
      if (result['ok'] == true) {
        _setMessage(
          '${format.displayName} test sent '
          '(${result['bytesWritten'] ?? result['protocol'] ?? 'ok'})',
        );
      } else if (result['pendingPermission'] == true) {
        _setMessage('Allow USB access when prompted…');
      } else {
        _setMessage(result['error']?.toString() ?? 'Test print failed');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _openSettings() async {
    final updated = await Navigator.of(context).push<LabelSettings>(
      MaterialPageRoute(builder: (_) => SettingsScreen(settings: _settings)),
    );
    if (updated != null) {
      setState(() => _settings = updated);
      await _bridge.saveLabelSettings(updated);
      if (updated.transport == PrinterTransport.bluetooth) {
        await PrinterStore.setAssigned(
          receiptAddress: updated.receiptBtAddress,
          labelAddress: '',
        );
        await PrinterStore.setReceiptWasConnected(false);
      }
      await PrinterStore.setTransport(updated.transport);
      await PrinterStore.setAutoCut(updated.autoCut);
      await _refresh();
    }
  }


  Future<String?> _pickLocalDemo(PrintFormat format) async {
    final dir = Directory.systemTemp;
    final file = File('${dir.path}/printfox_sample_${format.wireValue}.txt');
    final sample = format == PrintFormat.bill
        ? 'FatFox Customer Bill\n'
              'Sample 80mm thermal bill\n'
              '${DateTime.now()}\n'
              'Bill · ~72 mm printable'
        : 'FatFox Kitchen Order (KOT)\n'
              'Sample 80mm thermal KOT\n'
              '${DateTime.now()}\n'
              'KOT · ~72 mm printable';
    await file.writeAsString(sample);
    return file.path;
  }

  Future<void> _runSample(PrintFormat format) async {
    final path = await _pickLocalDemo(format);
    if (path != null) {
      _openJob(
        path,
        format == PrintFormat.bill ? 'Sample Bill' : 'Sample KOT',
        format,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final wide = MediaQuery.sizeOf(context).width >= 900;

    return Scaffold(
      appBar: AppBar(
        title: const Row(
          children: [
            Icon(Icons.print_rounded, size: 26),
            SizedBox(width: 10),
            Text('FatFox Printer Driver'),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh status',
            onPressed: _refresh,
          ),
          IconButton(
            icon: const Icon(Icons.tune),
            tooltip: 'Printer settings',
            onPressed: _openSettings,
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _ConnectionCard(
                  connected: _connected,
                  wifi: _wifi,
                  bluetooth: _bluetooth,
                  hasDevice: _status.hasDevice,
                  deviceName: _status.device?.displayName,
                  wifiHost: _settings.wifi.host,
                  wifiPort: _settings.wifi.port,
                  useGdi: _settings.wifi.useGdi,
                  receiptConnected: _status.receiptConnected,
                  receiptName: _status.bluetooth.receipt.name ??
                      (_settings.receiptBtAddress.isEmpty
                          ? null
                          : _settings.receiptBtAddress),
                  autoCut: _settings.autoCut,
                  busy: _busy,
                  onConnect: _connected ? _disconnect : _connect,
                  onDisconnectReceipt: _bluetooth
                      ? () => _disconnectRole('receipt')
                      : null,
                  onSettings: _openSettings,
                ),
                if (_message != null) ...[
                  const SizedBox(height: 12),
                  Text(
                    _message!,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],

                const SizedBox(height: 28),
                Text(
                  'What this app prints',
                  style: theme.textTheme.titleLarge?.copyWith(
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  'Website prints KOT (printfox://…&format=kot) and Bill (printfox://…&format=bill) directly to 80mm thermal printers.',
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 16),

                if (wide)
                  Row(
                    children: [
                      Expanded(
                        child: _PrintTypeCard(
                          icon: Icons.receipt_long_outlined,
                          title: 'KOT (Kitchen Order)',
                          subtitle: 'Thermal · ESC/POS · auto-cut',
                          deepLinkHint: 'printfox://…&format=kot',
                          details: const [
                            '~72 mm printable width',
                            'Bluetooth / USB receipt printer',
                            'Website / share / in-app print',
                          ],
                          primaryLabel: 'Sample KOT preview',
                          secondaryLabel: 'Test KOT print',
                          onPrimary: () => _runSample(PrintFormat.kot),
                          onSecondary: _busy
                              ? null
                              : () => _testPrint(PrintFormat.kot),
                        ),
                      ),
                      const SizedBox(width: 16),
                      Expanded(
                        child: _PrintTypeCard(
                          icon: Icons.description_outlined,
                          title: 'Bill (Customer Receipt)',
                          subtitle: 'Thermal · ESC/POS · auto-cut',
                          deepLinkHint: 'printfox://…&format=bill',
                          details: const [
                            '~72 mm printable width',
                            'Bluetooth / USB receipt printer',
                          ],
                          primaryLabel: 'Sample Bill preview',
                          secondaryLabel: 'Test Bill print',
                          onPrimary: () => _runSample(PrintFormat.bill),
                          onSecondary: _busy
                              ? null
                              : () => _testPrint(PrintFormat.bill),
                          onEditLayout: _openSettings,
                        ),
                      ),
                    ],
                  )
                else ...[
                  _PrintTypeCard(
                    icon: Icons.receipt_long_outlined,
                    title: 'KOT (Kitchen Order)',
                    subtitle: 'Thermal · ESC/POS · auto-cut',
                    deepLinkHint: 'printfox://…&format=kot',
                    details: const [
                      '~72 mm printable width',
                      'Bluetooth / USB receipt printer',
                    ],
                    primaryLabel: 'Sample KOT preview',
                    secondaryLabel: 'Test KOT print',
                    onPrimary: () => _runSample(PrintFormat.kot),
                    onSecondary: _busy ? null : () => _testPrint(PrintFormat.kot),
                  ),
                  const SizedBox(height: 12),
                  _PrintTypeCard(
                    icon: Icons.description_outlined,
                    title: 'Bill (Customer Receipt)',
                    subtitle: 'Thermal · ESC/POS · auto-cut',
                    deepLinkHint: 'printfox://…&format=bill',
                    details: const [
                      '~72 mm printable width',
                      'Bluetooth / USB receipt printer',
                    ],
                    primaryLabel: 'Sample Bill preview',
                    secondaryLabel: 'Test Bill print',
                    onPrimary: () => _runSample(PrintFormat.bill),
                    onSecondary: _busy ? null : () => _testPrint(PrintFormat.bill),
                    onEditLayout: _openSettings,
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ConnectionCard extends StatelessWidget {
  const _ConnectionCard({
    required this.connected,
    required this.wifi,
    required this.bluetooth,
    required this.hasDevice,
    required this.deviceName,
    required this.wifiHost,
    required this.wifiPort,
    required this.useGdi,
    required this.receiptConnected,
    required this.receiptName,
    required this.autoCut,
    required this.busy,
    required this.onConnect,
    required this.onDisconnectReceipt,
    required this.onSettings,
  });

  final bool connected;
  final bool wifi;
  final bool bluetooth;
  final bool hasDevice;
  final String? deviceName;
  final String wifiHost;
  final int wifiPort;
  final bool useGdi;
  final bool receiptConnected;
  final String? receiptName;
  final bool autoCut;
  final bool busy;
  final VoidCallback onConnect;
  final VoidCallback? onDisconnectReceipt;
  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = connected
        ? const Color(0xFF2E7D32)
        : (hasDevice ||
              (wifi && wifiHost.isNotEmpty) ||
              (bluetooth && (receiptName != null && receiptName!.isNotEmpty)))
        ? const Color(0xFFE65100)
        : theme.colorScheme.outline;

    final title = bluetooth
        ? (receiptConnected
              ? 'Bluetooth printer connected'
              : 'Bluetooth printer — tap connect')
        : connected
        ? (wifi ? 'Wi‑Fi printer ready' : 'USB printer connected')
        : wifi
        ? (wifiHost.isEmpty
              ? 'Wi‑Fi not configured'
              : 'Wi‑Fi printer — tap connect')
        : hasDevice
        ? 'USB printer found — tap connect'
        : 'No printer connected';

    final detail = bluetooth
        ? 'Printer: ${receiptConnected ? (receiptName ?? "connected") : (receiptName ?? "not set")}${autoCut ? " · auto-cut" : ""}'
        : wifi
        ? (wifiHost.isEmpty
              ? 'Set printer IP in Settings'
              : '$wifiHost:$wifiPort${useGdi ? " · GDI" : ""}')
        : (deviceName ?? 'USB OTG · thermal receipt printer');

    final transportLabel = bluetooth
        ? 'Bluetooth (80mm Thermal)'
        : wifi
        ? 'Wi‑Fi'
        : 'USB';

    IconData leadingIcon;
    if (bluetooth) {
      leadingIcon = connected ? Icons.bluetooth_connected : Icons.bluetooth;
    } else if (wifi) {
      leadingIcon = connected ? Icons.wifi : Icons.wifi_off;
    } else {
      leadingIcon = connected ? Icons.usb : Icons.usb_off;
    }

    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        gradient: LinearGradient(
          colors: [
            color.withValues(alpha: 0.14),
            theme.colorScheme.surfaceContainerHighest,
          ],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        border: Border.all(color: color.withValues(alpha: 0.35)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Container(
                width: 56,
                height: 56,
                decoration: BoxDecoration(
                  color: color.withValues(alpha: 0.15),
                  shape: BoxShape.circle,
                ),
                child: Icon(leadingIcon, color: color, size: 28),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      detail,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Transport: $transportLabel',
                      style: theme.textTheme.labelMedium?.copyWith(
                        color: color,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          if (bluetooth && onDisconnectReceipt != null) ...[
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: receiptConnected ? onDisconnectReceipt : null,
              icon: const Icon(Icons.link_off),
              label: const Text('Disconnect Bluetooth printer'),
            ),
          ],
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: FilledButton.icon(
                  onPressed: busy ? null : onConnect,
                  icon: Icon(
                    connected
                        ? Icons.link_off
                        : (bluetooth
                              ? Icons.bluetooth_connected
                              : (wifi ? Icons.wifi : Icons.usb)),
                  ),
                  label: Text(
                    connected
                        ? 'Disconnect'
                        : (bluetooth
                              ? 'Connect printer'
                              : (wifi ? 'Connect Wi‑Fi' : 'Connect USB')),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: onSettings,
                  icon: const Icon(Icons.tune),
                  label: const Text('Settings'),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _PrintTypeCard extends StatelessWidget {
  const _PrintTypeCard({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.deepLinkHint,
    required this.details,
    required this.primaryLabel,
    required this.secondaryLabel,
    required this.onPrimary,
    required this.onSecondary,
    this.onEditLayout,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final String deepLinkHint;
  final List<String> details;
  final String primaryLabel;
  final String secondaryLabel;
  final VoidCallback onPrimary;
  final VoidCallback? onSecondary;
  final VoidCallback? onEditLayout;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(icon, size: 28, color: theme.colorScheme.primary),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        subtitle,
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                deepLinkHint,
                style: theme.textTheme.labelSmall?.copyWith(
                  fontFamily: 'monospace',
                ),
              ),
            ),
            const SizedBox(height: 12),
            for (final line in details)
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(
                      Icons.check_circle_outline,
                      size: 16,
                      color: theme.colorScheme.primary,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(line, style: theme.textTheme.bodySmall),
                    ),
                  ],
                ),
              ),
            const SizedBox(height: 16),
            FilledButton(onPressed: onPrimary, child: Text(primaryLabel)),
            const SizedBox(height: 8),
            OutlinedButton(onPressed: onSecondary, child: Text(secondaryLabel)),
          ],
        ),
      ),
    );
  }
}
