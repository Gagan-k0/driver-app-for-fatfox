import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:image/image.dart' as img;

import '../models/printer_models.dart';
import '../services/usb_printer_bridge.dart';
import '../services/zpl_builder.dart';


class PrintPreviewScreen extends StatefulWidget {
  const PrintPreviewScreen({
    super.key,
    required this.path,
    required this.name,
    required this.settings,
    this.returnToCallerOnDone = false,
    this.onFinished,
  });

  final String path;
  final String name;
  final LabelSettings settings;
  final bool returnToCallerOnDone;
  final Future<void> Function()? onFinished;

  @override
  State<PrintPreviewScreen> createState() => _PrintPreviewScreenState();
}

class _PrintPreviewScreenState extends State<PrintPreviewScreen> {
  final _bridge = UsbPrinterBridge.instance;
  Uint8List? _preview;
  String? _error;
  bool _loading = true;
  bool _printing = false;
  late LabelSettings _settings;
  String? _textContent;

  @override
  void initState() {
    super.initState();
    _settings = widget.settings;
    _prepare();
  }

  bool get _isPdf {
    final lower = widget.path.toLowerCase();
    return lower.endsWith('.pdf') || lower.contains('.pdf');
  }

  bool get _isText =>
      widget.path.toLowerCase().endsWith('.txt') ||
      widget.path.toLowerCase().endsWith('.csv');



  Future<void> _prepare() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      // Both "bill" and "label" are printed on 80mm thermal printers.
      final previewSettings = _settings;

