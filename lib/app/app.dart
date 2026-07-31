import 'package:flutter/material.dart';

import '../data/repository.dart';
import '../features/home/home_screen.dart';
import '../features/journal/journal_screen.dart';
import '../features/onboarding/onboarding_screen.dart';
import '../features/rules/rules_screen.dart';
import '../features/settings/settings_screen.dart';
import '../widgets/async_view.dart';
import 'theme.dart';

class NopeCallApp extends StatelessWidget {
  const NopeCallApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Отбой',
      theme: buildTheme(Brightness.light),
      darkTheme: buildTheme(Brightness.dark),
      home: const AppRoot(),
      debugShowCheckedModeBanner: false,
    );
  }
}

/// Первый запуск ведёт в онбординг, дальше — сразу в приложение (ТЗ §9.8).
///
/// Флаг живёт в настройках на стороне Kotlin, а не в `SharedPreferences` из Dart: настройки
/// у приложения одни, и вторая точка хранения состояния тут же начала бы расходиться с первой.
class AppRoot extends StatefulWidget {
  const AppRoot({super.key});

  @override
  State<AppRoot> createState() => _AppRootState();
}

class _AppRootState extends State<AppRoot> {
  final _repo = PlatformRepository();
  Loadable<bool> _needsOnboarding = const Loadable(loading: true);

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final settings = await _repo.settings();
      if (!mounted) return;
      setState(
        () => _needsOnboarding = Loadable(
          data: settings['onboarding_done'] != 'true',
        ),
      );
    } catch (_) {
      // Платформенная часть недоступна — показываем приложение, а не запираем пользователя
      // в онбординге, который тоже не сможет ничего сделать.
      if (mounted) {
        setState(() {
          _needsOnboarding = const Loadable(data: false);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final needs = _needsOnboarding.data;
    if (needs == null) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (needs) {
      return OnboardingScreen(
        onDone: () =>
            setState(() => _needsOnboarding = const Loadable(data: false)),
      );
    }
    return const RootShell();
  }
}

/// Четыре раздела: главный, журнал, правила, настройки (ТЗ §9).
class RootShell extends StatefulWidget {
  const RootShell({super.key});

  @override
  State<RootShell> createState() => _RootShellState();
}

class _RootShellState extends State<RootShell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    const screens = [
      HomeScreen(),
      JournalScreen(),
      RulesScreen(),
      SettingsScreen(),
    ];
    return Scaffold(
      body: screens[_index],
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() {
          _index = i;
        }),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.shield_outlined),
            selectedIcon: Icon(Icons.shield),
            label: 'Главная',
          ),
          NavigationDestination(
            icon: Icon(Icons.history_outlined),
            selectedIcon: Icon(Icons.history),
            label: 'Журнал',
          ),
          NavigationDestination(
            icon: Icon(Icons.rule_outlined),
            selectedIcon: Icon(Icons.rule),
            label: 'Правила',
          ),
          NavigationDestination(
            icon: Icon(Icons.settings_outlined),
            selectedIcon: Icon(Icons.settings),
            label: 'Настройки',
          ),
        ],
      ),
    );
  }
}
