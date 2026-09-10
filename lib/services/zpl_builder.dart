import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:image/image.dart' as img;
import 'package:pdfx/pdfx.dart';

import '../models/printer_models.dart';

/// Builds ZPL/BPLZ payloads on the Dart side for PDF/text jobs.
class ZplBuilder {
  static String testLabel(LabelSettings s, {String title = 'FatFox Print'}) {
    final subtitle = '${s.format.displayName} · Thermal Receipt Ready';
    final sizeLine = '203 DPI  |  ESC/POS / ZPL  |  80mm Thermal Receipt';
    return '''
^XA
^CI28
^PW${s.widthDots}
^LL${s.heightDots}
^LH0,0
^FO40,40^A0N,48,48^FD$title^FS
^FO40,110^A0N,28,28^FD$subtitle^FS
^FO40,160^A0N,24,24^FD$sizeLine^FS
^FO40,220^GB${s.widthDots - 80},2,2^FS
^FO40,260^BY2^BCN,80,Y,N,N^FDFOXWEL-PRINT^FS
^FO40,370^A0N,22,22^FDPrintFox^FS
^XZ
''';
  }

  static String fromText(String text, LabelSettings s) {
    final maxChars = (s.widthDots / 12).floor().clamp(40, 120);
    final maxLines = (s.heightDots / 36).floor().clamp(12, 40);
    final lines = text.split('\n').take(maxLines);
    final buf = StringBuffer()
      ..writeln('^XA')
      ..writeln('^CI28')
      ..writeln('^PW${s.widthDots}')
      ..writeln('^LL${s.heightDots}')
      ..writeln('^LH0,0');
    var y = 30;
    for (final line in lines) {
      final safe = line.replaceAll('^', ' ').replaceAll('~', ' ');
      final clipped =
          safe.length > maxChars ? safe.substring(0, maxChars) : safe;
      buf.writeln('^FO30,$y^A0N,28,28^FD$clipped^FS');
      y += 36;
    }
    buf.writeln('^XZ');
    return buf.toString();
  }

  static String fromMonoImage(img.Image mono, LabelSettings s) {
    final width = mono.width;
    final height = mono.height;
    final bytesPerRow = (width + 7) ~/ 8;
    final packed = Uint8List(bytesPerRow * height);

    for (var y = 0; y < height; y++) {
      for (var x = 0; x < width; x++) {
        final pixel = mono.getPixel(x, y);
        final lum = img.getLuminance(pixel);
        if (lum < s.threshold) {
          final idx = y * bytesPerRow + (x >> 3);
          packed[idx] = packed[idx] | (0x80 >> (x & 7));
        }
      }
    }

    final hex = StringBuffer();
    for (final b in packed) {
      hex.write(b.toRadixString(16).padLeft(2, '0').toUpperCase());
    }

    return '''
^XA
^CI28
^PW${s.widthDots}
^LL${s.heightDots}
^LH0,0
^FO0,0^GFA,${packed.length},${packed.length},$bytesPerRow,${hex.toString()}^FS
^XZ
''';
  }

  static Future<img.Image> rasterizePdfPage(
    String pdfPath,
    LabelSettings s,
  ) async {
    final doc = await PdfDocument.openFile(pdfPath);
    try {
      final page = await doc.getPage(1);
      try {
        final pageImage = await page.render(
          width: s.widthDots.toDouble(),
          height: s.heightDots.toDouble(),
          format: PdfPageImageFormat.png,
        );
        if (pageImage == null) {
          throw StateError('Failed to render PDF page');
        }
        final decoded = img.decodeImage(pageImage.bytes);
        if (decoded == null) {
          throw StateError('Failed to decode rendered PDF');
        }
        return img.copyResize(
          decoded,
          width: s.widthDots,
          height: s.heightDots,
        );
      } finally {
        await page.close();
      }
    } finally {
      await doc.close();
    }
  }

  static Future<img.Image> loadImageFile(String path, LabelSettings s) async {
    final bytes = await File(path).readAsBytes();
    final decoded = img.decodeImage(bytes);
    if (decoded == null) throw StateError('Unsupported image: $path');
    return img.copyResize(decoded, width: s.widthDots, height: s.heightDots);
  }

  static Future<Uint8List> previewPng(img.Image image) async {
    final rgba = img.encodePng(image);
    return Uint8List.fromList(rgba);
  }

  static Future<ui.Image> decodeUiImage(Uint8List pngBytes) async {
    final codec = await ui.instantiateImageCodec(pngBytes);
    final frame = await codec.getNextFrame();
    return frame.image;
  }
}
