import 'package:flutter/material.dart';

import '../../data/nope_call_api.g.dart';
import '../../data/repository.dart';
import '../../widgets/async_view.dart';
import '../rules/rule_editor.dart';

/// Режим наблюдения (ТЗ §7.7).
///
/// Экран отвечает на вопросы, ради которых логи и собираются, **не дожидаясь выгрузки**:
/// приходит ли операторская подпись к моменту проверки или досылается позже, в каком формате,
/// куда вендор кладёт данные, какова реальная задержка. Половина ответов видна уже здесь.
///
/// Отсюда же самый короткий путь от наблюдения к работающему правилу: тап по наблюдённой
/// подписи открывает редактор с предзаполненным правилом.
class ObserveScreen extends StatefulWidget {
  const ObserveScreen({super.key});

  @override
  State<ObserveScreen> createState() => _ObserveScreenState();
}

class _ObserveScreenState extends State<ObserveScreen> {
  final _repo = PlatformRepository();

  Loadable<(ObservationStatusDto, ObservationReportDto)> _state =
      const Loadable(loading: true);
  int _periodDays = 30;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _state = Loadable(data: _state.data, loading: true));
    try {
      final status = await _repo.observationStatus();
      final report = await _repo.observationReport(_periodDays);
      if (!mounted) return;
      setState(() => _state = Loadable(data: (status, report)));
    } catch (e) {
      if (!mounted) return;
      setState(() => _state = Loadable(data: _state.data, error: e));
    }
  }

  /// Настройка применяется на экране сразу, платформа догоняет: иначе переключатель
  /// возвращался бы назад на время записи.
  Future<void> _apply(ObservationStatusDto next) async {
    final current = _state.data;
    if (current != null) {
      setState(
        () => _state = Loadable(data: (next, current.$2), loading: true),
      );
    }
    await _repo.setObservationConfig(
      enabled: next.enabled,
      techEnabled: next.techEnabled,
      techVerbose: next.techVerbose,
      callsRetentionDays: next.callsRetentionDays,
      callsMaxMb: next.callsMaxMb,
      techRetentionDays: next.techRetentionDays,
      techMaxMb: next.techMaxMb,
      maskByDefault: next.maskByDefault,
    );
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Режим наблюдения')),
      body: LoadableView<(ObservationStatusDto, ObservationReportDto)>(
        state: _state,
        onRetry: _load,
        builder: (context, data) {
          final (status, report) = data;
          return RefreshIndicator(
            onRefresh: _load,
            child: ListView(
              padding: const EdgeInsets.only(bottom: 32),
              children: [
                _PrivacyCard(status: status),
                _MainSwitch(
                  status: status,
                  onChanged: (v) => _apply(_copy(status, enabled: v)),
                ),
                if (status.enabled) ...[
                  _VolumeCard(status: status),
                  _ExportCard(status: status, busy: _busy, onExport: _export),
                  const Divider(height: 32),
                  _PeriodSelector(
                    periodDays: _periodDays,
                    onChanged: (days) {
                      setState(() => _periodDays = days);
                      _load();
                    },
                  ),
                  _SignatureCard(report: report),
                  _SourcesCard(report: report),
                  _TimingCard(report: report),
                  if (report.extrasKeys.isNotEmpty) _ExtrasCard(report: report),
                  _SignatureSamples(
                    report: report,
                    onTap: _createRuleFromSignature,
                  ),
                ],
                const Divider(height: 32),
                _AdvancedSection(status: status, onChanged: _apply),
                _DeleteTile(status: status, onDeleted: _load),
              ],
            ),
          );
        },
      ),
    );
  }

  /// Самый короткий путь от наблюдения к работающему правилу (ТЗ §7.7.5) — и, по сути,
  /// основной сценарий первых двух недель использования.
  Future<void> _createRuleFromSignature(SignatureDto signature) async {
    final created = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) => RuleEditorScreen(
          draft: RuleDraft(
            title: signature.raw,
            targetType: 'NAME_ORG',
            matchType: 'TOKEN',
            pattern: _firstWord(signature.raw),
          ),
        ),
      ),
    );
    if (created == true && mounted) await _load();
  }

  /// Первое значимое слово подписи: правило «содержит слово» по нему и предлагается.
  static String _firstWord(String raw) {
    final head = raw.split(':').first.trim();
    final words = head
        .split(RegExp(r'\s+'))
        .where((w) => w.length > 2)
        .toList();
    // `OOO` и `PAO` — форма юрлица, а не название: правило по ним поймало бы всё подряд.
    final meaningful = words.where(
      (w) => !const {'OOO', 'PAO', 'ZAO', 'AO', 'IP'}.contains(w.toUpperCase()),
    );
    return (meaningful.isNotEmpty ? meaningful.first : words.firstOrNull) ??
        head;
  }

  Future<void> _export() async {
    final choice = await showModalBottomSheet<_ExportChoice>(
      context: context,
      isScrollControlled: true,
      builder: (_) => _ExportSheet(repo: _repo, status: _state.data!.$1),
    );
    if (choice == null || !mounted) return;

    setState(() => _busy = true);
    try {
      await _repo.shareLogs(
        fromAt: choice.fromAt,
        toAt: choice.toAt,
        mask: choice.mask,
        periodLabel: choice.label,
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  ObservationStatusDto _copy(
    ObservationStatusDto s, {
    bool? enabled,
    bool? techEnabled,
    bool? techVerbose,
    int? callsRetentionDays,
    int? callsMaxMb,
    int? techRetentionDays,
    int? techMaxMb,
    bool? maskByDefault,
  }) => ObservationStatusDto(
    enabled: enabled ?? s.enabled,
    techEnabled: techEnabled ?? s.techEnabled,
    techVerbose: techVerbose ?? s.techVerbose,
    callsRetentionDays: callsRetentionDays ?? s.callsRetentionDays,
    callsMaxMb: callsMaxMb ?? s.callsMaxMb,
    techRetentionDays: techRetentionDays ?? s.techRetentionDays,
    techMaxMb: techMaxMb ?? s.techMaxMb,
    maskByDefault: maskByDefault ?? s.maskByDefault,
    callsBytes: s.callsBytes,
    techBytes: s.techBytes,
    dailyBytesEstimate: s.dailyBytesEstimate,
    droppedTechLines: s.droppedTechLines,
    installId: s.installId,
    oldestAt: s.oldestAt,
  );
}

String formatBytes(int bytes) {
  if (bytes < 1024) return '$bytes Б';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).round()} КБ';
  return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} МБ';
}

