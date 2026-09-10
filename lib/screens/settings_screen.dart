import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/printer_models.dart';
import '../services/usb_printer_bridge.dart';
import '../storage/printer_store.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.settings});

  final LabelSettings settings;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final _bridge = UsbPrinterBridge.instance;
  late LabelSettings _settings;
  late final TextEditingController _wifiHost;
  late final TextEditingController _wifiPort;

  List<BluetoothPrinterDevice> _btDevices = const [];
  BluetoothHubStatus _btStatus = const BluetoothHubStatus();
  bool _btBusy = false;
  String? _btMessage;

  @override
  void initState() {
    super.initState();
    _settings = widget.settings;
    _wifiHost = TextEditingController(text: _settings.wifi.host);
    _wifiPort = TextEditingController(text: '${_settings.wifi.port}');
    _refreshBluetooth();
  }

  @override
  void dispose() {
    _wifiHost.dispose();
    _wifiPort.dispose();
    super.dispose();
  }

  Future<void> _refreshBluetooth() async {
    try {
      final devices = await _bridge.listBluetoothPrinters();
      final status = await _bridge.getBluetoothStatus();
      if (!mounted) return;
      setState(() {
        _btDevices = devices;
        _btStatus = status;
        _btMessage = null;
      });
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() => _btMessage = e.message ?? 'Bluetooth list failed');
    } catch (e) {
      if (!mounted) return;
      setState(() => _btMessage = e.toString());
    }
  }

  Future<void> _connectRole(String role, String address) async {
    if (address.isEmpty) {
      setState(() => _btMessage = 'Pick a bonded printer first');
      return;
    }
    setState(() => _btBusy = true);
    try {
      final result =
          await _bridge.connectBluetooth(role: role, address: address);
      await _refreshBluetooth();
      if (!mounted) return;
      setState(() {
        _btMessage = result['ok'] == true
            ? 'Connected receipt printer · ${result['name'] ?? address}'
            : (result['error']?.toString() ?? 'Connect failed');
        if (result['ok'] == true) {
          _settings = _settings.copyWith(
            transport: PrinterTransport.bluetooth,
            receiptBtAddress: address,
          );
        }
      });

      if (result['ok'] == true) {
        await PrinterStore.setAssigned(
          receiptAddress: _settings.receiptBtAddress,
          labelAddress: '',
        );
        await PrinterStore.setTransport(PrinterTransport.bluetooth);
        await PrinterStore.setReceiptConnection(true);
      }
    } finally {
      if (mounted) setState(() => _btBusy = false);
    }
  }

  Future<void> _disconnectRole(String role) async {
    setState(() => _btBusy = true);
    try {
      final result = await _bridge.disconnectBluetooth(role: role);
      await _refreshBluetooth();
      if (!mounted) return;
      setState(() {
        _btMessage = result['ok'] == true
            ? 'Disconnected receipt printer'
            : (result['error']?.toString() ?? 'Disconnect failed');
      });
      if (result['ok'] == true) {
        await PrinterStore.setReceiptConnection(false);
        await PrinterStore.setReceiptWasConnected(false);
        await PrinterStore.setAssigned(
          receiptAddress: _settings.receiptBtAddress,
          labelAddress: '',
        );
      }
    } finally {
      if (mounted) setState(() => _btBusy = false);
    }
  }

  LabelSettings _collect() {
    final port = int.tryParse(_wifiPort.text) ?? 9100;
    return _settings.copyWith(
      format: PrintFormat.kot,
      widthDots: _settings.widthDots <= 0
          ? LabelSettings.receipt80mmWidthDots
          : _settings.widthDots,
      heightDots: _settings.heightDots <= 0
          ? LabelSettings.receipt80mmHeightDots
          : _settings.heightDots,
      wifi: _settings.wifi.copyWith(
        host: _wifiHost.text.trim(),
        port: port.clamp(1, 65535),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('FatFox Printer Settings'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, _collect()),
            child: const Text('Save'),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _bluetoothCard(context),
          const SizedBox(height: 24),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _sectionTitle(context, 'Thermal Print Quality'),
                  const SizedBox(height: 12),
                  Text('Darkness threshold: ${_settings.threshold}'),
                  Slider(
                    min: 80,
                    max: 220,
                    divisions: 28,
                    value: _settings.threshold.toDouble(),
                    onChanged: (v) => setState(() {
                      _settings = _settings.copyWith(threshold: v.round());
                    }),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 24),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _sectionTitle(context, 'Network (Wi-Fi) Printer'),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _wifiHost,
                    decoration: const InputDecoration(
                      labelText: 'IP Address / Host',
                      hintText: 'e.g. 192.168.1.100',
                      border: OutlineInputBorder(),
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _wifiPort,
                    decoration: const InputDecoration(
                      labelText: 'Port (default 9100)',
                      border: OutlineInputBorder(),
                    ),
                    keyboardType: TextInputType.number,
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _bluetoothCard(BuildContext context) {
    final receiptAddr = _settings.receiptBtAddress;
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    'Bluetooth 80mm Thermal Printer',
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                ),
                IconButton(
                  tooltip: 'Refresh bonded list',
                  onPressed: _btBusy ? null : _refreshBluetooth,
                  icon: const Icon(Icons.refresh),
                ),
              ],
            ),
            Text(
              _btStatus.enabled
                  ? 'Bluetooth on · ${_btDevices.length} bonded device(s)'
                  : 'Bluetooth is off — enable it in Android settings',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 16),
            _btRolePicker(
              context,
              title: '80mm Receipt Printer (KOT & Bill)',
              role: 'receipt',
              address: receiptAddr,
              connected: _btStatus.receipt.connected,
              connectedName: _btStatus.receipt.name,
              onChanged: (v) => setState(() {
                _settings = _settings.copyWith(receiptBtAddress: v ?? '');
              }),
            ),
            if (_btMessage != null) ...[
              const SizedBox(height: 12),
              Text(
                _btMessage!,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.primary,
                    ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _btRolePicker(
    BuildContext context, {
    required String title,
    required String role,
    required String address,
    required bool connected,
    required String? connectedName,
    required ValueChanged<String?> onChanged,
  }) {
    final items = <DropdownMenuItem<String>>[
      const DropdownMenuItem(value: '', child: Text('— Not assigned —')),
      ..._btDevices.map(
        (d) => DropdownMenuItem(
          value: d.address,
          child: Text(d.displayName, overflow: TextOverflow.ellipsis),
        ),
      ),
      if (address.isNotEmpty &&
          !_btDevices.any((d) => d.address == address))
        DropdownMenuItem(
          value: address,
          child: Text('Saved · $address', overflow: TextOverflow.ellipsis),
        ),
    ];
    final value = address.isEmpty
        ? ''
        : (items.any((i) => i.value == address) ? address : '');

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Icon(
              connected ? Icons.bluetooth_connected : Icons.bluetooth,
              size: 18,
              color: connected
                  ? const Color(0xFF2E7D32)
                  : Theme.of(context).colorScheme.outline,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                title,
                style: Theme.of(context).textTheme.titleSmall,
              ),
            ),
            Text(
              connected ? (connectedName ?? 'Connected') : 'Not connected',
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: connected
                        ? const Color(0xFF2E7D32)
                        : Theme.of(context).colorScheme.outline,
                  ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        InputDecorator(
          decoration: const InputDecoration(
            border: OutlineInputBorder(),
            labelText: 'Bonded printer',
          ),
          child: DropdownButtonHideUnderline(
            child: DropdownButton<String>(
              value: value,
              isExpanded: true,
              items: items,
              onChanged: onChanged,
            ),
          ),
        ),
        const SizedBox(height: 8),
        Align(
          alignment: Alignment.centerRight,
          child: TextButton.icon(
            onPressed: _btBusy || value.isEmpty
                ? null
                : () => _connectRole(role, value),
            icon: const Icon(Icons.link),
            label: Text('Connect printer'),
          ),
        ),
        if (connected) ...[
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton.icon(
              onPressed: _btBusy ? null : () => _disconnectRole(role),
              icon: const Icon(Icons.link_off),
              label: Text('Disconnect printer'),
            ),
          ),
        ],
      ],
    );
  }

  Widget _sectionTitle(BuildContext context, String text) {
    return Text(text, style: Theme.of(context).textTheme.titleMedium);
  }
}
