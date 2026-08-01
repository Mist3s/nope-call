import 'package:flutter/material.dart';

import '../../data/nope_call_api.g.dart';
import '../../data/repository.dart';
import '../../widgets/async_view.dart';

/// Главный экран (ТЗ §9.1).
///
/// Главное требование к нему: **не обещать того, чего нет**. Без роли сервис проверки
/// не вызывается вообще, поэтому «блокировка активна» показывать нельзя — и это отдельный
/// критерий приёмки (§18 п. 2).
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _repo = PlatformRepository();
  Loadable<(SetupStatus, SummaryDto)> _state = const Loadable(loading: true);

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// Обновление тихое: карточка статуса и счётчики остаются на экране, пока идёт запрос.
  /// Иначе возврат из системного диалога роли ронял бы экран в спиннер.
  Future<void> _load() async {
    // `_load` вызывается не только из `initState`, но и после `await` — из обновления
    // и из применения настройки. Ведущий `setState` без этой проверки бросал бы ассертом
    // на экране, который пользователь успел закрыть.
    if (!mounted) return;
    setState(() => _state = Loadable(data: _state.data, loading: true));
    try {
      final status = await _repo.status();
      final summary = await _repo.summary();
      if (!mounted) return;
      setState(() => _state = Loadable(data: (status, summary)));
    } catch (e) {
      if (!mounted) return;
      setState(() => _state = Loadable(data: _state.data, error: e));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Отбой')),
      body: RefreshIndicator(
        onRefresh: _load,
        child: LoadableView<(SetupStatus, SummaryDto)>(
          state: _state,
          onRetry: _load,
          builder: (context, data) {
            final (status, summary) = data;
            return ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _StatusCard(status: status, onRequestRole: _requestRole),
                const SizedBox(height: 16),
                _StatsRow(status: status, summary: summary),
                const SizedBox(height: 16),
                // Отступ внутри условия, а не рядом с ним: без карточки проблем два
                // соседних SizedBox складывались в двойной промежуток, и карточки
                // на главном экране стояли с разным шагом.
                if (status.problems.isNotEmpty) ...[
                  _ProblemsCard(
                    problems: status.problems,
                    onOpenSettings: _repo.openAppSettings,
                  ),
                  const SizedBox(height: 16),
                ],
                _SignatureCard(summary: summary),
              ],
            );
          },
        ),
      ),
    );
  }

  Future<void> _requestRole() async {
    final available = await _repo.requestRole();
    if (!available && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Это устройство не поддерживает роль средства проверки звонков',
          ),
        ),
      );
    }
    // Диалог системный: результат придёт после возврата в приложение.
    if (mounted) await _load();
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.status, required this.onRequestRole});

  final SetupStatus status;
  final Future<void> Function() onRequestRole;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final active = status.blockingActive;
    final color = active ? scheme.primary : scheme.error;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  active ? Icons.shield : Icons.shield_outlined,
                  color: color,
                  size: 32,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    active ? 'Блокировка активна' : 'Блокировка не работает',
                    style: Theme.of(
                      context,
                    ).textTheme.titleLarge?.copyWith(color: color),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              active ? _describe(status) : _explain(status),
              style: Theme.of(context).textTheme.bodyMedium,
            ),
            if (active && status.defaultAction != 'ALLOW') ...[
              const SizedBox(height: 8),
              Text(
                'Это обратный порядок: приложение задумано блокировать только при '
                'совпадении правила. Проверьте раздел «Когда правило не совпало» '
                'в настройках.',
                style: Theme.of(
                  context,
                ).textTheme.bodySmall?.copyWith(color: scheme.error),
              ),
            ],
            if (!status.hasRole) ...[
              const SizedBox(height: 16),
              FilledButton.icon(
                onPressed: onRequestRole,
                icon: const Icon(Icons.verified_user_outlined),
                label: const Text('Назначить приложение'),
              ),
            ],
          ],
        ),
      ),
    );
  }

  /// Что произойдёт со звонком — по фактической настройке, а не по замыслу.
  ///
  /// Обещание на главном экране обязано совпадать с поведением. Пока действие
  /// по умолчанию `ALLOW`, верно «остальные проходят»; если пользователь переключил его
  /// на блокировку, та же фраза становится прямой неправдой — а это главный экран
  /// приложения, которое обещает объяснимость.
  String _describe(SetupStatus status) {
    if (status.defaultAction == 'ALLOW') {
      return 'Звонок отклоняется, только если совпало ваше правило. '
          'Во всех остальных случаях звонок проходит.';
    }
    final what = switch (status.defaultAction) {
      'REJECT' => 'отклоняется',
      'DROP' => 'сбрасывается без гудка',
      'SILENCE' => 'проходит без звука',
      _ => 'обрабатывается по настройке',
    };
    return 'Проходят только звонки, которые разрешило ваше правило: всё остальное '
        '$what. Так задано действием по умолчанию. '
        'Сбой, таймаут или недоступные правила по-прежнему пропускают звонок.';
  }

  String _explain(SetupStatus status) {
    if (!status.hasRole) {
      return 'Пока приложение не назначено средством проверки звонков, система '
          'не отдаёт ему звонки — блокировать нечем.';
    }
    if (!status.blockingEnabled) {
      return 'Блокировка выключена в настройках. Правила сохранены и заработают '
          'сразу после включения.';
    }
    return 'Нет ни одного включённого правила. Пока их нет, блокировать нечего: '
        'приложение не угадывает, а действует только по вашим правилам.';
  }
}