/// Состав собираемых данных — перечислением, не мелким шрифтом (ТЗ §7.7.4).
class _PrivacyCard extends StatelessWidget {
  const _PrivacyCard({required this.status});

  final ObservationStatusDto status;

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
                Icon(Icons.lock_outline, color: theme.colorScheme.primary),
                const SizedBox(width: 8),
                Text('Что записывается', style: theme.textTheme.titleMedium),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              'Номер и название каждого входящего звонка, время, решение и то, как приложение '
              'разобрало подпись оператора. Плюс технические подробности: задержки, сеть, '
              'ключи, которые прислала система.',
              style: theme.textTheme.bodyMedium,
            ),
            const SizedBox(height: 8),
            Text(
              'Логи лежат только на устройстве и никуда не отправляются сами. Сеть в '
              'приложении используется единственным образом — проверить и скачать '
              'обновление; часть с журналом и логами доступа к ней не имеет. Отправку '
              'выбираете вы, кнопкой ниже.',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _MainSwitch extends StatelessWidget {
  const _MainSwitch({required this.status, required this.onChanged});

  final ObservationStatusDto status;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return SwitchListTile(
      value: status.enabled,
      onChanged: onChanged,
      title: const Text('Вести журнал наблюдения'),
      subtitle: Text(
        status.enabled
            ? 'Режим работает постоянно и сам не выключается — логи только ротируются'
            : 'Запись остановлена. Уже накопленное сохранено, удалить можно ниже',
      ),
    );
  }
}

class _VolumeCard extends StatelessWidget {
  const _VolumeCard({required this.status});

  final ObservationStatusDto status;

