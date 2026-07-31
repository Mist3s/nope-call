import 'package:flutter/material.dart';

/// Иконка и цвет типа записи журнала (ТЗ §7.4).
///
/// Вынесено из списка в отдельный файл, потому что то же самое нужно карточке звонка.
/// Пока карточка выбирала иконку сама, исходящий звонок в списке показывался стрелкой
/// «наружу», а в карточке того же звонка — стрелкой «внутрь».
class CallVisual {
  const CallVisual(this.icon, this.color);

  final IconData icon;
  final Color color;

  /// Красный здесь означает ровно одно: «заблокировали мы». Пропущенный звонок — не ошибка,
  /// и подсвечивать его как ошибку значит уводить взгляд не туда.
  static CallVisual of(String kind, ColorScheme scheme) => switch (kind) {
    'BLOCKED_BY_APP' => CallVisual(Icons.block, scheme.error),
    'BLOCKED_EXTERNAL' => CallVisual(Icons.shield_outlined, scheme.outline),
    'SILENCED' => CallVisual(Icons.notifications_off_outlined, scheme.tertiary),
    'MISSED' => CallVisual(Icons.call_missed, scheme.outline),
    'REJECTED_BY_USER' => CallVisual(Icons.call_end_outlined, scheme.outline),
    'OUTGOING' => CallVisual(Icons.call_made, scheme.primary),
    'VOICEMAIL' => CallVisual(Icons.voicemail_outlined, scheme.outline),
    'INCOMING_ANSWERED' => CallVisual(Icons.call_received, scheme.primary),
    _ => CallVisual(Icons.check_circle_outline, scheme.primary),
  };
}
