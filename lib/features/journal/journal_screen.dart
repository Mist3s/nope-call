import 'dart:async';

import 'package:flutter/material.dart';

import '../../data/nope_call_api.g.dart';
import '../../data/repository.dart';
import '../../widgets/async_view.dart';
import 'call_card.dart';
import 'journal_visuals.dart';
import 'journal_filters.dart';

/// Журнал (ТЗ §9.2).
///
/// Показывает объединение двух слоёв: собственные проверки и записи системного журнала.
/// Без доступа к системному журналу второй слой пуст — тогда журнал честно говорит, что
/// исход звонка и длительность неизвестны: `CallScreeningService` вызывается один раз,
/// **до** звонка, и ничего этого не узнаёт (ТЗ §7.2).
class JournalScreen extends StatefulWidget {
  const JournalScreen({super.key});

  @override
  State<JournalScreen> createState() => _JournalScreenState();
}

class _JournalScreenState extends State<JournalScreen> {
  final _repo = PlatformRepository();
  final _items = <JournalItemDto>[];
  final _scroll = ScrollController();
  final _search = TextEditingController();

  /// Пауза перед применением поиска.
  ///
  /// Раньше поиск применялся **только** по нажатию «готово» на клавиатуре: пользователь
  /// набирал текст и не понимал, почему список не меняется. Каждый набранный символ —
  /// это запрос к базе с двумя `LIKE`, поэтому не сразу, а после паузы.
  Timer? _searchDebounce;

  Loadable<List<JournalItemDto>> _state = const Loadable(loading: true);
  JournalCursorDto? _next;
  bool _hasMore = true;
  bool _loadingMore = false;
  bool _searchOpen = false;
  List<SimDto> _sims = const [];

  var _filter = JournalFilterDto(kind: 'ALL');

  @override
  void initState() {
    super.initState();
    _loadFirst();
    _loadSims();
    _scroll.addListener(() {
      if (_scroll.position.pixels > _scroll.position.maxScrollExtent - 400) {
        _loadMore();
      }
    });
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _scroll.dispose();
    _search.dispose();
    super.dispose();
  }

  Future<void> _loadSims() async {
    try {
      final sims = await _repo.journalSims();
      if (mounted) setState(() => _sims = sims.whereType<SimDto>().toList());
    } catch (_) {
      // Фильтр по SIM — удобство. Его отсутствие не должно ронять журнал.
    }
  }

  /// Первая страница. Обновление тихое: список остаётся на экране, пока идёт запрос —
  /// иначе возврат из карточки звонка ронял бы журнал в спиннер.
  Future<void> _loadFirst() async {
    setState(() => _state = Loadable(data: _state.data, loading: true));
    try {
      final page = await _repo.journalPage(filter: _filter);
      if (!mounted) return;
      _items
        ..clear()
        ..addAll(page.items.whereType<JournalItemDto>());
      _next = page.next;
      _hasMore = page.hasMore;
      setState(() => _state = Loadable(data: List.of(_items)));
    } catch (e) {
      if (!mounted) return;
      setState(() => _state = Loadable(data: _state.data, error: e));
    }
  }

  /// Обновление жестом: сначала синхронизируем зеркало. Разрешение могли выдать только что,
  /// и ждать перезапуска приложения ради этого незачем.
  Future<void> _refresh() async {
    try {
      await _repo.syncCallLog();
    } catch (_) {
      // Нет доступа к системному журналу — это нормальное состояние, не ошибка.
    }
    await _loadFirst();
    await _loadSims();
  }

  Future<void> _loadMore() async {
    if (!_hasMore || _loadingMore || _next == null) return;
    _loadingMore = true;
    try {
      final page = await _repo.journalPage(filter: _filter, cursor: _next);
      if (!mounted) return;
      setState(() {
        _items.addAll(page.items.whereType<JournalItemDto>());
        _next = page.next;
        _hasMore = page.hasMore;
        _state = Loadable(data: List.of(_items));
      });
    } finally {
      _loadingMore = false;
    }
  }

  void _applyFilter(JournalFilterDto filter) {
    setState(() => _filter = filter);
    _loadFirst();
  }

  Future<void> _openCard(JournalItemDto item) async {
    // Правило, созданное из карточки, действует сразу — журнал надо перечитать, чтобы
    // счётчики и статусы не расходились с реальностью.
    final created = await Navigator.of(
      context,
    ).push<bool>(MaterialPageRoute(builder: (_) => CallCardScreen(item: item)));
    if (created == true && mounted) await _loadFirst();
  }

