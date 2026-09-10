import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../models/printer_models.dart';
import '../services/usb_printer_bridge.dart';
import 'print_preview_screen.dart';

/// In-app browser for your billing website.
/// Bypasses Chrome + Android Print Service — print goes to USB via JS bridge.
class WebPrintScreen extends StatefulWidget {
  const WebPrintScreen({super.key, required this.settings});

  final LabelSettings settings;

  @override
  State<WebPrintScreen> createState() => _WebPrintScreenState();
}

class _WebPrintScreenState extends State<WebPrintScreen> {
  static const _prefsUrlKey = 'web_print_url';

  final _bridge = UsbPrinterBridge.instance;
  final _urlController = TextEditingController();
  late final WebViewController _web;
  bool _loading = true;
  String? _status;
  bool _printing = false;

  @override
  void initState() {
    super.initState();
    _web = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.white)
      ..addJavaScriptChannel(
        'Lp46Bridge',
        onMessageReceived: _onBridgeMessage,
      )
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageStarted: (_) {
            if (mounted) setState(() => _loading = true);
          },
          onPageFinished: (_) async {
            await _injectPrintHook();
            if (mounted) setState(() => _loading = false);
          },
          onWebResourceError: (err) {
            if (!mounted) return;
            setState(() {
              _loading = false;
              _status = err.description;
            });
          },
        ),
      );
    _loadSavedUrl();
  }

  Future<void> _loadSavedUrl() async {
    final prefs = await SharedPreferences.getInstance();
    final url = prefs.getString(_prefsUrlKey) ?? '';
    _urlController.text = url;
    if (url.isNotEmpty) {
      await _openUrl(url);
    } else if (mounted) {
      setState(() => _loading = false);
    }
  }

  Future<void> _saveAndOpen() async {
    var url = _urlController.text.trim();
    if (url.isEmpty) return;
    if (!url.startsWith('http://') && !url.startsWith('https://')) {
      url = 'https://$url';
      _urlController.text = url;
    }
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefsUrlKey, url);
    await _openUrl(url);
  }

  Future<void> _openUrl(String url) async {
    setState(() {
      _loading = true;
      _status = null;
    });
    await _web.loadRequest(Uri.parse(url));
  }

  Future<void> _injectPrintHook() async {
    await _web.runJavaScript('''
(function() {
  if (window.__lp46Hooked) return;
  window.__lp46Hooked = true;
  window.Lp46 = {
    printText: function(text) {
      Lp46Bridge.postMessage(JSON.stringify({ type: 'printText', text: String(text || '') }));
    },
    printZpl: function(zpl) {
      Lp46Bridge.postMessage(JSON.stringify({ type: 'printZpl', zpl: String(zpl || '') }));
    },
    printPage: function() {
      var text = (document.body && (document.body.innerText || document.body.textContent)) || '';
      Lp46Bridge.postMessage(JSON.stringify({
        type: 'printText',
        text: text,
        title: document.title || 'Web print'
      }));
    }
  };
  window.print = function() { window.Lp46.printPage(); };
})();
''');
  }

  Future<void> _onBridgeMessage(JavaScriptMessage message) async {
    try {
      final data = jsonDecode(message.message) as Map<String, dynamic>;
      final type = data['type']?.toString();
      if (type == 'printText') {
        final text = data['text']?.toString() ?? '';
        final title = data['title']?.toString() ?? 'Web print';
        if (text.trim().isEmpty) {
          _toast('Nothing to print on this page');
          return;
        }
        await _printText(text, title);
      } else if (type == 'printZpl') {
        final zpl = data['zpl']?.toString() ?? '';
        if (zpl.trim().isEmpty) {
          _toast('Empty ZPL');
          return;
        }
        await _printRawZpl(zpl);
      }
    } catch (e) {
      _toast('Bridge error: $e');
    }
  }

  Future<void> _printPageFromToolbar() async {
    await _web.runJavaScript(
      'window.Lp46 ? window.Lp46.printPage() : alert("Page not ready");',
    );
  }

  Future<void> _printText(String text, String title) async {
    if (_printing) return;
    setState(() => _printing = true);
    try {
      final connect = await _bridge.connect();
      if (connect['pendingPermission'] == true) {
        _toast('Allow USB permission, then tap Print again');
        return;
      }
      final result = await _bridge.printText(text, widget.settings);
      if (result['ok'] == true) {
        _toast('Printed “$title” (${result['bytesWritten']} bytes)');
        return;
      }

      _toast(result['error']?.toString() ?? 'Opening preview…');
      final file = File(
        '${Directory.systemTemp.path}/web_${DateTime.now().millisecondsSinceEpoch}.txt',
      );
      await file.writeAsString(text);
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => PrintPreviewScreen(
            path: file.path,
            name: title,
            settings: widget.settings,
          ),
        ),
      );
    } catch (e) {
      _toast('Print failed: $e');
    } finally {
      if (mounted) setState(() => _printing = false);
    }
  }

  Future<void> _printRawZpl(String zpl) async {
    if (_printing) return;
    setState(() => _printing = true);
    try {
      await _bridge.connect();
      final result = await _bridge.writeText(zpl);
      _toast(
        result['ok'] == true
            ? 'ZPL sent (${result['bytesWritten']} bytes)'
            : (result['error']?.toString() ?? 'ZPL print failed'),
      );
    } catch (e) {
      _toast('ZPL failed: $e');
    } finally {
      if (mounted) setState(() => _printing = false);
    }
  }

  void _toast(String msg) {
    if (!mounted) return;
    setState(() => _status = msg);
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  void dispose() {
    _urlController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Web print'),
        actions: [
          IconButton(
            tooltip: 'Reload',
            onPressed: () => _web.reload(),
            icon: const Icon(Icons.refresh),
          ),
          IconButton(
            tooltip: 'Print page',
            onPressed: _printing ? null : _printPageFromToolbar,
            icon: _printing
                ? const SizedBox(
                    width: 22,
                    height: 22,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.print),
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _urlController,
                    decoration: const InputDecoration(
                      isDense: true,
                      border: OutlineInputBorder(),
                      hintText: 'https://your-site.com',
                      labelText: 'Website URL',
                    ),
                    keyboardType: TextInputType.url,
                    textInputAction: TextInputAction.go,
                    onSubmitted: (_) => _saveAndOpen(),
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton(
                  onPressed: _saveAndOpen,
                  child: const Text('Go'),
                ),
              ],
            ),
          ),
          if (_loading) const LinearProgressIndicator(minHeight: 2),
          if (_status != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  _status!,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
            ),
          Expanded(child: WebViewWidget(controller: _web)),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 6, 12, 10),
              child: Text(
                'Recommended: open your site here instead of Chrome. '
                'window.print() and the Print icon send bills to USB. '
                'Your site can also call Lp46.printText(...) or Lp46.printZpl(...).',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