/// Статистика (ТЗ §9.1).
///
/// Одна карточка со строками, а не три тесные плашки: в колонку шириной в треть экрана
/// подписи вида «заблокировано сегодня» не влезают и рвутся посреди слова. Строки дают
/// выровненные значения и подписи целиком, без переносов.
class _StatsRow extends StatelessWidget {
  const _StatsRow({required this.status, required this.summary});

  final SetupStatus status;
  final SummaryDto summary;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Column(
          children: [
            _StatRow(
              icon: Icons.block,
              label: 'Заблокировано сегодня',
              value: '${summary.blockedToday}',
              highlight: summary.blockedToday > 0,
            ),
            const Divider(height: 1),
            _StatRow(
              icon: Icons.rule,
              label: 'Активных правил',
              value: '${status.enabledRuleCount}',
            ),
            const Divider(height: 1),
            _StatRow(
              icon: Icons.fact_check_outlined,
              label: 'Проверок всего',
              value: '${summary.totalEvents}',
            ),
          ],
        ),
      ),
    );
  }
}

class _StatRow extends StatelessWidget {
  const _StatRow({
    required this.icon,
    required this.label,
    required this.value,
    this.highlight = false,
  });

  final IconData icon;
  final String label;
  final String value;
  final bool highlight;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      child: Row(
        children: [
          Icon(icon, size: 20, color: scheme.onSurfaceVariant),
          const SizedBox(width: 16),
          Expanded(
            child: Text(
              label,
              style: theme.textTheme.bodyLarge,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const SizedBox(width: 12),
          Text(
            value,
            style: theme.textTheme.titleLarge?.copyWith(
              fontWeight: FontWeight.w600,
              color: scheme.onSurface,
              // Табличные цифры: значения в столбце не «дёргаются» по ширине.
              fontFeatures: const [FontFeature.tabularFigures()],
            ),
          ),
        ],
      ),
    );
  }
}

class _ProblemsCard extends StatelessWidget {
  const _ProblemsCard({required this.problems, required this.onOpenSettings});

  final List<String> problems;
  final VoidCallback onOpenSettings;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Что мешает', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            for (final p in problems)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(Icons.warning_amber_rounded, size: 18),
                    const SizedBox(width: 8),
                    Expanded(child: Text(Labels.problem(p))),
                  ],
                ),
              ),
            const SizedBox(height: 8),
            TextButton(
              onPressed: onOpenSettings,
              child: const Text('Открыть настройки приложения'),
            ),
          ],
        ),
      ),
    );
  }
}

/// Наполняемость операторской подписи (ТЗ §7.7.5).
///
/// Показывается затем, чтобы пользователь **до** создания правил по названию увидел, приходит ли
/// подпись на его операторе и устройстве вообще. Правила по названию строить на пустом месте
/// бессмысленно, а понять это без цифр невозможно.
class _SignatureCard extends StatelessWidget {
  const _SignatureCard({required this.summary});

  final SummaryDto summary;

  @override
  Widget build(BuildContext context) {
    if (summary.checkedLast100 == 0) {
      return const SizedBox.shrink();
    }
    final scheme = Theme.of(context).colorScheme;
    final share = summary.withSignatureLast100 / summary.checkedLast100;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Названия звонящих',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            Text(
              'Подпись оператора была у ${summary.withSignatureLast100} '
              'из ${summary.checkedLast100} последних звонков.',
            ),
            const SizedBox(height: 8),
            LinearProgressIndicator(value: share),
            const SizedBox(height: 8),
            Text(
              share < 0.2
                  ? 'Подпись приходит редко: правила по названию будут срабатывать нечасто. '
                        'Это зависит от оператора и от поддержки VoLTE.'
                  : 'Подпись приходит достаточно часто, чтобы правила по названию работали.',
              style: Theme.of(
                context,
              ).textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }
}
