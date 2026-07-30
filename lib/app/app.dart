import 'package:flutter/material.dart';

import '../features/home/home_screen.dart';
import '../features/journal/journal_screen.dart';
import '../features/rules/rules_screen.dart';
import '../features/settings/settings_screen.dart';
import 'theme.dart';

class NopeCallApp extends StatelessWidget {
  const NopeCallApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Отбой',
      theme: buildTheme(Brightness.light),
      darkTheme: buildTheme(Brightness.dark),
      home: const RootShell(),
      debugShowCheckedModeBanner: false,
    );
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
