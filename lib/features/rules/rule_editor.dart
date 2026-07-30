import 'dart:async';

import 'package:flutter/material.dart';

import '../../data/nope_call_api.g.dart';
import '../../data/repository.dart';

/// Редактор правила (ТЗ §9.5).
///
/// Две вещи здесь важнее удобства ввода.
///
/// **Показ вариантов написания.** Если правило ищет не только то, что набрал пользователь,
/// он обязан это видеть: ложное срабатывание означает пропущенный звонок от врача, и объяснить
/// его потом можно только списком того, что искалось (ТЗ §6.3.2).
///
/// **Предупреждения о ненадёжных типах.** Операторская подпись обрезается на 32 символах,
/// поэтому «заканчивается на» и «точное совпадение» по названию работают через раз — и молчать
/// об этом нельзя.
class RuleEditorScreen extends StatefulWidget {
  const RuleEditorScreen({super.key, this.rule});

  final RuleDto? rule;

  @override
  State<RuleEditorScreen> createState() => _RuleEditorScreenState();
}

class _RuleEditorScreenState extends State<RuleEditorScreen> {
  final _repo = PlatformRepository();
  final _titleController = TextEditingController();
  final _patternController = TextEditingController();
  final _commentController = TextEditingController();

  String _target = 'NUMBER';
  String _matchType = 'PREFIX';
  String _action = 'REJECT';
  bool _enabled = true;

  PatternCheckResult? _check;
  PreviewDto? _preview;
  Timer? _debounce;
  bool _saving = false;

  bool get _isEdit => widget.rule != null;

  @override
  void initState() {
    super.initState();
    final rule = widget.rule;
    if (rule != null) {
      _titleController.text = rule.title;
      _patternController.text = rule.pattern;
      _commentController.text = rule.comment ?? '';
      _target = rule.targetType;
      _matchType = rule.matchType;
      _action = rule.action;
      _enabled = rule.isEnabled;
    }
    _patternController.addListener(_onPatternChanged);
    if (_patternController.text.isNotEmpty) _revalidate();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _titleController.dispose();
    _patternController.dispose();
    _commentController.dispose();
    super.dispose();
  }

