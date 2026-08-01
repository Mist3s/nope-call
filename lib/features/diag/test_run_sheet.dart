import 'package:flutter/material.dart';

import '../../data/nope_call_api.g.dart';
import '../../data/repository.dart';

/// Тестовый прогон (ТЗ §9.7).
///
/// Способ увидеть решение по звонку **без второго телефона**: ввести номер и подпись и получить
/// весь путь — во что превратилась канонизация, какие правила проверялись, какое сработало
/// и сколько это заняло. Он же — способ проверить критерии приёмки.
///
/// Прогон идёт через настоящий снимок и настоящий движок: вторая реализация сопоставления
/// «для диагностики» тут же начала бы расходиться с первой и врать в самый нужный момент.
class TestRunSheet extends StatefulWidget {
  const TestRunSheet({super.key});

  @override
  State<TestRunSheet> createState() => _TestRunSheetState();
}

class _TestRunSheetState extends State<TestRunSheet> {
  final _repo = PlatformRepository();
  final _number = TextEditingController(text: '+7');
  final _name = TextEditingController();

  TestRunDto? _result;
  bool _busy = false;

  @override
  void dispose() {
    _number.dispose();
    _name.dispose();
    super.dispose();
  }

  Future<void> _run() async {
    setState(() => _busy = true);
    try {
      final result = await _repo.testRun(
        _number.text.trim(),
        _name.text.trim().isEmpty ? null : _name.text.trim(),
      );
      if (mounted) setState(() => _result = result);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final result = _result;

    return SafeArea(
      child: Padding(
        padding: EdgeInsets.fromLTRB(
          20,
          20,
          20,
          20 + MediaQuery.viewInsetsOf(context).bottom,
        ),
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Тестовый прогон', style: theme.textTheme.titleLarge),
              const SizedBox(height: 4),
              Text(
                'Ничего не звонит и ничего не блокируется — проверяется только решение.',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _number,
                keyboardType: TextInputType.phone,
                decoration: const InputDecoration(
                  labelText: 'Номер',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _name,
                decoration: const InputDecoration(
                  labelText: 'Подпись оператора (если была)',
                  hintText: 'OOO Romashka: reklama',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 16),
              FilledButton.icon(
                onPressed: _busy ? null : _run,
                icon: _busy
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.play_arrow),
                label: const Text('Прогнать'),
              ),
              if (result != null) ...[
                const SizedBox(height: 20),
                _Verdict(result: result),
                const SizedBox(height: 16),
                Text(
                  'Как разобрался звонок',
                  style: theme.textTheme.labelLarge,
                ),
                const SizedBox(height: 8),
                _Field('Номер сравнивается как', result.digits),
                if (result.e164 != null) _Field('E.164', result.e164!),
                _Field(
                  'Варианты номера',
                  result.candidates.whereType<String>().join(', '),
                ),
                if (result.nameFold.isNotEmpty)
                  _Field('Название сравнивается как', result.nameFold),
                if (result.orgFold.isNotEmpty)
                  _Field('Наименование', result.orgFold),
                if (result.categoryFold != null)
                  _Field('Категория', result.categoryFold!),
                const SizedBox(height: 16),
                Text(
                  'Какие правила проверялись',
                  style: theme.textTheme.labelLarge,
                ),
                const SizedBox(height: 4),
                if (result.steps.isEmpty)
                  Text(
                    // Причина пустой трассы бывает разной, и «подходящих по типу нет»
                    // подходит только к одной из них. У короткого номера и у выключенной
                    // блокировки проход не выполнялся вовсе, а прежний текст утверждал,
                    // что правила смотрели и не нашли подходящих.
                    switch (result.reason) {
                      'RULE_MATCH' =>
                        'Совпало точное правило по номеру — оно найдено по индексу, '
                            'без перебора остальных.',
                      'SHORT_NUMBER' =>
                        'Проход по правилам не выполнялся: у короткого номера работает '
                            'только точное правило по номеру, остальные пропускаются.',
                      'EMERGENCY' =>
                        'Проход по правилам не выполнялся: экстренный номер разрешается '
                            'до проверки правил.',
                      'DISABLED_BY_USER' =>
                        'Проход по правилам не выполнялся: блокировка выключена общим '
                            'выключателем.',
                      'RESTRICTED_NUMBER' || 'UNKNOWN_NUMBER' =>
                        'Проход по правилам не выполнялся: сопоставлять нечего — ни номера, '
                            'ни названия.',
                      'OUTGOING_CALL' =>
                        'Проход по правилам не выполнялся: звонок исходящий.',
                      _ =>
                        'Ни одно правило не проверялось: подходящих по типу нет.',
                    },
                    style: theme.textTheme.bodySmall,
                  ),
                for (final step in result.steps.whereType<TraceStepDto>())
                  _Step(step: step),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _Verdict extends StatelessWidget {
  const _Verdict({required this.result});

  final TestRunDto result;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final blocks = result.action == 'REJECT' || result.action == 'DROP';
    // Цвет содержимого задан явно. Без него текст берёт `onSurface` — на фоне
    // `errorContainer` в тёмной теме это почти нечитаемо, а вся карточка превращается
    // в красный блок с тёмными буквами.
    final on = blocks
        ? theme.colorScheme.onErrorContainer
        : theme.colorScheme.onSecondaryContainer;
    return Card(
      color: blocks
          ? theme.colorScheme.errorContainer
          : theme.colorScheme.secondaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(blocks ? Icons.block : Icons.check_circle_outline, color: on),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    Labels.action(result.action),
                    style: theme.textTheme.titleMedium?.copyWith(color: on),
                  ),
                  Text(
                    '${Labels.reason(result.reason)}'
                    '${result.matchedRuleTitle != null ? ' «${result.matchedRuleTitle}»' : ''}'
                    // «0 мкс» читается как поломка: точное правило находится по индексу
                    // быстрее, чем измеряется. Честнее сказать «меньше микросекунды».
                    ' · ${result.elapsedMicros == 0 ? '< 1' : result.elapsedMicros} мкс',
                    style: theme.textTheme.bodySmall?.copyWith(color: on),
                  ),
                  if (result.snapshotMissing)
                    Text(
                      'Снимка правил нет — звонок был бы пропущен',
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.error,
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Step extends StatelessWidget {
  const _Step({required this.step});

  final TraceStepDto step;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            step.matched
                ? Icons.check_circle
                : (step.skippedReason != null
                      ? Icons.error_outline
                      : Icons.radio_button_unchecked),
            size: 18,
            color: step.matched
                ? scheme.primary
                : (step.skippedReason != null ? scheme.error : scheme.outline),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(step.title, style: theme.textTheme.bodyMedium),
                Text(
                  '${Labels.target(step.target)} · ${Labels.matchType(step.matchType)} · '
                  '«${step.canonical}»'
                  '${step.skippedReason != null ? ' · пропущено: ${step.skippedReason}' : ''}',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: scheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
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
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 190,
            child: Text(
              label,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          Expanded(child: Text(value, style: theme.textTheme.bodyMedium)),
        ],
      ),
    );
  }
}
