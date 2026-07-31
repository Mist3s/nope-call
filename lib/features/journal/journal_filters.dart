import 'package:flutter/material.dart';

import '../../data/nope_call_api.g.dart';
import '../../data/repository.dart';

/// Полный набор фильтров журнала (ТЗ §7.5).
///
/// Панель, а не отдельный экран: фильтр выбирают, глядя на список, и уводить с него незачем.
/// Быстрые фильтры по типу записи живут на самом экране — здесь то, что нужно реже:
/// период, наличие операторской подписи, правило, SIM.
class JournalFilterSheet extends StatefulWidget {
  const JournalFilterSheet({
    super.key,
    required this.filter,
    required this.sims,
  });

  final JournalFilterDto filter;
  final List<String> sims;

  @override
  State<JournalFilterSheet> createState() => _JournalFilterSheetState();
}

class _JournalFilterSheetState extends State<JournalFilterSheet> {
  final _repo = PlatformRepository();

  late String _kind = widget.filter.kind;
  late bool? _signature = widget.filter.hadSignature;
  late int? _fromAt = widget.filter.fromAt;
  late int? _toAt = widget.filter.toAt;
  late int? _ruleId = widget.filter.ruleId;
  late String? _sim = widget.filter.sim;

  List<RuleDto> _rules = const [];

  @override
  void initState() {
    super.initState();
    _loadRules();
  }

  Future<void> _loadRules() async {
    try {
      final rules = await _repo.rules();
      if (mounted) setState(() => _rules = rules.whereType<RuleDto>().toList());
    } catch (_) {
      // Фильтр по правилу — удобство: без списка правил панель остаётся рабочей.
    }
  }

  /// Периоды заданы относительно «сейчас», а не абсолютной датой: пользователь думает
  /// «за неделю», а не «с 23 июля».
  static const _periods = <String, int?>{
    'За всё время': null,
    'За сегодня': 0,
    'За 3 дня': 3,
    'За 7 дней': 7,
    'За 30 дней': 30,
  };

  void _setPeriod(int? days) {
    final now = DateTime.now();
    setState(() {
      _toAt = null;
      if (days == null) {
        _fromAt = null;
      } else if (days == 0) {
        _fromAt = DateTime(now.year, now.month, now.day).millisecondsSinceEpoch;
      } else {
        _fromAt = now.subtract(Duration(days: days)).millisecondsSinceEpoch;
      }
    });
  }

  /// Подпись кнопки своего диапазона.
  ///
  /// Пока диапазон не выбран — «Свой период», а не пересказ уже выбранного чипа: раньше
  /// здесь оказывалась подпись «За всё время», и в одной группе стояли два чипа
  /// с одинаковым текстом.
  String get _periodLabel {
    if (_toAt == null) return 'Свой период';
    final from = DateTime.fromMillisecondsSinceEpoch(_fromAt ?? 0);
    final to = DateTime.fromMillisecondsSinceEpoch(_toAt!);
    String d(DateTime v) =>
        '${v.day.toString().padLeft(2, '0')}.${v.month.toString().padLeft(2, '0')}';
    return '${d(from)} — ${d(to)}';
  }

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('Фильтры журнала', style: text.titleLarge),
            const SizedBox(height: 20),