  void _onPatternChanged() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 250), _revalidate);
  }

  Future<void> _revalidate() async {
    final pattern = _patternController.text;
    if (pattern.isEmpty) {
      setState(() {
        _check = null;
        _preview = null;
      });
      return;
    }
    final check = await _repo.checkPattern(_target, _matchType, pattern);
    if (!mounted) return;
    setState(() {
      _check = check;
    });

    if (check.valid) {
      final preview = await _repo.preview(_target, _matchType, pattern);
      if (mounted) {
        setState(() {
          _preview = preview;
        });
      }
    } else {
      setState(() {
        _preview = null;
      });
    }
  }

  List<String> get _matchTypesForTarget {
    if (_target == 'CONTACT') return ['IN_CONTACTS'];
    if (_target == 'NUMBER') {
      return ['EXACT', 'PREFIX', 'SUFFIX', 'CONTAINS', 'REGEX'];
    }
    return ['EXACT', 'PREFIX', 'SUFFIX', 'CONTAINS', 'TOKEN', 'REGEX'];
  }

  /// Предупреждения о том, что тип сопоставления ненадёжен для выбранного источника.
  String? get _reliabilityWarning {
    final isName = _target.startsWith('NAME');
    if (!isName) return null;
    if (_matchType == 'SUFFIX') {
      return 'Подпись оператора обрезается на 32 символах, поэтому «заканчивается на» '
          'по названию срабатывает не всегда. Надёжнее «содержит» или «содержит слово».';
    }
    if (_matchType == 'EXACT') {
      return 'Длинные названия обрезаются на 32 символах, и точное совпадение тогда '
          'не срабатывает. Надёжнее «содержит слово».';
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final check = _check;
    final warning = _reliabilityWarning;

    return Scaffold(
      appBar: AppBar(
        title: Text(_isEdit ? 'Правило' : 'Новое правило'),
        actions: [
          TextButton(
            onPressed: _saving ? null : _save,
            child: const Text('Сохранить'),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          TextField(
            controller: _titleController,
            decoration: const InputDecoration(
              labelText: 'Название правила',
              helperText: 'Чтобы потом понять, зачем оно',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 16),
          _Dropdown(
            label: 'Что проверяем',
            value: _target,
            items: Labels.targets,
            onChanged: (v) {
              setState(() {
                _target = v;
                if (!_matchTypesForTarget.contains(_matchType)) {
                  _matchType = _matchTypesForTarget.first;
                }
              });
              _revalidate();
            },
          ),
          const SizedBox(height: 16),
          _Dropdown(
            label: 'Как сравниваем',
            value: _matchType,
            items: {
              for (final k in _matchTypesForTarget) k: Labels.matchType(k),
            },
            onChanged: (v) {
              setState(() {
                _matchType = v;
              });
              _revalidate();
            },
          ),
          if (warning != null) ...[
            const SizedBox(height: 12),
            _Note(text: warning, icon: Icons.info_outline),
          ],
          if (_target != 'CONTACT') ...[
            const SizedBox(height: 16),
            TextField(
              controller: _patternController,
              decoration: InputDecoration(
                labelText: 'Значение',
                hintText: _target == 'NUMBER' ? '8495 или +7495' : 'реклама',
                border: const OutlineInputBorder(),
                errorText: check != null && !check.valid ? check.error : null,
              ),
            ),
          ],
          if (check != null && check.valid) ...[
            const SizedBox(height: 12),
            _CanonicalNote(check: check, target: _target),
          ],
          if (_preview != null) ...[
            const SizedBox(height: 12),
            _PreviewNote(preview: _preview!),
          ],
          const SizedBox(height: 16),
          _Dropdown(
            label: 'Что делать',
            value: _action,
            items: Labels.actions,
            onChanged: (v) => setState(() {
              _action = v;
            }),
          ),
          const SizedBox(height: 8),
          Text(
            Labels.actionHints[_action] ?? '',
            style: Theme.of(
              context,
            ).textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
          ),
          const SizedBox(height: 16),
          SwitchListTile(
            value: _enabled,
            onChanged: (v) => setState(() {
              _enabled = v;
            }),
            title: const Text('Правило включено'),
            contentPadding: EdgeInsets.zero,
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _commentController,
            decoration: const InputDecoration(
              labelText: 'Комментарий',
              border: OutlineInputBorder(),
            ),
            maxLines: 2,
          ),
        ],
      ),
    );
  }

  Future<void> _save() async {
    final pattern = _patternController.text;
    if (_target != 'CONTACT' && pattern.trim().isEmpty) {
      _showError('Заполните значение');
      return;
    }
    setState(() {
      _saving = true;
    });
    final result = await _repo.saveRule(
      id: widget.rule?.id,
      title: _titleController.text.trim().isEmpty
          ? pattern
          : _titleController.text.trim(),
      targetType: _target,
      matchType: _matchType,
      pattern: pattern,
      action: _action,
      enabled: _enabled,
      comment: _commentController.text.trim().isEmpty
          ? null
          : _commentController.text.trim(),
    );
    if (!mounted) return;
    setState(() {
      _saving = false;
    });

    if (!result.saved) {
      _showError(result.error ?? 'Не удалось сохранить правило');
      return;
    }
    Navigator.of(context).pop(true);
  }

  void _showError(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }
}

class _Dropdown extends StatelessWidget {
  const _Dropdown({
    required this.label,
    required this.value,
    required this.items,
    required this.onChanged,
  });

  final String label;
  final String value;
  final Map<String, String> items;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return DropdownButtonFormField<String>(
      initialValue: items.containsKey(value) ? value : items.keys.first,
      decoration: InputDecoration(
        labelText: label,
        border: const OutlineInputBorder(),
      ),
      items: [
        for (final e in items.entries)
          DropdownMenuItem(value: e.key, child: Text(e.value)),
      ],
      onChanged: (v) {
        if (v != null) onChanged(v);
      },
    );
  }
}

/// Показывает, во что превратился шаблон и что именно правило будет искать.
class _CanonicalNote extends StatelessWidget {
  const _CanonicalNote({required this.check, required this.target});

  final PatternCheckResult check;
  final String target;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final variants = check.variants.whereType<String>().toList();
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Сравнивается как: ${check.canonical}',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          if (variants.length > 1) ...[
            const SizedBox(height: 8),
            Text(
              'Правило будет искать ${variants.length} написаний — '
              'у одной и той же организации транслитерация бывает разной:',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 6),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final v in variants.take(12))
                  Chip(
                    label: Text(v, style: const TextStyle(fontSize: 11)),
                    visualDensity: VisualDensity.compact,
                    padding: EdgeInsets.zero,
                  ),
                if (variants.length > 12)
                  Chip(
                    label: Text('и ещё ${variants.length - 12}'),
                    visualDensity: VisualDensity.compact,
                  ),
              ],
            ),
            if (check.variantsTruncated)
              Text(
                'Вариантов слишком много, часть отброшена — сделайте шаблон точнее.',
                style: Theme.of(
                  context,
                ).textTheme.bodySmall?.copyWith(color: scheme.error),
              ),
          ],
        ],
      ),
    );
  }
}

class _PreviewNote extends StatelessWidget {
  const _PreviewNote({required this.preview});

  final PreviewDto preview;

  @override
  Widget build(BuildContext context) {
    final count = preview.count;
    final prefix = preview.truncated ? '≥ ' : '';
    return _Note(
      icon: Icons.search,
      text: count == 0
          ? 'В журнале нет записей, подходящих под это правило'
          : 'Под правило попадает $prefix$count '
                '${_plural(count)} в журнале',
    );
  }

  String _plural(int n) {
    if (n % 10 == 1 && n % 100 != 11) return 'запись';
    if ([2, 3, 4].contains(n % 10) && !(n % 100 >= 12 && n % 100 <= 14)) {
      return 'записи';
    }
    return 'записей';
  }
}

class _Note extends StatelessWidget {
  const _Note({required this.text, required this.icon});

  final String text;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 16, color: scheme.onSurfaceVariant),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            text,
            style: Theme.of(
              context,
            ).textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
          ),
        ),
      ],
    );
  }
}