  @override
  Widget build(BuildContext context) {
    final oldest = status.oldestAt;
    // Карточка, как и все остальные блоки экрана: те же пары «подпись — значение», поданные
    // плоским списком, выглядели как другой вид содержимого.
    return Card(
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 0),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _Line('Занято', formatBytes(status.callsBytes + status.techBytes)),
            _Line(
              'Прирост в сутки',
              '≈ ${formatBytes(status.dailyBytesEstimate)}',
            ),
            if (oldest != null) _Line('Есть данные с', formatTime(oldest)),
            _Line('Идентификатор логов', status.installId),
            if (status.droppedTechLines > 0)
              _Line(
                'Потеряно строк',
                '${status.droppedTechLines} (переполнение очереди)',
              ),
          ],
        ),
      ),
    );
  }
}

class _ExportCard extends StatelessWidget {
  const _ExportCard({
    required this.status,
    required this.busy,
    required this.onExport,
  });

  final ObservationStatusDto status;
  final bool busy;
  final VoidCallback onExport;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
      child: FilledButton.icon(
        onPressed: busy ? null : onExport,
        icon: busy
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.ios_share),
        label: Text(busy ? 'Собираю архив…' : 'Отправить логи'),
      ),
    );
  }
}

class _PeriodSelector extends StatelessWidget {
  const _PeriodSelector({required this.periodDays, required this.onChanged});

  final int periodDays;
  final ValueChanged<int> onChanged;

  static const _options = {
    1: 'Сутки',
    7: '7 дней',
    30: '30 дней',
    90: '90 дней',
  };

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Wrap(
        spacing: 8,
        children: [
          for (final e in _options.entries)
            ChoiceChip(
              label: Text(e.value),
              selected: periodDays == e.key,
              onSelected: (_) => onChanged(e.key),
            ),
        ],
      ),
    );
  }
}

/// Главный измеряемый показатель проекта (ТЗ §21 п. 4): приходит подпись к моменту
/// проверки или досылается после решения. От этого зависит, работают ли правила по названию.
class _SignatureCard extends StatelessWidget {
  const _SignatureCard({required this.report});

  final ObservationReportDto report;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final checks = report.checks;
    final share = checks == 0 ? 0.0 : report.withSignature / checks;
    final late = checks == 0 ? 0.0 : report.lateNames / checks;

    return Card(
      margin: const EdgeInsets.all(16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Операторская подпись', style: theme.textTheme.titleMedium),
            const SizedBox(height: 12),
            _Line('Проверок за период', '$checks'),
            _Line(
              'Подпись была сразу',
              '${report.withSignature} (${(share * 100).round()} %)',
            ),
            _Line(
              'Подпись пришла позже',
              '${report.lateNames} (${(late * 100).round()} %)',
            ),
            _Line('Названия не было', '${report.withoutName}'),
            _Line('Скрытый номер', '${report.hiddenNumbers}'),
            if (report.lateNames > 0) ...[
              const SizedBox(height: 12),
              Text(
                'Часть подписей приходит уже после решения. На таких звонках правила '
                'по названию сработать не могут — по ним нужны правила по номеру.',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.error,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _SourcesCard extends StatelessWidget {
  const _SourcesCard({required this.report});

  final ObservationReportDto report;

  @override
  Widget build(BuildContext context) {
    return _BucketCard(
      title: 'Откуда бралось название',
      buckets: report.nameSources,
      labeller: Labels.nameSourceLabel,
    );
  }
}

class _TimingCard extends StatelessWidget {
  const _TimingCard({required this.report});

  final ObservationReportDto report;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Сколько занимает решение',
              style: theme.textTheme.titleMedium,
            ),
            const SizedBox(height: 12),
            _Line('Медиана', '${report.latencyP50} мс'),
            _Line('95-й процентиль', '${report.latencyP95} мс'),
            _Line('Максимум', '${report.latencyMax} мс'),
            _Line('Холодных стартов', '${report.coldStarts}'),
            _Line('Не успели ответить', '${report.watchdogFired}'),
            if (report.networkTypes.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text('Сеть', style: theme.textTheme.labelLarge),
              for (final b in report.networkTypes) _Line(b.label, '${b.total}'),
              for (final b in report.volte)
                _Line(
                  b.label == 'VOLTE'
                      ? 'VoLTE есть'
                      : (b.label == 'NO_VOLTE'
                            ? 'VoLTE нет'
                            : 'VoLTE неизвестно'),
                  '${b.total}',
                ),
            ],
          ],
        ),
      ),
    );
  }
}

