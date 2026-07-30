import 'package:flutter/material.dart';

import '../../data/nope_call_api.g.dart';
import '../../data/repository.dart';
import '../../widgets/async_view.dart';
import 'call_card.dart';

/// Журнал (ТЗ §9.2).
///
/// Показывает только собственные проверки приложения — и говорит об этом прямо. Исход звонка,
/// длительность и исходящие берутся из системного журнала, а он требует отдельного разрешения:
/// `CallScreeningService` вызывается один раз, до звонка, и ничего этого не узнаёт (ТЗ §7.2).
class JournalScreen extends StatefulWidget {
  const JournalScreen({super.key});

  @override
  State<JournalScreen> createState() => _JournalScreenState();
}

class _JournalScreenState extends State<JournalScreen> {
  final _repo = PlatformRepository();
  final _items = <JournalItemDto>[];
  final _scroll = ScrollController();

  Loadable<List<JournalItemDto>> _state = const Loadable(loading: true);
  int? _nextTime;
  int? _nextId;
  bool _hasMore = true;
  bool _loadingMore = false;
  bool _onlyBlocked = false;

  @override
  void initState() {
    super.initState();
    _loadFirst();
    _scroll.addListener(() {
      if (_scroll.position.pixels > _scroll.position.maxScrollExtent - 400) {
        _loadMore();
      }
    });
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  /// Первая страница. Обновление тихое: список остаётся на экране, пока идёт запрос —
  /// иначе возврат из карточки звонка ронял бы журнал в спиннер.
  Future<void> _loadFirst() async {
    setState(() => _state = Loadable(data: _state.data, loading: true));
    try {
      final page = await _repo.journalPage();
      if (!mounted) return;
      _items
        ..clear()
        ..addAll(page.items.whereType<JournalItemDto>());
      _nextTime = page.nextBeforeTime;
      _nextId = page.nextBeforeId;
      _hasMore = page.hasMore;
      setState(() => _state = Loadable(data: List.of(_items)));
    } catch (e) {
      if (!mounted) return;
      setState(() => _state = Loadable(data: _state.data, error: e));
    }
  }

  Future<void> _loadMore() async {
    if (!_hasMore || _loadingMore || _nextTime == null) return;
    _loadingMore = true;
    final page = await _repo.journalPage(
      beforeTime: _nextTime,
      beforeId: _nextId,
    );
    if (!mounted) return;
    setState(() {
      _items.addAll(page.items.whereType<JournalItemDto>());
      _nextTime = page.nextBeforeTime;
      _nextId = page.nextBeforeId;
      _hasMore = page.hasMore;
      _loadingMore = false;
      _state = Loadable(data: List.of(_items));
    });
  }

  Future<void> _openCard(JournalItemDto item) async {
    // Правило, созданное из карточки, действует сразу — журнал надо перечитать, чтобы
    // счётчики и статусы не расходились с реальностью.
    final created = await Navigator.of(
      context,
    ).push<bool>(MaterialPageRoute(builder: (_) => CallCardScreen(item: item)));
    if (created == true && mounted) await _loadFirst();
  }

  List<JournalItemDto> get _visible =>
      _onlyBlocked ? _items.where((i) => i.blockedByUs).toList() : _items;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Журнал'),
        actions: [
          IconButton(
            tooltip: _onlyBlocked ? 'Показать все' : 'Только заблокированные',
            icon: Icon(
              _onlyBlocked ? Icons.filter_alt : Icons.filter_alt_outlined,
            ),
            onPressed: () {
              setState(() => _onlyBlocked = !_onlyBlocked);
            },
          ),
        ],
      ),
      body: LoadableView<List<JournalItemDto>>(
        state: _state,
        onRetry: _loadFirst,
        builder: (context, items) {
          if (items.isEmpty) {
            return const EmptyState(
              icon: Icons.history_outlined,
              title: 'Проверок пока не было',
              description:
                  'Здесь появятся звонки, которые проверило приложение. Записи '
                  'создаются в момент звонка — журнал не заполняется задним числом.',
            );
          }
          final visible = _visible;
          return RefreshIndicator(
            onRefresh: _loadFirst,
            child: ListView.separated(
              controller: _scroll,
              itemCount: visible.length + 1,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, i) {
                if (i == visible.length) return _Footer(hasMore: _hasMore);
                return _JournalTile(
                  item: visible[i],
                  onTap: () => _openCard(visible[i]),
                );
              },
            ),
          );
        },
      ),
    );
  }
}

class _JournalTile extends StatelessWidget {
  const _JournalTile({required this.item, required this.onTap});

  final JournalItemDto item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final blocked = item.blockedByUs;
    final title = item.nameRaw?.isNotEmpty == true
        ? item.nameRaw!
        : (item.rawNumber.isNotEmpty ? item.rawNumber : 'Скрытый номер');

    return ListTile(
      onTap: onTap,
      leading: Icon(
        blocked ? Icons.block : Icons.call_received,
        color: blocked ? scheme.error : scheme.primary,
      ),
      title: Text(title),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (item.nameRaw?.isNotEmpty == true && item.rawNumber.isNotEmpty)
            Text(item.rawNumber, style: Theme.of(context).textTheme.bodySmall),
          Text(
            '${formatTime(item.occurredAt)} · ${Labels.reason(item.reason)}'
            '${item.matchedRuleTitle != null ? ' «${item.matchedRuleTitle}»' : ''}',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
      trailing: item.hadSignature
          ? Tooltip(
              message: 'Была подпись оператора',
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
                'Показаны только проверки, выполненные приложением. Исход звонка '
                'и длительность приходят из системного журнала — для этого нужен '
                'доступ к журналу звонков.',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
      ),
    );
  }
}
