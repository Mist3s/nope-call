import 'package:flutter/material.dart';

import '../../data/nope_call_api.g.dart';
import '../../data/repository.dart';
import '../../widgets/async_view.dart';

/// Журнал приложения: хранение и очистка (ТЗ §7.6).
///
/// Отдельный экран, а не строки в общих настройках, по той же причине, что у логов
/// наблюдения: у журнала своё содержимое, свои сроки и своя кнопка удаления, и рядом
/// с параметрами логирования они читались бы как одно и то же. Здесь же сказано, из чего
/// журнал состоит, — иначе непонятно, что именно удаляет кнопка внизу.
class JournalSettingsScreen extends StatefulWidget {
  const JournalSettingsScreen({super.key});

  @override
  State<JournalSettingsScreen> createState() => _JournalSettingsScreenState();
}

class _JournalSettingsScreenState extends State<JournalSettingsScreen> {
  final _repo = PlatformRepository();

  Loadable<(Map<String, String>, SummaryDto)> _state = const Loadable(
    loading: true,
  );

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    // `_load` вызывается не только из `initState`, но и после `await` — из обновления
    // и из применения настройки. Ведущий `setState` без этой проверки бросал бы ассертом
    // на экране, который пользователь успел закрыть.
    if (!mounted) return;
    setState(() => _state = Loadable(data: _state.data, loading: true));
    try {
      final settings = await _repo.settings();
      final summary = await _repo.summary();
      if (!mounted) return;
      setState(() => _state = Loadable(data: (settings, summary)));
    } catch (e) {
      if (!mounted) return;
      setState(() => _state = Loadable(data: _state.data, error: e));
    }
  }

  /// Значение применяется на экране сразу, платформа догоняет: иначе поле возвращалось бы
  /// к прежнему значению на время записи.
  Future<void> _put(String key, String value) async {
    final current = _state.data;
    if (current != null) {
      setState(() {
        _state = Loadable(
          data: ({...current.$1, key: value}, current.$2),
          loading: true,
        );
      });
    }
    await _repo.putSetting(key, value);
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Журнал')),
      body: LoadableView<(Map<String, String>, SummaryDto)>(
        state: _state,
        onRetry: _load,
        builder: (context, data) {
          final (settings, summary) = data;
          final days = settings['journal_retention_days'] ?? '365';
          final records = settings['journal_retention_records'] ?? '20000';

          return RefreshIndicator(
            onRefresh: _load,
            child: ListView(
              padding: const EdgeInsets.only(bottom: 32),
              children: [
                _WhatCard(summary: summary),
                const Divider(height: 32),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                  child: Text(
                    'Хранение',
                    style: theme.textTheme.titleSmall?.copyWith(
                      color: theme.colorScheme.primary,
                    ),
                  ),
                ),
                _RetentionTile(
                  title: 'Хранить, суток',
                  subtitle: '0 — без ограничения по сроку',
                  value: days,
                  onChanged: (v) => _put('journal_retention_days', v),
                ),
                _RetentionTile(
                  title: 'Хранить записей, не больше',
                  subtitle: '0 — без ограничения по числу',
                  value: records,
                  onChanged: (v) => _put('journal_retention_records', v),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                  child: Text(
                    'Удаляется то, что старше срока или не попало в число последних — '
                    'что раньше. Оба ограничения нужны вместе: срок ничего не значит '
                    'на телефоне, куда звонят сто раз в день, а число оставляет годовой '
                    'хвост на телефоне, которым почти не пользуются.',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ),
                const Divider(height: 32),
                _ExportCsvTile(repo: _repo),
                _ClearJournalTile(repo: _repo, onCleared: _load),
              ],
            ),
          );
        },
      ),
    );
  }
}

/// Из чего состоит журнал. Без этого непонятно, что именно удалит кнопка очистки —
/// и почему в журнале видно звонки, которых приложение не проверяло.
class _WhatCard extends StatelessWidget {
  const _WhatCard({required this.summary});

