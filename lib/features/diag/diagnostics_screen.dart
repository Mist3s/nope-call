import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../data/nope_call_api.g.dart';
import '../../data/repository.dart';
import '../../widgets/async_view.dart';
import 'test_run_sheet.dart';

/// Диагностика (ТЗ §9.7).
///
/// Экран обязательный, а не «если останется время»: блокировка звонков отказывает **тихо** —
/// роль отозвали, разрешение отобрали, прошивка убила процесс, — и без диагностики единственным
/// способом поддержки становится подключение к телефону пользователя.
class DiagnosticsScreen extends StatefulWidget {
  const DiagnosticsScreen({super.key});

  @override
  State<DiagnosticsScreen> createState() => _DiagnosticsScreenState();
}

class _DiagnosticsScreenState extends State<DiagnosticsScreen> {
  final _repo = PlatformRepository();
  Loadable<(DiagnosticsDto, SetupStatus)> _state = const Loadable(
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
      final report = await _repo.diagnostics();
      final status = await _repo.status();
      if (!mounted) return;
      setState(() => _state = Loadable(data: (report, status)));
    } catch (e) {
      if (!mounted) return;
      setState(() => _state = Loadable(data: _state.data, error: e));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Диагностика'),
        actions: [
          IconButton(
            tooltip: 'Скопировать отчёт',
            icon: const Icon(Icons.copy_all_outlined),
            onPressed: () {
              final report = _state.data?.$1;
              if (report == null) return;
              Clipboard.setData(ClipboardData(text: report.reportText));
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(
                  content: Text('Отчёт скопирован. Номера в нём замаскированы'),
                ),
              );
            },
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => showModalBottomSheet<void>(
          context: context,
          isScrollControlled: true,
          builder: (_) => const TestRunSheet(),
        ),
        icon: const Icon(Icons.play_arrow),
        label: const Text('Тестовый прогон'),
      ),
      body: LoadableView<(DiagnosticsDto, SetupStatus)>(
        state: _state,
        onRetry: _load,
        builder: (context, data) {
          final (report, status) = data;
          return RefreshIndicator(
            onRefresh: _load,
            child: ListView(
              padding: const EdgeInsets.only(bottom: 96),
              children: [
                _StateCard(status: status, report: report, repo: _repo),
                if (report.signatureLooksUnavailable)
                  _SignatureWarning(report: report),
                _NamesCard(report: report),
                _SpeedCard(report: report),
                _SnapshotCard(report: report),
                if (report.ruleErrors.isNotEmpty)
                  _RuleErrorsCard(report: report),
                _EventsCard(report: report),
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text(
                    report.device,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _StateCard extends StatelessWidget {
  const _StateCard({
    required this.status,
    required this.report,
    required this.repo,
  });

  final SetupStatus status;
  final DiagnosticsDto report;
  final PlatformRepository repo;

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
            Text('Состояние', style: theme.textTheme.titleMedium),
            const SizedBox(height: 12),
            _Check('Роль средства проверки звонков', status.hasRole),
            _Check('Блокировка включена', status.blockingEnabled),
            _Check('Правила есть', status.enabledRuleCount > 0),
            _Check('Доступ к журналу звонков', status.hasCallLog),
            _Check('Доступ к контактам', status.hasContacts),
            _Check('Уведомления разрешены', status.hasNotifications),
            _Check(
              'Без ограничений энергосбережения',
              report.batteryUnrestricted,
            ),
            const SizedBox(height: 8),
            _Row(
              'Последняя проверка звонка',
              status.lastScreeningAt == null
                  ? 'не было ни одной'
                  : formatTime(status.lastScreeningAt!),
            ),
            _Row('Проверок за 7 суток', '${report.checksLast7Days}'),
            if (report.batteryUnrestricted == false) ...[
              const SizedBox(height: 12),
              Text(
                'Прошивка вправе выгружать приложение из памяти. Сервис проверки от этого '
                'не перестаёт работать — система поднимает его на звонок, — но холодный старт '
                'становится дороже.',
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: 8),
              OutlinedButton(
                onPressed: repo.openBatterySettings,
                child: const Text('Настройки энергосбережения'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Предупреждение до того, как пользователь построит десяток правил по названию (ТЗ §9.7).
class _SignatureWarning extends StatelessWidget {
  const _SignatureWarning({required this.report});

  final DiagnosticsDto report;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      color: theme.colorScheme.errorContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(
              Icons.warning_amber_outlined,
              color: theme.colorScheme.onErrorContainer,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                'Ни у одного из последних ${report.checkedLast100} звонков не было '
                'операторской подписи. На этом устройстве или в этой сети подпись не приходит — '
                'правила по названию срабатывать не будут. Стройте правила по номеру.',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onErrorContainer,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _NamesCard extends StatelessWidget {
  const _NamesCard({required this.report});

  final DiagnosticsDto report;

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
            Text('Названия звонящих', style: theme.textTheme.titleMedium),
            const SizedBox(height: 12),
            _Row(
              'Подпись была',
              '${report.withSignatureLast100} из ${report.checkedLast100}',
            ),
            for (final b in report.nameSources.whereType<BucketDto>())
              _Row(Labels.nameSourceLabel(b.label), '${b.total}'),
            for (final b in report.volte.whereType<BucketDto>())
              _Row(
                b.label == 'VOLTE'
                    ? 'VoLTE есть'
                    : (b.label == 'NO_VOLTE'
                          ? 'VoLTE нет'
                          : 'VoLTE неизвестно'),
                '${b.total}',
              ),
          ],
        ),
      ),
    );
  }
}

class _SpeedCard extends StatelessWidget {
  const _SpeedCard({required this.report});

  final DiagnosticsDto report;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Задержка решения за 7 суток',
              style: theme.textTheme.titleMedium,
            ),
            const SizedBox(height: 12),
            _Row('Медиана', '${report.latencyP50} мс'),
            _Row('95-й процентиль', '${report.latencyP95} мс'),
            _Row('Максимум', '${report.latencyMax} мс'),
            // Показывается всегда, а не только при ненулевом значении: «0» здесь —
            // это утверждение «ни одна проверка не потерялась», и его надо видеть.
            _Row('Потеряно записей', '${report.droppedPendingEvents}'),
            if (report.degradedCounts.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text('Причины решений', style: theme.textTheme.labelLarge),
              for (final b in report.degradedCounts.whereType<BucketDto>())
                _Row(Labels.reason(b.label), '${b.total}'),
            ],
          ],
        ),
      ),
    );
  }
}

class _SnapshotCard extends StatelessWidget {
  const _SnapshotCard({required this.report});

  final DiagnosticsDto report;

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
            Text('Снимок правил', style: theme.textTheme.titleMedium),
            const SizedBox(height: 4),
            Text(
              'Именно по нему принимается решение во время звонка — база при этом '
              'не открывается.',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 12),
            _Row('Правил в снимке', '${report.snapshotRuleCount ?? '—'}'),
            _Row('Версия формата', '${report.snapshotFormatVersion ?? '—'}'),
            _Row('Версия канонизации', '${report.snapshotCanonVersion ?? '—'}'),
            _Row(
              'Собран',
              report.snapshotBuiltAt == null
                  ? '—'
                  : formatTime(report.snapshotBuiltAt!),
            ),
            if (report.snapshotError != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  report.snapshotError!,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.error,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _RuleErrorsCard extends StatelessWidget {
  const _RuleErrorsCard({required this.report});

  final DiagnosticsDto report;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Правила с ошибками', style: theme.textTheme.titleMedium),
            const SizedBox(height: 4),
            Text(
              'Сбойное правило пропускается, а не блокирует звонок. После трёх подряд '
              'ошибок оно выключается автоматически.',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 12),
            for (final e in report.ruleErrors.whereType<RuleErrorDto>())
              _Row('${e.title} (${e.errorCount})', e.lastError ?? '—'),
          ],
        ),
      ),
    );
  }
}