class _ExtrasCard extends StatelessWidget {
  const _ExtrasCard({required this.report});

  final ObservationReportDto report;

  @override
  Widget build(BuildContext context) {
    return _BucketCard(
      title: 'Ключи, которые прислала система',
      description:
          'Именно здесь вендорские реализации могут прятать подпись. Полные значения '
          'есть в выгрузке.',
      buckets: report.extrasKeys,
    );
  }
}

class _SignatureSamples extends StatelessWidget {
  const _SignatureSamples({required this.report, required this.onTap});

  final ObservationReportDto report;
  final ValueChanged<SignatureDto> onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final samples = report.signatures.whereType<SignatureDto>().toList();
    if (samples.isEmpty) {
      return Padding(
        padding: const EdgeInsets.all(16),
        child: Text(
          'Подписей пока не встречалось. Это тоже результат: значит оператор их '
          'не передаёт либо звонков от организаций не было.',
          style: theme.textTheme.bodySmall?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
      );
    }

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
            child: Text(
              'Наблюдённые подписи',
              style: theme.textTheme.titleMedium,
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: Text(
              'Дословно, как прислал оператор, и рядом — во что это превратила '
              'канонизация. Тап создаёт правило.',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          for (final s in samples)
            ListTile(
              dense: true,
              title: Text(s.raw),
              subtitle: Text(
                '${s.fold ?? '—'} · ×${s.total} · ${formatTime(s.lastAt)}',
              ),
              trailing: const Icon(Icons.add_circle_outline),
              onTap: () => onTap(s),
            ),
        ],
      ),
    );
  }
}

/// Параметры логирования (ТЗ §7.7.2): они настройки именно затем, чтобы разбирать новое
/// поведение, не выпуская новую сборку.
class _AdvancedSection extends StatelessWidget {
  const _AdvancedSection({required this.status, required this.onChanged});

  final ObservationStatusDto status;
  final ValueChanged<ObservationStatusDto> onChanged;

  @override
  Widget build(BuildContext context) {
    return ExpansionTile(
      // Иконка нужна не для красоты: рядом стоит «Удалить логи» с иконкой, и без неё
      // два пункта одного блока начинались с разных отступов.
      leading: const Icon(Icons.tune),
      title: const Text('Параметры логирования'),
      subtitle: const Text('Сроки, объёмы, подробность'),
      children: [
        SwitchListTile(
          value: status.techEnabled,
          onChanged: (v) => onChanged(_with(techEnabled: v)),
          title: const Text('Технический лог'),
          subtitle: const Text('Нужен для разбора «почему не сработало»'),
        ),
        SwitchListTile(
          value: status.techVerbose,
          onChanged: status.techEnabled
              ? (v) => onChanged(_with(techVerbose: v))
              : null,
          title: const Text('Подробный технический лог'),
          subtitle: const Text('Больше записей, больше объём'),
        ),
        SwitchListTile(
          value: status.maskByDefault,
          onChanged: (v) => onChanged(_with(maskByDefault: v)),
          title: const Text('Обезличивать выгрузку по умолчанию'),
          // Что именно маскируется, подробно сказано в самой панели отправки — здесь только
          // роль настройки, иначе одно и то же объяснение стоит в приложении дважды.
          subtitle: const Text('Разовый выбор при отправке это переопределяет'),
        ),
        _NumberTile(
          title: 'Хранить события, суток',
          value: status.callsRetentionDays,
          onChanged: (v) => onChanged(_with(callsRetentionDays: v)),
        ),
        _NumberTile(
          title: 'Предел объёма событий, МБ',
          value: status.callsMaxMb,
          onChanged: (v) => onChanged(_with(callsMaxMb: v)),
        ),
        _NumberTile(
          title: 'Хранить технический лог, суток',
          value: status.techRetentionDays,
          onChanged: (v) => onChanged(_with(techRetentionDays: v)),
        ),
        _NumberTile(
          title: 'Предел объёма техлога, МБ',
          value: status.techMaxMb,
          onChanged: (v) => onChanged(_with(techMaxMb: v)),
        ),
      ],
    );
  }

