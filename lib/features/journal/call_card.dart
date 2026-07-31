import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../data/nope_call_api.g.dart';
import '../../data/repository.dart';
import '../../widgets/async_view.dart';

/// Карточка звонка (ТЗ §9.3).
///
/// Основной способ создания правил — отсюда, а не из редактора: пользователь видит конкретный
/// звонок и решает, что с такими делать. Ручной редактор вторичен (критерий приёмки §18 п. 11).
///
/// Каждое действие показывает, **какое именно правило** будет создано, и сколько записей журнала
/// под него уже попадает. Иначе «заблокировать все номера с таким началом» — покупка кота в мешке.
class CallCardScreen extends StatefulWidget {
  const CallCardScreen({super.key, required this.item});

  final JournalItemDto item;

  @override
  State<CallCardScreen> createState() => _CallCardScreenState();
}

class _CallCardScreenState extends State<CallCardScreen> {
  final _repo = PlatformRepository();
  bool _created = false;

  JournalItemDto get item => widget.item;

  /// Цифры номера — на них строятся предложения по префиксу и суффиксу.
  String get _digits => item.rawNumber.replaceAll(RegExp(r'\D'), '');

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final suggestions = _suggestions();

    return Scaffold(
      appBar: AppBar(title: const Text('Звонок')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(
                        item.blockedByUs ? Icons.block : Icons.call_received,
                        color: item.blockedByUs
                            ? theme.colorScheme.error
                            : theme.colorScheme.primary,
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(
                          item.rawNumber.isEmpty
                              ? 'Скрытый номер'
                              : item.rawNumber,
                          style: theme.textTheme.titleLarge,
                        ),
                      ),
                      if (item.rawNumber.isNotEmpty)
                        IconButton(
                          tooltip: 'Скопировать',
                          icon: const Icon(Icons.copy_outlined),
                          onPressed: () {
                            Clipboard.setData(
                              ClipboardData(text: item.rawNumber),
                            );
                            ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(content: Text('Номер скопирован')),
                            );
                          },
                        ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  _Field('Когда', formatTime(item.occurredAt)),
                  _Field('Тип записи', Labels.kind(item.kind)),
                  if (item.nameRaw?.isNotEmpty == true)
                    _Field('Подпись', item.nameRaw!),
                  _Field(
                    'Название получено',
                    _nameSourceLabel(item.nameSource),
                  ),
                  // Решения может не быть вовсе: запись пришла из системного журнала,
                  // а звонок проверяли не мы. Придумывать результат в этом случае нельзя.
                  if (item.action != null)
                    _Field('Результат', Labels.action(item.action!))
                  else
                    _Field('Результат', 'приложение этот звонок не проверяло'),
                  if (item.reason != null)
                    _Field('Причина', Labels.reason(item.reason!)),
                  if (item.matchedRuleTitle != null)
                    _Field('Правило', item.matchedRuleTitle!),
                  if (item.durationSeconds != null)
                    _Field('Длительность', '${item.durationSeconds} с'),
                  if (item.latencyMs != null)
                    _Field('Решение заняло', '${item.latencyMs} мс'),
                ],
              ),
            ),
          ),
          const SizedBox(height: 20),
          Text(
            'Что делать с такими звонками',
            style: theme.textTheme.titleMedium,
          ),
          const SizedBox(height: 4),
          Text(
            'Правило начнёт действовать сразу после создания.',
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 12),
          if (suggestions.isEmpty)
            Text(
              'У скрытого номера нет ни цифр, ни подписи — правило построить не на чем. '
              'Скрытые номера настраиваются отдельно, в настройках.',
              style: theme.textTheme.bodyMedium,
            ),
          for (final s in suggestions)
            _SuggestionTile(
              suggestion: s,
              repo: _repo,
              onCreated: () => setState(() => _created = true),
            ),
          if (_created) ...[
            const SizedBox(height: 16),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: const Text('Готово'),
            ),
          ],
        ],
      ),
    );
  }

  /// Предложения по этому звонку. Порядок — от самого узкого к самому широкому:
  /// точный номер безопаснее префикса, префикс безопаснее правила по названию.
  List<_Suggestion> _suggestions() {
    final result = <_Suggestion>[];
    final digits = _digits;

    if (digits.isNotEmpty) {
      result.add(
        _Suggestion(
          title: 'Заблокировать этот номер',
          detail: item.rawNumber,
          target: 'NUMBER',
          matchType: 'EXACT',
          pattern: item.rawNumber,
          action: 'REJECT',
        ),
      );

      if (digits.length >= 5) {
        final prefix = digits.substring(0, 5);
        result.add(
          _Suggestion(
            title: 'Заблокировать все номера, начинающиеся с $prefix',
            detail: 'Обычно это код города или оператора',
            target: 'NUMBER',
            matchType: 'PREFIX',
            pattern: prefix,
            action: 'REJECT',
          ),
        );
      }

      if (digits.length >= 4) {
        final suffix = digits.substring(digits.length - 4);
        result.add(
          _Suggestion(
            title: 'Заблокировать все номера, заканчивающиеся на $suffix',
            detail: 'Пригодится против номеров, отличающихся только началом',
            target: 'NUMBER',
            matchType: 'SUFFIX',
            pattern: suffix,
            action: 'REJECT',
          ),
        );
      }
    }

    // Правило по названию предлагается только когда подпись действительно была: строить его
    // на пустом месте бессмысленно (ТЗ §6.3).
    final name = item.nameRaw;
    if (name != null && name.isNotEmpty) {
      result.add(
        _Suggestion(
          title: 'Заблокировать звонки с такой подписью',
          detail: name,
          target: 'NAME_ORG',
          matchType: 'CONTAINS',
          pattern: name,
          action: 'REJECT',
        ),
      );
    }

    if (digits.isNotEmpty) {
      result.add(
        _Suggestion(
          title: 'Добавить в разрешённые',
          detail:
              'Звонок будет проходить, даже если ниже есть блокирующее правило',
          target: 'NUMBER',
          matchType: 'EXACT',
          pattern: item.rawNumber,
          action: 'ALLOW',
        ),
      );
    }
    return result;
  }

  String _nameSourceLabel(String source) => switch (source) {
    'CNAP' => 'подпись оператора',
    'CNAP_OPERATOR_LABEL' => 'служебная метка оператора',
    'CONTACTS' => 'телефонная книга',
    'SYSTEM_LOG' => 'системный журнал, уже после звонка',
    _ => 'названия не было',
  };
}