class _EventsCard extends StatelessWidget {
  const _EventsCard({required this.report});

  final DiagnosticsDto report;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final events = report.lastEvents.whereType<EventLineDto>().toList();
    if (events.isEmpty) return const SizedBox.shrink();

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Text(
              'Последние проверки',
              style: theme.textTheme.titleMedium,
            ),
          ),
          for (final e in events)
            ListTile(
              dense: true,
              title: Text(
                e.number.isEmpty ? 'скрытый номер' : e.number,
                style: theme.textTheme.bodyMedium,
              ),
              subtitle: Text(
                '${formatTime(e.occurredAt)} · ${Labels.action(e.action)} · '
                '${Labels.reason(e.reason)}',
                style: theme.textTheme.bodySmall,
              ),
              trailing: Text(
                '${e.latencyMs} мс${e.coldStart == true ? ' ❄' : ''}',
                style: theme.textTheme.bodySmall?.copyWith(
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _Check extends StatelessWidget {
  const _Check(this.label, this.value);

  final String label;

  /// `null` — определить не удалось. Показывать это как «нет» было бы неправдой.
  final bool? value;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final (icon, color) = switch (value) {
      true => (Icons.check_circle, scheme.primary),
      false => (Icons.cancel_outlined, scheme.error),
      null => (Icons.help_outline, scheme.outline),
    };
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        children: [
          Icon(icon, size: 18, color: color),
          const SizedBox(width: 8),
          Expanded(
            child: Text(label, style: Theme.of(context).textTheme.bodyMedium),
          ),
        ],
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row(this.label, this.value);

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
          // Expanded, а не Flexible: у Flexible бокс сжимается до содержимого, и `textAlign.end`
          // выравнивает текст внутри уже сжатого бокса — то есть по середине строки. Колонка
          // чисел при этом шатается от строки к строке.
          Expanded(
            child: Text(
              value,
              textAlign: TextAlign.end,
              style: theme.textTheme.bodyMedium?.copyWith(
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