  ObservationStatusDto _with({
    bool? techEnabled,
    bool? techVerbose,
    bool? maskByDefault,
    int? callsRetentionDays,
    int? callsMaxMb,
    int? techRetentionDays,
    int? techMaxMb,
  }) => ObservationStatusDto(
    enabled: status.enabled,
    techEnabled: techEnabled ?? status.techEnabled,
    techVerbose: techVerbose ?? status.techVerbose,
    callsRetentionDays: callsRetentionDays ?? status.callsRetentionDays,
    callsMaxMb: callsMaxMb ?? status.callsMaxMb,
    techRetentionDays: techRetentionDays ?? status.techRetentionDays,
    techMaxMb: techMaxMb ?? status.techMaxMb,
    maskByDefault: maskByDefault ?? status.maskByDefault,
    callsBytes: status.callsBytes,
    techBytes: status.techBytes,
    dailyBytesEstimate: status.dailyBytesEstimate,
    droppedTechLines: status.droppedTechLines,
    installId: status.installId,
    oldestAt: status.oldestAt,
  );
}

class _NumberTile extends StatelessWidget {
  const _NumberTile({
    required this.title,
    required this.value,
    required this.onChanged,
  });

  final String title;
  final int value;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      title: Text(title),
      trailing: SizedBox(
        width: 96,
        child: TextFormField(
          initialValue: '$value',
          keyboardType: TextInputType.number,
          textAlign: TextAlign.end,
          decoration: const InputDecoration(isDense: true),
          onFieldSubmitted: (raw) {
            final parsed = int.tryParse(raw.trim());
            if (parsed != null && parsed >= 0) onChanged(parsed);
          },
        ),
      ),
    );
  }
}

class _DeleteTile extends StatefulWidget {
  const _DeleteTile({required this.status, required this.onDeleted});

  final ObservationStatusDto status;
  final VoidCallback onDeleted;

  @override
  State<_DeleteTile> createState() => _DeleteTileState();
}

class _DeleteTileState extends State<_DeleteTile> {
  final _repo = PlatformRepository();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ListTile(
      leading: Icon(Icons.delete_outline, color: scheme.error),
      title: Text('Удалить логи', style: TextStyle(color: scheme.error)),
      // Занятый объём показан выше, в сводке. Повторять его здесь незачем: два одинаковых
      // числа в пределах одного скролла читаются как разные показатели.
      subtitle: const Text(
        'Удалит и события звонков, и технический лог. Выключение режима '
        'логи не удаляет',
      ),
      onTap: _confirm,
    );
  }

  Future<void> _confirm() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Удалить накопленные логи?'),
        content: const Text(
          'Отменить будет нельзя. Если логи собирались для отправки — сначала отправьте их.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Отмена'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Удалить'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    await _repo.deleteLogs();
    widget.onDeleted();
  }
}

class _BucketCard extends StatelessWidget {
  const _BucketCard({
    required this.title,
    required this.buckets,
    this.description,
    this.labeller,
  });

  final String title;
  final List<BucketDto?> buckets;
  final String? description;
  final String Function(String)? labeller;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final items = buckets.whereType<BucketDto>().toList();
    if (items.isEmpty) return const SizedBox.shrink();

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: theme.textTheme.titleMedium),
            if (description != null) ...[
              const SizedBox(height: 4),
              Text(
                description!,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
            const SizedBox(height: 12),
            for (final b in items)
              _Line(labeller?.call(b.label) ?? b.label, '${b.total}'),
          ],
        ),
      ),
    );
  }
}

/// Строка «подпись — значение». Значение выровнено по правому краю и монотипными цифрами:
/// колонка чисел, которая шатается, читается как небрежность.
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