            Text('Тип записи', style: text.labelLarge),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final entry in Labels.journalKinds.entries)
                  ChoiceChip(
                    label: Text(entry.value),
                    selected: _kind == entry.key,
                    onSelected: (_) => setState(() => _kind = entry.key),
                  ),
              ],
            ),
            const SizedBox(height: 20),

            Text('Период', style: text.labelLarge),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final entry in _periods.entries)
                  ChoiceChip(
                    label: Text(entry.key),
                    selected: _matchesPeriod(entry.value),
                    onSelected: (_) => _setPeriod(entry.value),
                  ),
                ActionChip(
                  avatar: const Icon(Icons.date_range, size: 18),
                  label: Text(_periodLabel),
                  onPressed: _pickRange,
                ),
              ],
            ),
            const SizedBox(height: 20),

            // Ключевой фильтр всего проекта: он отвечает на вопрос, доступна ли подпись
            // оператора к моменту проверки (ТЗ §21 п. 4).
            Text('Операторская подпись', style: text.labelLarge),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: [
                ChoiceChip(
                  label: const Text('Любая'),
                  selected: _signature == null,
                  onSelected: (_) => setState(() => _signature = null),
                ),
                ChoiceChip(
                  label: const Text('Была'),
                  selected: _signature == true,
                  onSelected: (_) => setState(() => _signature = true),
                ),
                ChoiceChip(
                  label: const Text('Не было'),
                  selected: _signature == false,
                  onSelected: (_) => setState(() => _signature = false),
                ),
              ],
            ),

            if (_rules.isNotEmpty) ...[
              const SizedBox(height: 20),
              Text('Сработавшее правило', style: text.labelLarge),
              const SizedBox(height: 8),
              DropdownButtonFormField<int?>(
                initialValue: _ruleId,
                isExpanded: true,
                decoration: const InputDecoration(border: OutlineInputBorder()),
                // Закрытое поле у DropdownButtonFormField — ровно одна строка: isDense
                // включён по умолчанию и жёстко задаёт высоту 24. Названия правил,
                // созданных из карточки звонка, длиннее («Заблокировать все номера,
                // начинающиеся с 84951»), поэтому вторая строка молча срезалась — и правило
                // по началу номера выглядело точно так же, как правило по окончанию.
                // Здесь ужимаем многоточием, а в раскрытом списке оставляем целиком.
                selectedItemBuilder: (context) => [
                  const Text('Любое', maxLines: 1),
                  for (final rule in _rules)
                    Text(
                      rule.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                ],
                items: [
                  const DropdownMenuItem(value: null, child: Text('Любое')),
                  for (final rule in _rules)
                    DropdownMenuItem(value: rule.id, child: Text(rule.title)),
                ],
                onChanged: (value) => setState(() => _ruleId = value),
              ),
            ],

            if (widget.sims.length > 1) ...[
              const SizedBox(height: 20),
              Text('SIM', style: text.labelLarge),
              const SizedBox(height: 8),
              DropdownButtonFormField<String?>(
                initialValue: _sim,
                isExpanded: true,
                decoration: const InputDecoration(border: OutlineInputBorder()),
                // Метка SIM приходит от системы как `phoneAccountId` и короткой быть
                // не обязана: на части прошивок это длинный идентификатор.
                selectedItemBuilder: (context) => [
                  const Text('Любая', maxLines: 1),
                  for (final sim in widget.sims)
                    Text(sim, maxLines: 1, overflow: TextOverflow.ellipsis),
                ],
                items: [
                  const DropdownMenuItem(value: null, child: Text('Любая')),
                  for (final sim in widget.sims)
                    DropdownMenuItem(value: sim, child: Text(sim)),
                ],
                onChanged: (value) => setState(() => _sim = value),
              ),
            ],

            const SizedBox(height: 24),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () => Navigator.of(
                      context,
                    ).pop(JournalFilterDto(kind: 'ALL')),
                    child: const Text('Сбросить'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton(
                    onPressed: () => Navigator.of(context).pop(
                      JournalFilterDto(
                        kind: _kind,
                        digitsQuery: widget.filter.digitsQuery,
                        nameQuery: widget.filter.nameQuery,
                        hadSignature: _signature,
                        fromAt: _fromAt,
                        toAt: _toAt,
                        ruleId: _ruleId,
                        sim: _sim,
                      ),
                    ),
                    child: const Text('Применить'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  bool _matchesPeriod(int? days) {
    if (days == null) return _fromAt == null && _toAt == null;
    if (_fromAt == null || _toAt != null) return false;
    final from = DateTime.fromMillisecondsSinceEpoch(_fromAt!);
    final diff = DateTime.now().difference(from).inDays;
    return days == 0 ? diff == 0 : diff == days;
  }

  Future<void> _pickRange() async {
    final now = DateTime.now();
    final range = await showDateRangePicker(
      context: context,
      firstDate: DateTime(now.year - 2),
      lastDate: now,
    );
    if (range == null) return;
    setState(() {
      _fromAt = range.start.millisecondsSinceEpoch;
      // Конец диапазона — конец суток, иначе выбор «сегодня–сегодня» не покажет ничего.
      _toAt = DateTime(
        range.end.year,
        range.end.month,
        range.end.day,
        23,
        59,
        59,
      ).millisecondsSinceEpoch;
    });
  }
}
