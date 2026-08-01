import 'package:flutter/material.dart';

import '../../data/nope_call_api.g.dart';
import '../../data/repository.dart';
import '../../widgets/async_view.dart';
import 'rule_editor.dart';

/// Список правил (ТЗ §9.4).
///
/// Порядок в списке — это и есть порядок применения: первое совпавшее правило выигрывает.
/// Поэтому перетаскивание меняет решения, и об этом сказано прямо в шапке.
class RulesScreen extends StatefulWidget {
  const RulesScreen({super.key});

  @override
  State<RulesScreen> createState() => _RulesScreenState();
}

class _RulesScreenState extends State<RulesScreen> {
  final _repo = PlatformRepository();
  Loadable<List<RuleDto>> _state = const Loadable(loading: true);

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// Обновление **тихое**: прежний список остаётся на экране, пока не придёт новый.
  /// Иначе каждое переключение правила роняло бы список в спиннер и экран мигал.
  Future<void> _load() async {
    // `_load` вызывается не только из `initState`, но и после `await` — из обновления
    // и из применения настройки. Ведущий `setState` без этой проверки бросал бы ассертом
    // на экране, который пользователь успел закрыть.
    if (!mounted) return;
    setState(() => _state = Loadable(data: _state.data, loading: true));
    try {
      final rules = await _repo.rules();
      if (!mounted) return;
      setState(() => _state = Loadable(data: rules));
    } catch (e) {
      if (!mounted) return;
      setState(() => _state = Loadable(data: _state.data, error: e));
    }
  }

  /// Локальная замена одного правила — чтобы переключатель отзывался мгновенно,
  /// не дожидаясь ответа платформы и пересборки снимка.
  void _replaceLocally(int id, RuleDto Function(RuleDto) transform) {
    final current = _state.data;
    if (current == null) return;
    setState(() {
      _state = Loadable(
        data: [
          for (final r in current)
            if (r.id == id) transform(r) else r,
        ],
      );
    });
  }

  Future<void> _export() async {
    final shared = await _repo.exportRules();
    if (!mounted || shared) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Сохранять нечего: правил нет')),
    );
  }

  /// Импорт с отчётом (ТЗ §15.8): показать, что добавилось, что пропущено и что отклонено.
  /// Молча применить файл нельзя — пользователь должен видеть, во что превратился импорт.
  Future<void> _import({required bool replaceAll}) async {
    if (replaceAll) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Заменить все правила?'),
          content: const Text(
            'Текущие правила будут удалены, а вместо них появятся правила из файла. '
            'Отменить будет нельзя.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: const Text('Отмена'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: const Text('Заменить'),
            ),
          ],
        ),
      );
      if (ok != true) return;
    }

    final report = await _repo.importRules(replaceAll: replaceAll);
    if (!mounted) return;
    await _load();
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (context) => _ImportReportDialog(report: report),
    );
  }

  Future<void> _openEditor([RuleDto? rule]) async {
    final saved = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => RuleEditorScreen(rule: rule)),
    );
    if (saved == true) await _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Правила'),
        actions: [
          PopupMenuButton<String>(
            tooltip: 'Экспорт и импорт',
            onSelected: (value) => switch (value) {
              'export' => _export(),
              'add' => _import(replaceAll: false),
              _ => _import(replaceAll: true),
            },
            itemBuilder: (context) => const [
              PopupMenuItem(
                value: 'export',
                child: Text('Сохранить правила в файл'),
              ),
              PopupMenuItem(value: 'add', child: Text('Добавить из файла')),
              PopupMenuItem(
                value: 'replace',
                child: Text('Заменить все из файла'),
              ),
            ],
          ),
        ],
      ),
      // FAB скрыт, пока список пуст: там уже стоит кнопка «Создать правило», и два
      // разных элемента для одного действия на одном экране читаются как недоделка.
      floatingActionButton: (_state.data?.isEmpty ?? true)
          ? null
          : FloatingActionButton.extended(
              onPressed: _openEditor,
              icon: const Icon(Icons.add),
              label: const Text('Правило'),
            ),
      body: LoadableView<List<RuleDto>>(
        state: _state,
        onRetry: _load,
        builder: (context, rules) {
          if (rules.isEmpty) {
            return EmptyState(
              icon: Icons.rule_outlined,
              title: 'Правил пока нет',
              description:
                  'Приложение не угадывает, кого блокировать. Пока нет правил, '
                  'все звонки проходят. Создайте первое правило.',
              action: FilledButton(
                onPressed: _openEditor,
                child: const Text('Создать правило'),
              ),
            );
          }
          return Column(
            children: [
              const _OrderHint(),
              Expanded(
                child: ReorderableListView.builder(
                  padding: const EdgeInsets.only(bottom: 88),
                  itemCount: rules.length,
                  onReorder: (from, to) => _reorder(rules, from, to),
                  itemBuilder: (context, i) => _RuleTile(
                    key: ValueKey(rules[i].id),
                    rule: rules[i],
                    index: i,
                    onTap: () => _openEditor(rules[i]),
                    onToggle: (v) => _toggle(rules[i], v),
                    onDelete: () => _delete(rules[i]),
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _reorder(List<RuleDto> rules, int from, int to) async {
    final reordered = [...rules];
    final item = reordered.removeAt(from);
    reordered.insert(from < to ? to - 1 : to, item);

    // Порядок применяется на экране сразу: перетаскивание должно ощущаться мгновенным.
    setState(() => _state = Loadable(data: reordered, loading: true));

    // Одна операция на весь список: перенумерация идёт в одной транзакции, иначе
    // промежуточное состояние дало бы неверный порядок применения.
    await _repo.reorderRules(reordered.map((r) => r.id).toList());
    await _load();
  }

  Future<void> _toggle(RuleDto rule, bool enabled) async {
    _replaceLocally(
      rule.id,
      (r) => RuleDto(
        id: r.id,
        title: r.title,
        targetType: r.targetType,
        matchType: r.matchType,
        pattern: r.pattern,
        patternCanonical: r.patternCanonical,
        action: r.action,
        orderIndex: r.orderIndex,
        isEnabled: enabled,
        translitVariants: r.translitVariants,
        matchCount: r.matchCount,
        regexField: r.regexField,
        comment: r.comment,
        lastMatchedAt: r.lastMatchedAt,
      ),
    );
    await _repo.setRuleEnabled(rule.id, enabled);
    await _load();
  }

  Future<void> _delete(RuleDto rule) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Удалить правило?'),
        content: Text('«${rule.title}» перестанет действовать.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Отмена'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Удалить'),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      // Удаляем из списка сразу: ждать ответа платформы, глядя на удалённое правило, странно.
      final current = _state.data;
      if (current != null) {
        setState(() {
          _state = Loadable(
            data: current.where((r) => r.id != rule.id).toList(),
            loading: true,
          );
        });
      }
      await _repo.deleteRule(rule.id);
      await _load();
    }
  }
}

class _OrderHint extends StatelessWidget {
  const _OrderHint();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      color: scheme.surfaceContainerHighest,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Text(
        'Выигрывает первое совпавшее правило сверху. Перетащите, чтобы изменить порядок.',
        style: Theme.of(
          context,
        ).textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
      ),
    );
  }
}

/// Отчёт об импорте (ТЗ §15.8). Отклонённые показываются построчно с причиной: «файл
/// импортирован» без объяснения, почему часть правил не появилась, — это не отчёт.
class _ImportReportDialog extends StatelessWidget {
  const _ImportReportDialog({required this.report});

  final ImportReportDto report;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final rejected = report.rejected.whereType<String>().toList();
    final removed = report.removed.whereType<String>().toList();

    return AlertDialog(
      title: Text(report.ok ? 'Импорт завершён' : 'Импорт не выполнен'),
      content: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            if (!report.ok)
              Text(report.error ?? 'Файл не разобран')
            else ...[
              Text('Добавлено: ${report.added}'),
              if (report.updated > 0) Text('Обновлено: ${report.updated}'),
              if (report.duplicates > 0)
                Text('Пропущено как дубликат: ${report.duplicates}'),
              if (removed.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text('Удалено:', style: theme.textTheme.labelLarge),
                for (final r in removed) Text('· $r'),
              ],
              if (rejected.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(
                  'Отклонено:',
                  style: theme.textTheme.labelLarge?.copyWith(
                    color: theme.colorScheme.error,
                  ),
                ),
                for (final r in rejected)
                  Text('· $r', style: theme.textTheme.bodySmall),
              ],
              if (!report.snapshotRebuilt) ...[
                const SizedBox(height: 8),
                Text(
                  'Внимание: действующая копия правил не пересобралась. Правила в списке '
                  'новые, а решения по звонкам пока прежние — перезапустите приложение.',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.error,
                  ),
                ),
              ],
            ],
          ],
        ),
      ),
      actions: [
        FilledButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Понятно'),
        ),
      ],
    );
  }
}

