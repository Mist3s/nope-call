import 'package:flutter/material.dart';

import '../data/nope_call_api.g.dart';

/// Пустое состояние с объяснением, а не с одной иконкой.
class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    required this.icon,
    required this.title,
    this.description,
    this.action,
  });

  final IconData icon;
  final String title;
  final String? description;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 48, color: scheme.outline),
            const SizedBox(height: 16),
            Text(
              title,
              style: Theme.of(context).textTheme.titleMedium,
              textAlign: TextAlign.center,
            ),
            if (description != null) ...[
              const SizedBox(height: 8),
              Text(
                description!,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
            ],
            if (action != null) ...[const SizedBox(height: 20), action!],
          ],
        ),
      ),
    );
  }
}

/// Русская форма числительного: 1 запись, 2 записи, 5 записей.
///
/// Нужна потому, что «уйдёт 2 событий» выдаёт машинный перевод, а не приложение, которое
/// кто-то делал руками. Правило то же, что в CLDR: 11–14 всегда множественная форма,
/// поэтому «21 запись», но «11 записей».
String plural(int count, String one, String few, String many) {
  final mod100 = count.abs() % 100;
  final mod10 = count.abs() % 10;
  if (mod100 >= 11 && mod100 <= 14) return many;
  if (mod10 == 1) return one;
  if (mod10 >= 2 && mod10 <= 4) return few;
  return many;
}

/// Текст предпросмотра правила (ТЗ §18 п. 16).
///
/// Три величины, и вторые две важнее первой: журнал говорит «столько таких звонков уже было»,
/// а «разрешённых» и «контактов» отвечают на другой вопрос — сколько своих правило отрежет.
/// Номер, по которому ещё не звонили, в журнале отсутствует, но в книге есть — и именно он
/// делает жалобу «заблокировали врача» возможной.
String previewText(PreviewDto preview) {
  final parts = <String>[];
  if (preview.count == 0) {
    parts.add('В журнале таких звонков нет');
  } else {
    parts.add(
      'Подходит под ${preview.truncated ? '≥ ' : ''}${preview.count} '
      '${plural(preview.count, 'запись', 'записи', 'записей')} журнала',
    );
  }

  final contacts = preview.contactsCovered;
  if (contacts == null) {
    parts.add('контакты не проверены: нет доступа к телефонной книге');
  } else if (contacts > 0) {
    parts.add(
      'зацепит ${preview.contactsTruncated ? '≥ ' : ''}$contacts '
      '${plural(contacts, 'контакт', 'контакта', 'контактов')}',
    );
  }

  final allowed = preview.allowRulesCovered ?? 0;
  if (allowed > 0) {
    parts.add(
      'перекроет $allowed ${plural(allowed, 'разрешающее', 'разрешающих', 'разрешающих')} '
      '${plural(allowed, 'правило', 'правила', 'правил')}',
    );
  }
  return parts.join(' · ');
}

String formatTime(int millis) {
  final d = DateTime.fromMillisecondsSinceEpoch(millis);
  final now = DateTime.now();
  String two(int v) => v.toString().padLeft(2, '0');
  final time = '${two(d.hour)}:${two(d.minute)}';
  if (d.year == now.year && d.month == now.month && d.day == now.day) {
    return 'сегодня $time';
  }
  return '${two(d.day)}.${two(d.month)}.${d.year} $time';
}

/// Состояние загружаемых данных, которое **не теряет прежние данные** при обновлении.
///
/// `FutureBuilder` для этого не годится: замена Future роняет его в состояние ожидания, экран
/// показывает спиннер поверх уже загруженного списка и мигает. Заметно это на каждом действии,
/// меняющем состояние элемента — переключить правило, перетащить, изменить настройку.
class Loadable<T> {
  const Loadable({this.data, this.error, this.loading = false});

  final T? data;
  final Object? error;

  /// Идёт загрузка. Если при этом [data] не пусто — обновление тихое, спиннер не нужен.
  final bool loading;

  bool get hasData => data != null;
  bool get isFirstLoad => data == null && error == null;
}

/// Рисует данные, как только они есть, и показывает спиннер только на первой загрузке.
class LoadableView<T> extends StatelessWidget {
  const LoadableView({
    super.key,
    required this.state,
    required this.builder,
    this.onRetry,
  });

  final Loadable<T> state;
  final Widget Function(BuildContext context, T data) builder;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    final data = state.data;
    if (data != null) {
      // Данные есть — показываем их даже во время обновления. Тонкая полоса сверху даёт
      // понять, что идёт загрузка, не перерисовывая содержимое.
      return Column(
        children: [
          SizedBox(
            height: 2,
            child: state.loading
                ? const LinearProgressIndicator(minHeight: 2)
                : const SizedBox.shrink(),
          ),
          Expanded(child: builder(context, data)),
        ],
      );
    }
    if (state.error != null) {
      return _ErrorBody(error: state.error!, onRetry: onRetry);
    }
    return const Center(child: CircularProgressIndicator());
  }
}

class _ErrorBody extends StatelessWidget {
  const _ErrorBody({required this.error, this.onRetry});

  final Object error;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.error_outline,
              size: 40,
              color: Theme.of(context).colorScheme.error,
            ),
            const SizedBox(height: 12),
            const Text(
              'Не удалось получить данные от системной части приложения',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              '$error',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodySmall,
            ),
            if (onRetry != null) ...[
              const SizedBox(height: 16),
              OutlinedButton(
                onPressed: onRetry,
                child: const Text('Повторить'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
