import 'package:flutter_test/flutter_test.dart';
import 'package:unified_driver/main.dart';

void main() {
  testWidgets('App loads with Foxwel branding', (tester) async {
    await tester.pumpWidget(const FoxwelPrinterApp());
    await tester.pump();
    expect(find.textContaining('Foxwel Printer Plugin'), findsWidgets);
  });
}
