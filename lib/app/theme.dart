import 'package:flutter/material.dart';

/// Палитра из branding/README.md. Светлая и тёмная темы обязательны (ТЗ §11.5).
abstract final class BrandColors {
  static const navy = Color(0xFF17214F);
  static const red = Color(0xFFF53430);
  static const purple = Color(0xFF6A69AD);
}

ThemeData buildTheme(Brightness brightness) {
  final scheme = ColorScheme.fromSeed(
    seedColor: BrandColors.navy,
    brightness: brightness,
  );
  return ThemeData(
    colorScheme: scheme,
    useMaterial3: true,
    appBarTheme: const AppBarTheme(centerTitle: false),
    cardTheme: CardThemeData(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: scheme.outlineVariant),
      ),
    ),
    listTileTheme: const ListTileThemeData(
      contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: 4),
    ),
  );
}