  Future<void> _hide(JournalItemDto item) async {
    final systemId = item.systemId;
    if (systemId == null) return;
    await _repo.hideJournalRecord(systemId);
    if (!mounted) return;
    // Локально, без перезапроса: скрытие — точечное изменение, и мигать списком незачем.
    setState(() {
      _items.removeWhere(
        (i) => i.sourceRank == item.sourceRank && i.id == item.id,
      );
      _state = Loadable(data: List.of(_items));
    });
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Запись скрыта. Системный журнал Android не изменён'),
      ),
    );
  }

  Future<void> _openFilters() async {
    final result = await showModalBottomSheet<JournalFilterDto>(
      context: context,
      isScrollControlled: true,
      builder: (_) => JournalFilterSheet(filter: _filter, sims: _sims),
    );
    if (result != null) _applyFilter(result);
  }

  @override
  Widget build(BuildContext context) {
    final filtered = !_isDefault(_filter);
    return Scaffold(
      appBar: AppBar(
        title: _searchOpen
            ? TextField(
                controller: _search,
                autofocus: true,
                keyboardType: TextInputType.text,
                decoration: const InputDecoration(
                  hintText: 'Номер или название',
                  border: InputBorder.none,
                ),
                onChanged: _onSearchChanged,
                onSubmitted: (value) {
                  // Нажатие «готово» применяет немедленно, не дожидаясь паузы.
                  _searchDebounce?.cancel();
                  _applyFilter(_copyWith(_filter, search: value.trim()));
                },
              )
            : const Text('Журнал'),
        actions: [
          IconButton(
            tooltip: _searchOpen ? 'Закрыть поиск' : 'Поиск',
            icon: Icon(_searchOpen ? Icons.close : Icons.search),
            onPressed: () {
              setState(() => _searchOpen = !_searchOpen);
              if (!_searchOpen &&
                  (_filter.digitsQuery != null || _filter.nameQuery != null)) {
                _search.clear();
                _applyFilter(_copyWith(_filter, search: ''));
              }
            },
          ),
          IconButton(
            tooltip: 'Фильтры',
            icon: Badge(
              isLabelVisible: filtered,
              child: const Icon(Icons.filter_list),
            ),
            onPressed: _openFilters,
          ),
        ],
      ),
      body: Column(
        children: [
          _KindBar(
            selected: _filter.kind,
            onSelected: (kind) => _applyFilter(_copyWith(_filter, kind: kind)),
          ),
          Expanded(
            child: LoadableView<List<JournalItemDto>>(
              state: _state,
              onRetry: _loadFirst,
              builder: (context, items) {
                if (items.isEmpty) {
                  return RefreshIndicator(
                    onRefresh: _refresh,
                    child: ListView(
                      children: [
                        SizedBox(
                          height: MediaQuery.sizeOf(context).height * 0.6,
                          child: filtered
                              ? EmptyState(
                                  icon: Icons.filter_list_off,
                                  title: 'Под фильтр ничего не попало',
                                  description:
                                      'Записи есть, но выбранные условия их не '
                                      'пропускают. Сбросьте фильтры, чтобы увидеть всё.',
                                  action: OutlinedButton(
                                    onPressed: () => _applyFilter(
                                      JournalFilterDto(kind: 'ALL'),
                                    ),
                                    child: const Text('Сбросить фильтры'),
                                  ),
                                )
                              : const EmptyState(
                                  icon: Icons.history_outlined,
                                  title: 'Записей пока нет',
                                  description:
                                      'Здесь появятся проверенные звонки. Записи создаются '
                                      'в момент звонка — журнал не заполняется задним числом.',
                                ),
                        ),
                      ],
                    ),
                  );
                }
                return RefreshIndicator(
                  onRefresh: _refresh,
                  child: ListView.separated(
                    controller: _scroll,
                    itemCount: items.length + 1,
                    separatorBuilder: (_, _) => const Divider(height: 1),
                    itemBuilder: (context, i) {
                      if (i == items.length) return _Footer(hasMore: _hasMore);
                      final item = items[i];
                      return _JournalTile(
                        item: item,
                        onTap: () => _openCard(item),
                        onHide: item.systemId != null
                            ? () => _hide(item)
                            : null,
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  static bool _isDefault(JournalFilterDto f) =>
      f.kind == 'ALL' &&
      f.digitsQuery == null &&
      f.nameQuery == null &&
      f.hadSignature == null &&
      f.fromAt == null &&
      f.toAt == null &&
      f.ruleId == null &&
      f.sim == null;

  void _onSearchChanged(String value) {
    _searchDebounce?.cancel();
    _searchDebounce = Timer(
      const Duration(milliseconds: 300),
      () => _applyFilter(_copyWith(_filter, search: value.trim())),
    );
  }

  /// Поиск один, а полей два: цифры ищутся по номеру, всё остальное — по названию.
  /// Разделение здесь, а не в Kotlin, потому что это решение интерфейса, а не модели.
  JournalFilterDto _copyWith(
    JournalFilterDto f, {
    String? kind,
    String? search,
  }) {
    String? digits = f.digitsQuery;
    String? name = f.nameQuery;
    if (search != null) {
      // Правило простое и предсказуемое: есть буквы — ищем по названию, нет — по номеру.
      // Раньше здесь стояло «цифр не меньше, чем длина минус три», и запрос «Мама 8»
      // оказывался поиском по названию «Мама 8», которого в свёрнутой форме не существует.
      // Оба поля одновременно заполнять нельзя: в запросе они соединены через AND.
      final hasLetters = RegExp(r'\p{L}', unicode: true).hasMatch(search);
      final onlyDigits = search.replaceAll(RegExp(r'[^0-9]'), '');
      if (search.isEmpty) {
        digits = null;
        name = null;
      } else if (hasLetters) {
        digits = null;
        name = search;
      } else {
        digits = onlyDigits.isEmpty ? null : onlyDigits;
        name = null;
      }
    }
    return JournalFilterDto(
      kind: kind ?? f.kind,
      digitsQuery: digits,
      nameQuery: name,
      hadSignature: f.hadSignature,
      fromAt: f.fromAt,
      toAt: f.toAt,
      ruleId: f.ruleId,
      sim: f.sim,
    );
  }
}

/// Быстрые фильтры по типу записи. Всегда на экране: это самый частый вопрос к журналу.
class _KindBar extends StatelessWidget {
  const _KindBar({required this.selected, required this.onSelected});

  final String selected;
  final ValueChanged<String> onSelected;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Row(
        children: [
          for (final entry in Labels.journalKinds.entries)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ChoiceChip(
                label: Text(entry.value),
                selected: selected == entry.key,
                onSelected: (_) => onSelected(entry.key),
              ),
            ),
        ],
      ),
    );
  }
}

class _JournalTile extends StatelessWidget {
  const _JournalTile({required this.item, required this.onTap, this.onHide});

  final JournalItemDto item;
  final VoidCallback onTap;
  final VoidCallback? onHide;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final small = Theme.of(context).textTheme.bodySmall;
    final title = item.nameRaw?.isNotEmpty == true
        ? item.nameRaw!
        : (item.rawNumber.isNotEmpty ? item.rawNumber : 'Скрытый номер');

    final visual = CallVisual.of(item.kind, scheme);
    final details = <String>[
      formatTime(item.occurredAt),
      Labels.shortKind(item.kind),
      if (item.matchedRuleTitle != null) '«${item.matchedRuleTitle}»',
      if (item.durationSeconds != null && item.durationSeconds! > 0)
        _duration(item.durationSeconds!),
    ];

    return ListTile(
      onTap: onTap,
      onLongPress: onHide,
      leading: Icon(visual.icon, color: visual.color),
      title: Text(title),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (item.nameRaw?.isNotEmpty == true && item.rawNumber.isNotEmpty)
            Text(item.rawNumber, style: small),
          Text(details.join(' · '), style: small),
        ],
      ),
      trailing: item.hadSignature
          ? Tooltip(
              message: 'Подпись оператора была в момент проверки',
              child: Icon(
                Icons.badge_outlined,
                size: 18,
                color: scheme.outline,
              ),
            )
          : null,
      isThreeLine:
          item.nameRaw?.isNotEmpty == true && item.rawNumber.isNotEmpty,
    );
  }

  static String _duration(int seconds) {
    if (seconds < 60) return '$seconds с';
    return '${seconds ~/ 60} мин ${seconds % 60} с';
  }
}

class _Footer extends StatelessWidget {
  const _Footer({required this.hasMore});

  final bool hasMore;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 20, horizontal: 16),
      child: Center(
        child: hasMore
            ? const CircularProgressIndicator()
            : Text(
                'Записи без доступа к системному журналу показывают только сам факт '
                'проверки: исход звонка и длительность приходят из журнала Android.',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
      ),
    );
  }
}