class _ExportChoice {
  const _ExportChoice({
    required this.fromAt,
    required this.toAt,
    required this.mask,
    required this.label,
  });

  final int fromAt;
  final int toAt;
  final bool mask;
  final String label;
}

/// Выбор периода и режима выгрузки (ТЗ §7.7.3).
///
/// Показывает, что именно уйдёт: период, число событий и оценку размера. «Пришлите лог
/// за вчера» — основной сценарий поддержки, а не «пришлите всё».
class _ExportSheet extends StatefulWidget {
  const _ExportSheet({required this.repo, required this.status});

  final PlatformRepository repo;
  final ObservationStatusDto status;

  @override
  State<_ExportSheet> createState() => _ExportSheetState();
}

class _ExportSheetState extends State<_ExportSheet> {
  static const _periods = {
    'За сегодня': 0,
    'За 24 часа': 1,
    'За 3 дня': 3,
    'За 7 дней': 7,
    'За 30 дней': 30,
    'Всё': -1,
  };

  String _period = 'За 24 часа';
  late bool _mask = widget.status.maskByDefault;
  ExportEstimateDto? _estimate;

  @override
  void initState() {
    super.initState();
    _loadEstimate();
  }

  (int, int) get _range {
    final now = DateTime.now();
    final days = _periods[_period]!;
    final toAt = now.millisecondsSinceEpoch;
    if (days < 0) return (0, toAt);
    if (days == 0) {
      return (
        DateTime(now.year, now.month, now.day).millisecondsSinceEpoch,
        toAt,
      );
    }
    return (now.subtract(Duration(days: days)).millisecondsSinceEpoch, toAt);
  }

  Future<void> _loadEstimate() async {
    setState(() => _estimate = null);
    final (fromAt, toAt) = _range;
    final estimate = await widget.repo.estimateLogs(fromAt: fromAt, toAt: toAt);
    if (mounted) setState(() => _estimate = estimate);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final estimate = _estimate;

    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('Отправить логи', style: theme.textTheme.titleLarge),
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final p in _periods.keys)
                  ChoiceChip(
                    label: Text(p),
                    selected: _period == p,
                    onSelected: (_) {
                      setState(() => _period = p);
                      _loadEstimate();
                    },
                  ),
              ],
            ),
            const SizedBox(height: 16),
            if (estimate == null)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 8),
                child: LinearProgressIndicator(minHeight: 2),
              )
            else
              Text(
                'В архиве будет ${estimate.callLines} '
                '${plural(estimate.callLines, 'событие', 'события', 'событий')}, '
                'примерно ${formatBytes(estimate.archiveBytes)}.',
                style: theme.textTheme.bodyMedium,
              ),
            const SizedBox(height: 16),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              value: _mask,
              onChanged: (v) => setState(() => _mask = v),
              title: const Text('Обезличить'),
              subtitle: const Text(
                'Номера маскируются, имя из контактов скрывается. Подпись '
                'оператора остаётся: она и есть предмет разбора',
              ),
            ),
            if (!_mask)
              Text(
                'Полная выгрузка содержит все номера и все имена звонивших. Отправляйте '
                'её только тому, кому доверяете.',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.error,
                ),
              ),
            const SizedBox(height: 20),
            // Главная кнопка на всю ширину, отмена под ней. В два столбца «Собрать
            // и отправить» не влезает и обрезается — а обрезанная подпись на главном
            // действии выглядит как недоделка.
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: estimate == null || estimate.callLines == 0
                    ? null
                    : () {
                        final (fromAt, toAt) = _range;
                        Navigator.of(context).pop(
                          _ExportChoice(
                            fromAt: fromAt,
                            toAt: toAt,
                            mask: _mask,
                            label: _label(),
                          ),
                        );
                      },
                child: const Text('Собрать и отправить'),
              ),
            ),
            const SizedBox(height: 4),
            SizedBox(
              width: double.infinity,
              child: TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Отмена'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// Метка периода попадает в имя файла: по присланному архиву должно быть видно, что в нём.
  String _label() {
    final days = _periods[_period]!;
    if (days < 0) return 'all';
    if (days == 0) return 'today';
    return '${days}d';
  }
}