      if (_isText) {
        _textContent = await File(widget.path).readAsString();
        final zplImage = await _textAsPreviewImage(_textContent!);
        _preview = await ZplBuilder.previewPng(zplImage);
      } else if (_isPdf) {
        try {
          final previewPath = await _bridge.renderPdfPreview(
            widget.path,
            previewSettings,
          );
          if (previewPath != null && File(previewPath).existsSync()) {
            _preview = await File(previewPath).readAsBytes();
          } else {
            final raster = await ZplBuilder.rasterizePdfPage(
              widget.path,
              previewSettings,
            );
            _preview = await ZplBuilder.previewPng(raster);
          }
        } catch (pdfErr) {
          if (File(widget.path).existsSync()) {
            try {
              final raster = await ZplBuilder.loadImageFile(
                widget.path,
                previewSettings,
              );
              _preview = await ZplBuilder.previewPng(raster);
            } catch (_) {
              _error = 'Unable to parse print document. Please check the website print format.';
            }
          } else {
            _error = 'Print document file missing. Please try printing again from website.';
          }
        }
      } else {
        final raster = await ZplBuilder.loadImageFile(
          widget.path,
          previewSettings,
        );
        _preview = await ZplBuilder.previewPng(raster);
      }
    } catch (e) {
      _error = e.toString();
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<img.Image> _textAsPreviewImage(String text) async {
    final w = _settings.widthDots;
    final h = _settings.heightDots;
    final image = img.Image(width: w, height: h);
    img.fill(image, color: img.ColorRgb8(255, 255, 255));
    img.drawString(
      image,
      'Preview (receipt)',
      font: img.arial24,
      x: 24,
      y: 24,
      color: img.ColorRgb8(0, 0, 0),
    );
    final maxChars = (w / 8).floor().clamp(40, 140);
    final maxLines = (h / 28).floor().clamp(12, 40);
    var y = 70;
    for (final line in text.split('\n').take(maxLines)) {
      img.drawString(
        image,
        line.length > maxChars ? '${line.substring(0, maxChars)}…' : line,
        font: img.arial14,
        x: 24,
        y: y,
        color: img.ColorRgb8(20, 20, 20),
      );
      y += 28;
    }
    return image;
  }

  Future<void> _print() async {
    setState(() => _printing = true);
    try {
      final settings = _settings.syncedLabelDots();
      // Persist connection settings only. Website chooses the job type via `format`.
      final persist = settings.copyWith(
        format: PrintFormat.kot,
        widthDots: LabelSettings.receipt80mmWidthDots,
        heightDots: LabelSettings.receipt80mmHeightDots,
      );
      await _bridge.saveLabelSettings(persist);

      Map<dynamic, dynamic> result;

      if (_isText) {
        result = await _bridge.printText(
          _textContent ?? await File(widget.path).readAsString(),
          settings,
        );
      } else if (_isPdf) {
        result = await _bridge.printPdfFile(widget.path, settings);
      } else {
        result = await _bridge.printImageFile(widget.path, settings);
      }

      if (!mounted) return;
      final ok = result['ok'] == true;
      if (ok && widget.returnToCallerOnDone) {
        await _finish();
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            ok
                ? 'Sent to printer (${result['bytesWritten'] ?? result['protocol'] ?? 'ok'})'
                : (result['error']?.toString() ??
                      (result['pendingPermission'] == true
                          ? 'Allow USB permission and try again'
                          : 'Print failed')),
          ),
        ),
      );
      if (ok && Navigator.of(context).canPop()) {
        Navigator.of(context).pop();
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Print error: $e')));
    } finally {
      if (mounted) setState(() => _printing = false);
    }
  }

  Future<void> _finish() async {
    if (widget.onFinished != null) {
      await widget.onFinished!();
      return;
    }
    if (widget.returnToCallerOnDone) {
      await _bridge.returnToCaller();
      return;
    }
    if (mounted && Navigator.of(context).canPop()) {
      Navigator.of(context).pop();
    }
  }

  @override
  Widget build(BuildContext context) {
    final wide = MediaQuery.sizeOf(context).width >= 900;

    return PopScope(
      canPop: !widget.returnToCallerOnDone,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop || !widget.returnToCallerOnDone) return;
        await _finish();
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(widget.name),
          leading: widget.returnToCallerOnDone
              ? IconButton(
                  icon: const Icon(Icons.close),
                  tooltip: 'Back to website',
                  onPressed: _finish,
                )
              : null,
        ),
        body: Column(
          children: [
            Expanded(
              child: _loading
                  ? const Center(child: CircularProgressIndicator())
                  : _error != null
                  ? Center(
                      child: Padding(
                        padding: const EdgeInsets.all(24),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Container(
                              padding: const EdgeInsets.all(16),
                              decoration: BoxDecoration(
                                color: Colors.red.withOpacity(0.12),
                                shape: BoxShape.circle,
                              ),
                              child: const Icon(
                                Icons.error_outline_rounded,
                                size: 42,
                                color: Colors.redAccent,
                              ),
                            ),
                            const SizedBox(height: 16),
                            Text(
                              _error!,
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            const SizedBox(height: 20),
                            ElevatedButton.icon(
                              onPressed: _prepare,
                              icon: const Icon(Icons.refresh_rounded),
                              label: const Text('Retry Loading'),
                              style: ElevatedButton.styleFrom(
                                backgroundColor: const Color(0xFFFF6B00),
                                foregroundColor: Colors.white,
                              ),
                            ),
                          ],
                        ),
                      ),
                    )
                  : _singlePreview(),
            ),
            SafeArea(
              child: Padding(
                padding: EdgeInsets.fromLTRB(
                  wide ? 32 : 18,
                  12,
                  wide ? 32 : 18,
                  20,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                      decoration: BoxDecoration(
                        color: const Color(0xFFFF6B00).withOpacity(0.1),
                        borderRadius: BorderRadius.circular(20),
                        border: Border.all(color: const Color(0xFFFFD6B8), width: 1),
                      ),
                      child: Text(
                        _settings.sizeCaption,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          color: Color(0xFFFF6B00),
                          fontWeight: FontWeight.w700,
                          fontSize: 13,
                        ),
                      ),
                    ),
                    const SizedBox(height: 12),
                    SizedBox(
                      height: 56,
                      child: Container(
                        decoration: BoxDecoration(
                          gradient: (_loading || _printing || _error != null)
                              ? null
                              : const LinearGradient(
                                  colors: [Color(0xFFFF6B00), Color(0xFFE85D00)],
                                  begin: Alignment.topLeft,
                                  end: Alignment.bottomRight,
                                ),
                          borderRadius: BorderRadius.circular(16),
                          boxShadow: (_loading || _printing || _error != null)
                              ? null
                              : [
                                  BoxShadow(
                                    color: const Color(0xFFFF6B00).withOpacity(0.35),
                                    blurRadius: 16,
                                    offset: const Offset(0, 6),
                                  ),
                                ],
                        ),
                        child: ElevatedButton.icon(
                          onPressed: (_loading || _printing || _error != null)
                              ? null
                              : _print,
                          style: ElevatedButton.styleFrom(
                            backgroundColor: Colors.transparent,
                            shadowColor: Colors.transparent,
                            foregroundColor: Colors.white,
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                          ),
                          icon: _printing
                              ? const SizedBox(
                                  width: 22,
                                  height: 22,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2.5,
                                    valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                                  ),
                                )
                              : const Icon(Icons.print_rounded, size: 24),
                          label: Text(
                            _printing ? 'Printing Document…' : 'PRINT NOW',
                            style: const TextStyle(
                              fontSize: 17,
                              fontWeight: FontWeight.w800,
                              letterSpacing: 0.5,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _singlePreview() {
    return InteractiveViewer(
      child: Center(
        child: Container(
          margin: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: Colors.white,
            border: Border.all(color: Colors.black12),
            boxShadow: const [
              BoxShadow(
                blurRadius: 12,
                color: Color(0x22000000),
                offset: Offset(0, 4),
              ),
            ],
          ),
          child: _preview == null
              ? const SizedBox.shrink()
              : Image.memory(_preview!, fit: BoxFit.contain),
        ),
      ),
    );
  }


}