  final SummaryDto summary;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: const EdgeInsets.all(16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.history_outlined, color: theme.colorScheme.primary),
                const SizedBox(width: 8),
                Text('Что в журнале', style: theme.textTheme.titleMedium),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              'Два слоя. Первый — проверки самого приложения: они пишутся всегда, '
              'без разрешений. Второй — копия системного журнала вызовов Android: '
              'из неё берутся исход звонка, длительность и исходящие.',
              style: theme.textTheme.bodyMedium,
            ),
            const SizedBox(height: 12),
            _Line('Проверок приложения', '${summary.totalEvents}'),
            _Line('Записей из системного журнала', '${summary.mirrorRecords}'),
            _Line(
              'Последняя проверка',
              summary.lastEventAt == null
                  ? 'не было ни одной'
                  : formatTime(summary.lastEventAt!),
            ),
            if (summary.mirrorRecords == 0) ...[
              const SizedBox(height: 12),
              Text(
                'Копия системного журнала пуста. Скорее всего не выдан доступ к журналу '
                'звонков — тогда исход звонка и длительность приложению неизвестны.',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Числовая настройка ретеншена (ТЗ §7.6). Значение применяется по завершении ввода,
/// а не на каждую цифру: иначе «20» на миг означало бы «хранить две записи».
class _RetentionTile extends StatelessWidget {
  const _RetentionTile({
    required this.title,
    required this.subtitle,
    required this.value,
    required this.onChanged,
  });

  final String title;
  final String subtitle;
  final String value;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      title: Text(title),
      subtitle: Text(subtitle),
      trailing: SizedBox(
        width: 96,
        child: TextFormField(
          key: ValueKey(value),
          initialValue: value,
          keyboardType: TextInputType.number,
          textAlign: TextAlign.end,
          decoration: const InputDecoration(isDense: true),
          onFieldSubmitted: (raw) {
            final parsed = int.tryParse(raw.trim());
            if (parsed != null && parsed >= 0) onChanged(parsed.toString());
          },
        ),
      ),
    );
  }
}

/// Выгрузка журнала в CSV (ТЗ §7.6).
///
/// UTF-8 с BOM и разделитель «;» — так Excel открывает файл без вопросов про кодировку
/// и без склеивания всех колонок в одну. Куда отправить файл, выбирает пользователь.
class _ExportCsvTile extends StatefulWidget {
  const _ExportCsvTile({required this.repo});

  final PlatformRepository repo;

  @override
  State<_ExportCsvTile> createState() => _ExportCsvTileState();
}

class _ExportCsvTileState extends State<_ExportCsvTile> {
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: const Icon(Icons.table_view_outlined),
      title: const Text('Выгрузить в CSV'),
      subtitle: const Text(
        'Открывается в Excel и в таблицах без настройки кодировки',
      ),
      trailing: _busy
          ? const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : null,
      onTap: _busy ? null : _export,
    );
  }

  Future<void> _export() async {
    setState(() => _busy = true);
    try {
      final rows = await widget.repo.exportJournalCsv();
      if (!mounted || rows > 0) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Выгружать нечего: журнал пуст')),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}

/// Очистка журнала (ТЗ §7.6): с подтверждением и с прямым указанием, что системный
/// журнал вызовов Android не затрагивается — иначе пользователь ждёт другого.
class _ClearJournalTile extends StatelessWidget {
  const _ClearJournalTile({required this.repo, required this.onCleared});

  final PlatformRepository repo;
  final VoidCallback onCleared;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ListTile(
      leading: Icon(Icons.delete_sweep_outlined, color: scheme.error),
      title: Text('Очистить журнал', style: TextStyle(color: scheme.error)),
      subtitle: const Text('Системный журнал вызовов Android не изменится'),
      onTap: () => _confirm(context),
    );
  }

  Future<void> _confirm(BuildContext context) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Очистить журнал приложения?'),
        content: const Text(
          'Будут удалены записи проверок и копия системного журнала. Сам журнал '
          'вызовов Android останется как есть. Отменить будет нельзя.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Отмена'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Очистить'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    final removed = await repo.clearJournal();
    if (!context.mounted) return;
    onCleared();
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text('Удалено записей: $removed')));
  }
}

/// Строка «подпись — значение» с монотипными цифрами: колонка чисел, которая шатается,
/// читается как небрежность.
class _Line extends StatelessWidget {
  const _Line(this.label, this.value);

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Text(
              label,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          const SizedBox(width: 12),
          Text(
            value,
            style: theme.textTheme.bodyMedium?.copyWith(
              fontFeatures: const [FontFeature.tabularFigures()],
            ),
          ),
        ],
      ),
    );
  }
}
