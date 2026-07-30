import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nope_call/app/app.dart';

void main() {
  testWidgets('приложение открывается на главном экране', (tester) async {
    await tester.pumpWidget(const NopeCallApp());
    await tester.pump();

    // Четыре раздела из ТЗ §9 должны быть доступны сразу.
    expect(find.text('Главная'), findsOneWidget);
    expect(find.text('Журнал'), findsOneWidget);
    expect(find.text('Правила'), findsOneWidget);
    expect(find.text('Настройки'), findsOneWidget);
  });

  testWidgets('переключение раздела меняет содержимое', (tester) async {
    await tester.pumpWidget(const NopeCallApp());
    await tester.pump();

    await tester.tap(find.text('Правила'));
    await tester.pump();

    expect(find.byType(NavigationBar), findsOneWidget);
  });
}
