import 'package:flutter/material.dart';

import '../../data/repository.dart';
import '../../widgets/async_view.dart';

/// Настройки (ТЗ §9.6).
///
/// Каждая настройка, влияющая на решение по звонку, пересобирает снимок правил на стороне
/// Kotlin — иначе она бы не действовала до перезапуска. Это делает `putSetting`.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final _repo = PlatformRepository();
  Loadable<Map<String, String>> _state = const Loadable(loading: true);

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _state = Loadable(data: _state.data, loading: true));
    try {
      final settings = await _repo.settings();
      if (!mounted) return;
      setState(() => _state = Loadable(data: settings));
    } catch (e) {
      if (!mounted) return;
      setState(() => _state = Loadable(data: _state.data, error: e));
    }
  }

  /// Значение применяется на экране сразу, а платформа догоняет: иначе переключатель
  /// возвращался бы назад на время пересборки снимка правил.
  Future<void> _put(String key, String value) async {
    final current = _state.data;
    if (current != null) {
      setState(() {
        _state = Loadable(data: {...current, key: value}, loading: true);
      });
    }
    await _repo.putSetting(key, value);
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Настройки')),
      body: LoadableView<Map<String, String>>(
        state: _state,
        onRetry: _load,
        builder: (context, settings) {
          final blocking = settings['blocking_enabled'] != 'false';
          final defaultAction = settings['default_action'] ?? 'ALLOW';
          final restricted = settings['restricted_action'] ?? 'ALLOW';
          final unknown = settings['unknown_action'] ?? 'ALLOW';

          return ListView(
            children: [
              SwitchListTile(
                value: blocking,
                onChanged: (v) => _put('blocking_enabled', v.toString()),
                title: const Text('Блокировка включена'),
                subtitle: const Text(
                  'Общий выключатель. Правила сохраняются и заработают снова после включения.',
                ),
              ),
              const Divider(),
              const _SectionTitle('Когда правило не совпало'),
              _ActionTile(
                title: 'Действие по умолчанию',
                subtitle: defaultAction == 'ALLOW'
                    ? 'Звонок проходит — так работает принцип «блокируем только при уверенности»'
                    : 'Внимание: пройдут только явно разрешённые звонки',
                value: defaultAction,
                onChanged: (v) => _put('default_action', v),
              ),
              const Divider(),
              const _SectionTitle('Особые случаи'),
              _ActionTile(
                title: 'Скрытый номер',
                subtitle: 'Номер не передан: сопоставлять нечем',
                value: restricted,
                onChanged: (v) => _put('restricted_action', v),
              ),
              _ActionTile(
                title: 'Номер не определён',
                subtitle: 'Система не смогла определить номер',
                value: unknown,
                onChanged: (v) => _put('unknown_action', v),
              ),
              const Divider(),
              const _AboutCard(),
            ],
          );
        },
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      child: Text(
        text,
        style: Theme.of(context).textTheme.titleSmall?.copyWith(
          color: Theme.of(context).colorScheme.primary,
        ),
      ),
    );
  }
}

class _ActionTile extends StatelessWidget {
  const _ActionTile({
    required this.title,
    required this.subtitle,
    required this.value,
    required this.onChanged,
  });

  final String title;
  final String subtitle;
  final String value;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      title: Text(title),
      subtitle: Text(subtitle),
      trailing: DropdownButton<String>(
        value: value,
        underline: const SizedBox.shrink(),
        items: [
          for (final e in Labels.actions.entries)
            DropdownMenuItem(value: e.key, child: Text(e.value)),
        ],
        onChanged: (v) {
          if (v != null) onChanged(v);
        },
      ),
    );
  }
}

class _AboutCard extends StatelessWidget {
  const _AboutCard();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Text(
        'Всё работает офлайн. При проверке звонка приложение не обращается к сети, '
        'номера и журнал никуда не передаются, резервное копирование выключено.\n\n'
        'Приложение блокирует звонок только при совпадении вашего правила. Если правило '
        'не совпало или что-то отказало — звонок проходит.',
        style: Theme.of(
          context,
        ).textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
      ),
    );
  }
}
