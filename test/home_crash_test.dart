import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:unified_driver/screens/home_screen.dart';

void main() {
  testWidgets('HomeScreen builds on phone', (tester) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(find.textContaining('Foxwel'), findsWidgets);
  });

  testWidgets('HomeScreen builds on tablet', (tester) async {
    tester.view.physicalSize = const Size(1100, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(find.text('A5 bill'), findsOneWidget);
    expect(find.text('Label sheet'), findsOneWidget);
  });
}
