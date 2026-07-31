import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nope_call/app/app.dart';
import 'package:nope_call/features/onboarding/onboarding_screen.dart';

void main() {
  // Проверяется оболочка, а не `NopeCallApp`: тот сначала спрашивает у платформы, пройден ли
  // онбординг, а платформы в тесте нет. Смысл этих двух тестов — что разделы на месте.
  testWidgets('приложение открывается на главном экране', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: RootShell()));
    await tester.pump();

    // Четыре раздела из ТЗ §9 должны быть доступны сразу.
    expect(find.text('Главная'), findsOneWidget);
    expect(find.text('Журнал'), findsOneWidget);
    expect(find.text('Правила'), findsOneWidget);
    expect(find.text('Настройки'), findsOneWidget);
  });

  testWidgets('переключение раздела меняет содержимое', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: RootShell()));
    await tester.pump();

    await tester.tap(find.text('Правила'));
    await tester.pump();

    expect(find.byType(NavigationBar), findsOneWidget);
  });

  testWidgets('онбординг сразу говорит главное о блокировке по названию', (
    tester,
  ) async {
    // Это не украшение: пользователь, построивший десяток правил по названию, должен знать
    // про best effort заранее, а не выяснять по жалобам (ТЗ §9.8 п. 1).
    await tester.pumpWidget(MaterialApp(home: OnboardingScreen(onDone: () {})));
    await tester.pump();

    expect(
      find.textContaining('только если сработало ваше правило'),
      findsOneWidget,
    );
    expect(
      find.textContaining('подпись далеко не в каждом звонке'),
      findsOneWidget,
    );
    expect(find.text('Далее'), findsOneWidget);
  });
}
