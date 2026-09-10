import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';

import '../models/printer_models.dart';
import '../services/usb_printer_bridge.dart';
import 'home_screen.dart';
import 'print_preview_screen.dart';

/// Root gate: website / share / Print Service jobs never flash the home dashboard.
class AppRoot extends StatefulWidget {
  const AppRoot({super.key});

  @override
  State<AppRoot> createState() => _AppRootState();
}

enum _RootPhase { checking, loadingJob, preview, home, error }

class _AppRootState extends State<AppRoot> with WidgetsBindingObserver {
  final _bridge = UsbPrinterBridge.instance;

  _RootPhase _phase = _RootPhase.checking;
  String _jobName = 'Website print';
  String? _jobPath;
  String? _error;
  PrintFormat _jobFormat = PrintFormat.kot;
  LabelSettings _settings = const LabelSettings();
  LabelSettings? _jobSettings;
  StreamSubscription? _eventSub;
  bool _opening = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _bootstrap();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _eventSub?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed &&
        (_phase == _RootPhase.home || _phase == _RootPhase.error)) {
      _pollPendingJob(showLoadingWhileWaiting: true);
    }
  }

  Future<void> _bootstrap() async {
    try {
      _settings = await _bridge.getLabelSettings();
    } catch (_) {
      _settings = const LabelSettings();
    }
    _eventSub = _bridge.events.listen(_onEvent);
    try {
      await _pollPendingJob(showLoadingWhileWaiting: true);
    } catch (_) {}
    if (!mounted) return;
    if (_phase == _RootPhase.checking) {
      setState(() => _phase = _RootPhase.home);
    }
  }

  void _onEvent(Map<dynamic, dynamic> event) {
    final type = event['type']?.toString();
    if (type == 'deepLinkLoading') {
      if (!mounted) return;
      setState(() {
        _phase = _RootPhase.loadingJob;
        _jobName = event['name']?.toString() ?? 'Website print';
        _jobFormat = PrintFormat.parse(event['format']?.toString());
        _error = null;
      });
      _pollPendingJob(showLoadingWhileWaiting: true);
    } else if (type == 'deepLinkError') {
      if (!mounted) return;
      setState(() {
        _phase = _RootPhase.error;
        _error = event['error']?.toString() ?? 'Failed to load print job';
      });
    } else if (type == 'printJob') {
      final path = event['path']?.toString();
      final name = event['name']?.toString() ?? 'Print job';
      final format = PrintFormat.parse(event['format']?.toString());
      if (path != null && path.isNotEmpty) {
        _showPreview(path, name, format);
      }
    }
  }

  Future<void> _pollPendingJob({required bool showLoadingWhileWaiting}) async {
    try {
      var pending = await _bridge.getPendingPrintJob();
      if (pending != null && !pending.loading && pending.path.isNotEmpty) {
        _showPreview(pending.path, pending.name, pending.format);
        return;
      }
      if (pending?.loading == true) {
        if (!mounted) return;
        if (showLoadingWhileWaiting) {
          setState(() {
            _phase = _RootPhase.loadingJob;
            _jobName = pending!.name;
            _jobFormat = pending.format;
            _error = null;
          });
        }
        pending = await _bridge.waitForPendingPrintJob();
        if (pending != null && !pending.loading && pending.path.isNotEmpty) {
          _showPreview(pending.path, pending.name, pending.format);
          return;
        }
        if (!mounted) return;
        setState(() {
          _phase = _RootPhase.error;
          _error =
              'Could not load the print file. Check the website PDF link and try again.';
        });
        return;
      }
    } catch (e) {
      if (!mounted) return;
      // Cold start without a platform channel (tests) → home.
      if (_phase == _RootPhase.checking) {
        setState(() => _phase = _RootPhase.home);
      }
    }
  }

  Future<void> _finishExternalFlow() async {
    // Leave first so the user never flashes home before returning to the browser.
    await _bridge.returnToCaller();
    if (!mounted) return;
    setState(() {
      _phase = _RootPhase.home;
      _jobPath = null;
      _jobSettings = null;
      _error = null;
    });
  }

  void _showPreview(String path, String name, PrintFormat format) {
    if (_phase == _RootPhase.preview && _jobPath == path) return;
    if (_opening) return;
    if (!File(path).existsSync()) {
      if (!mounted) return;
      setState(() {
        _phase = _RootPhase.error;
        _error = 'Print file missing';
      });
      return;
    }
    _opening = true;
    _bridge.clearPendingPrintJob();
    if (!mounted) {
      _opening = false;
      return;
    }
    // Website format= decides A5 vs Label — no settings switch required.
    // Keep connection + sheet layout from saved settings for both job types.
    final jobSettings = format == PrintFormat.bill
        ? _settings.copyWith(format: PrintFormat.bill).syncedLabelDots()
        : _settings.copyWith(
            format: PrintFormat.kot,
            widthDots: LabelSettings.receipt80mmWidthDots,
            heightDots: LabelSettings.receipt80mmHeightDots,
          );
    setState(() {
      _phase = _RootPhase.preview;
      _jobPath = path;
      _jobName = name;
      _jobFormat = format;
      _jobSettings = jobSettings;
      _error = null;
    });
    _opening = false;
  }

  void _goHome() {
    if (!mounted) return;
    setState(() {
      _phase = _RootPhase.home;
      _jobPath = null;
      _jobSettings = null;
      _error = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    switch (_phase) {
      case _RootPhase.checking:
      case _RootPhase.loadingJob:
        return _PrintLoadingScreen(
          jobName: _jobName,
          format: _jobFormat,
        );
      case _RootPhase.error:
        return _PrintErrorScreen(
          message: _error ?? 'Something went wrong',
          onRetry: () {
            setState(() {
              _phase = _RootPhase.loadingJob;
              _error = null;
            });
            _pollPendingJob(showLoadingWhileWaiting: true);
          },
          onHome: _goHome,
          onClose: () => _bridge.returnToCaller(),
        );
      case _RootPhase.preview:
        return PrintPreviewScreen(
          path: _jobPath!,
          name: _jobName,
          settings: _jobSettings ?? _settings,
          returnToCallerOnDone: true,
          onFinished: _finishExternalFlow,
        );
      case _RootPhase.home:
        return const HomeScreen();
    }
  }
}

class _PrintLoadingScreen extends StatelessWidget {
  const _PrintLoadingScreen({
    required this.jobName,
    required this.format,
  });

  final String jobName;
  final PrintFormat format;

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    return Scaffold(
      backgroundColor: isDark ? const Color(0xFF0F172A) : const Color(0xFFFAF6F2),
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Container(
              constraints: const BoxConstraints(maxWidth: 420),
              padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 40),
              decoration: BoxDecoration(
                color: isDark ? const Color(0xFF1E293B) : Colors.white,
                borderRadius: BorderRadius.circular(24),
                border: Border.all(
                  color: isDark ? const Color(0xFF334155) : const Color(0xFFFFD6B8),
                  width: 1.5,
                ),
                boxShadow: [
                  BoxShadow(
                    color: const Color(0xFFFF6B00).withOpacity(0.12),
                    blurRadius: 24,
                    offset: const Offset(0, 8),
                  ),
                ],
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Container(
                    width: 72,
                    height: 72,
                    decoration: BoxDecoration(
                      gradient: const LinearGradient(
                        colors: [Color(0xFFFF6B00), Color(0xFFFF8C33)],
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                      ),
                      shape: BoxShape.circle,
                      boxShadow: [
                        BoxShadow(
                          color: const Color(0xFFFF6B00).withOpacity(0.35),
                          blurRadius: 16,
                          offset: const Offset(0, 6),
                        ),
                      ],
                    ),
                    child: const Icon(
                      Icons.print_rounded,
                      size: 36,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 24),
                  const Text(
                    'FatFox Driver',
                    style: TextStyle(
                      fontSize: 22,
                      fontWeight: FontWeight.w800,
                      letterSpacing: -0.5,
                    ),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 12),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
                    decoration: BoxDecoration(
                      color: const Color(0xFFFF6B00).withOpacity(0.12),
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Text(
                      'Preparing ${format.displayName} print…',
                      style: const TextStyle(
                        color: Color(0xFFFF6B00),
                        fontWeight: FontWeight.w700,
                        fontSize: 14,
                      ),
                      textAlign: TextAlign.center,
                    ),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    jobName,
                    style: TextStyle(
                      color: isDark ? Colors.grey[400] : const Color(0xFF64748B),
                      fontSize: 14,
                      fontWeight: FontWeight.w500,
                    ),
                    textAlign: TextAlign.center,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 32),
                  const SizedBox(
                    width: 38,
                    height: 38,
                    child: CircularProgressIndicator(
                      strokeWidth: 3.5,
                      valueColor: AlwaysStoppedAnimation<Color>(Color(0xFFFF6B00)),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _PrintErrorScreen extends StatelessWidget {
  const _PrintErrorScreen({
    required this.message,
    required this.onRetry,
    required this.onHome,
    required this.onClose,
  });

  final String message;
  final VoidCallback onRetry;
  final VoidCallback onHome;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    return Scaffold(
      backgroundColor: isDark ? const Color(0xFF0F172A) : const Color(0xFFFAF6F2),
      appBar: AppBar(title: const Text('FatFox Driver')),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Container(
              constraints: const BoxConstraints(maxWidth: 440),
              padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 32),
              decoration: BoxDecoration(
                color: isDark ? const Color(0xFF1E293B) : Colors.white,
                borderRadius: BorderRadius.circular(24),
                border: Border.all(
                  color: isDark ? const Color(0xFF334155) : const Color(0xFFFFD6B8),
                  width: 1.5,
                ),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Container(
                    width: 64,
                    height: 64,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: Colors.red.withOpacity(0.12),
                      shape: BoxShape.circle,
                    ),
                    child: const Icon(Icons.error_outline_rounded, size: 36, color: Colors.redAccent),
                  ),
                  const SizedBox(height: 20),
                  const Text(
                    'Print Loading Issue',
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 10),
                  Text(
                    message,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: isDark ? Colors.grey[400] : const Color(0xFF64748B),
                      fontSize: 14,
                    ),
                  ),
                  const SizedBox(height: 28),
                  ElevatedButton(
                    onPressed: onRetry,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: const Color(0xFFFF6B00),
                      foregroundColor: Colors.white,
                    ),
                    child: const Text('Try Again'),
                  ),
                  const SizedBox(height: 10),
                  OutlinedButton(
                    onPressed: onClose,
                    style: OutlinedButton.styleFrom(
                      foregroundColor: const Color(0xFFFF6B00),
                      side: const BorderSide(color: Color(0xFFFF6B00), width: 1.5),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                      padding: const EdgeInsets.symmetric(vertical: 14),
                    ),
                    child: const Text('Back to Website'),
                  ),
                  const SizedBox(height: 6),
                  TextButton(
                    onPressed: onHome,
                    style: TextButton.styleFrom(
                      foregroundColor: isDark ? Colors.grey[400] : const Color(0xFF64748B),
                    ),
                    child: const Text('Open App Home'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