class _RuleTile extends StatelessWidget {
  const _RuleTile({
    super.key,
    required this.rule,
    required this.index,
    required this.onTap,
    required this.onToggle,
    required this.onDelete,
  });

  final RuleDto rule;
  final int index;
  final VoidCallback onTap;
  final ValueChanged<bool> onToggle;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final blocking = rule.action == 'REJECT' || rule.action == 'DROP';
    return ListTile(
      onTap: onTap,
      leading: Icon(
        blocking ? Icons.block : Icons.check_circle_outline,
        color: rule.isEnabled
            ? (blocking ? scheme.error : scheme.primary)
            : scheme.outline,
      ),
      title: Text(
        rule.title,
        // Одна строка с многоточием. Длинное название без пробелов — а такие создаёт
        // карточка звонка — рвалось посередине слова, и строка выглядела как сломанная
        // вёрстка. Полное название видно в редакторе.
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: rule.isEnabled
            ? null
            : TextStyle(
                color: scheme.outline,
                decoration: TextDecoration.lineThrough,
              ),
      ),
      // Действие в подписи не повторяется: его показывает иконка слева. Раньше строка
      // из четырёх частей не влезала, ломалась на висячем предлоге и «Отклонить»
      // оказывалось на второй строке, где читалось как часть шаблона.
      subtitle: Text(
        '${Labels.target(rule.targetType)} · '
        // Неразрывный пробел склеивает предлог со шаблоном: иначе «с» и «на»
        // остаются висеть в конце строки, а шаблон уезжает на следующую.
        // Шаблона может не быть вовсе: у правила «есть в контактах» сравнивать нечего,
        // и пустые кавычки «» читались как потерянное значение.
        '${Labels.matchType(rule.matchType)}'
        '${rule.pattern.isEmpty ? '' : ' «${rule.pattern}»'}'
        '${rule.matchCount > 0 ? ' · сработало ${rule.matchCount}' : ''}',
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Switch(value: rule.isEnabled, onChanged: onToggle),
          IconButton(
            onPressed: onDelete,
            icon: const Icon(Icons.delete_outline),
            tooltip: 'Удалить',
          ),
          ReorderableDragStartListener(
            index: index,
            child: const Padding(
              padding: EdgeInsets.symmetric(horizontal: 4),
              child: Icon(Icons.drag_handle),
            ),
          ),
        ],
      ),
    );
  }
}