class _Suggestion {
  const _Suggestion({
    required this.title,
    required this.detail,
    required this.target,
    required this.matchType,
    required this.pattern,
    required this.action,
  });

  final String title;
  final String detail;
  final String target;
  final String matchType;
  final String pattern;
  final String action;
}

/// Плитка предложения с предпросмотром: сколько записей журнала попадёт под правило.
class _SuggestionTile extends StatefulWidget {
  const _SuggestionTile({
    required this.suggestion,
    required this.repo,
    required this.onCreated,
  });

  final _Suggestion suggestion;
  final PlatformRepository repo;
  final VoidCallback onCreated;

  @override
  State<_SuggestionTile> createState() => _SuggestionTileState();
}

class _SuggestionTileState extends State<_SuggestionTile> {
  PreviewDto? _preview;
  bool _busy = false;
  bool _done = false;

  @override
  void initState() {
    super.initState();
    _loadPreview();
  }

  Future<void> _loadPreview() async {
    final s = widget.suggestion;
    final preview = await widget.repo.preview(s.target, s.matchType, s.pattern);
    if (mounted) setState(() => _preview = preview);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final s = widget.suggestion;
    final allow = s.action == 'ALLOW';
    final preview = _preview;

    return Card(
      child: ListTile(
        leading: Icon(
          allow ? Icons.check_circle_outline : Icons.block,
          color: _done
              ? theme.colorScheme.outline
              : (allow ? theme.colorScheme.primary : theme.colorScheme.error),
        ),
        title: Text(s.title),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(s.detail, maxLines: 2, overflow: TextOverflow.ellipsis),
            if (preview != null)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(
                  previewText(preview),
                  // Не цветом ошибки: это не ошибка, а справка о том, скольких записей
                  // правило коснётся. Красный в этом экране означает «блокирует».
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                    fontWeight: preview.count > 1 ? FontWeight.w600 : null,
                  ),
                ),
              ),
          ],
        ),
        isThreeLine: true,
        trailing: _done
            ? const Icon(Icons.done)
            : (_busy
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.add)),
        onTap: _done || _busy ? null : _create,
      ),
    );
  }

  Future<void> _create() async {
    setState(() => _busy = true);
    final s = widget.suggestion;
    final result = await widget.repo.saveRule(
      title: s.title,
      targetType: s.target,
      matchType: s.matchType,
      pattern: s.pattern,
      action: s.action,
    );
    if (!mounted) return;
    setState(() {
      _busy = false;
      _done = result.saved;
    });
    if (result.saved) {
      widget.onCreated();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Правило создано и уже действует')),
      );
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(result.error ?? 'Не удалось создать правило')),
      );
    }
  }
}

class _Field extends StatelessWidget {
  const _Field(this.label, this.value);

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
          // Доли, а не фиксированные 150: значением здесь бывает название правила,
          // и в узкой колонке оно рвалось посередине слова.
          Expanded(
            flex: 2,
            child: Text(
              label,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            flex: 3,
            child: Text(value, style: theme.textTheme.bodyMedium),
          ),
        ],
      ),
    );
  }
}
