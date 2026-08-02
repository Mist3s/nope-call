import 'package:flutter/material.dart';

import '../../data/nope_call_api.g.dart';
import '../../data/repository.dart';

/// «О приложении» и обновление (ТЗ §15.5).
///
/// Статус проверки виден **только здесь**. Автопроверка при запуске тихая и не показывает
/// всплывающих окон: нет сети или GitHub недоступен — это не событие, требующее внимания
/// посреди работы. При ручной проверке причина отказа тоже показывается тут, а не модально.
class AboutScreen extends StatefulWidget {
  const AboutScreen({super.key});

  @override
  State<AboutScreen> createState() => _AboutScreenState();
}

class _AboutScreenState extends State<AboutScreen> {
  final _repo = PlatformRepository();

  UpdateStatusDto? _status;
  bool _checking = false;
  bool _installing = false;
  bool _prerelease = false;
  String? _installError;

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    try {
      final settings = await _repo.settings();
      if (!mounted) return;
      setState(() => _prerelease = settings['updates_prerelease'] == 'true');
      await _check(silent: true);
    } catch (_) {
      // Экран обязан открываться даже если платформенная часть недоступна.
    }
  }

  Future<void> _check({bool silent = false}) async {
    setState(() {
      _checking = true;
      if (!silent) _installError = null;
    });
    try {
      final status = await _repo.checkUpdate(
        allowPrerelease: _prerelease,
        silent: silent,
      );
      if (mounted) setState(() => _status = status);
    } finally {
      if (mounted) setState(() => _checking = false);
    }
  }

  Future<void> _install() async {
    setState(() {
      _installing = true;
      _installError = null;
    });
    try {
      final error = await _repo.installUpdate(allowPrerelease: _prerelease);
      if (mounted) setState(() => _installError = error);
    } finally {
      if (mounted) setState(() => _installing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final status = _status;

    return Scaffold(
      appBar: AppBar(title: const Text('О приложении')),
      body: ListView(
        padding: const EdgeInsets.only(bottom: 32),
        children: [
          Card(
            margin: const EdgeInsets.all(16),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Приём', style: theme.textTheme.titleLarge),
                  const SizedBox(height: 4),
                  Text(
                    'Блокировка входящих звонков по вашим правилам. Раздаётся напрямую, '
                    'без магазина приложений.',
                    style: theme.textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    // «0.1.0+1» — это версия и код сборки, склеенные через плюс так, как их
                    // держит pubspec. Пользователю плюс читается как опечатка, а код сборки
                    // всё равно нужен поддержке, поэтому он назван словом.
                    'Установлена версия ${_readable(status?.currentVersion)}',
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
          ),
          _UpdateCard(
            status: status,
            checking: _checking,
            installing: _installing,
            installError: _installError,
            onCheck: () => _check(),
            onInstall: _install,
            onOpenNotes: (url) => _repo.openReleasePage(url),
          ),
          SwitchListTile(
            value: _prerelease,
            onChanged: (v) async {
              setState(() => _prerelease = v);
              await _repo.putSetting('updates_prerelease', v.toString());
              await _check();
            },
            title: const Text('Предварительные версии'),
            subtitle: const Text(
              'Показывать выпуски с пометкой rc. Обычно они не нужны',
            ),
          ),
          const Divider(),
          Padding(
            padding: const EdgeInsets.all(16),
            child: Text(
              'Обновление скачивается из релизов проекта и устанавливается системным '
              'установщиком: подменить себя молча приложение не может. Перед установкой '
              'сверяются контрольная сумма файла и отпечаток сертификата — иначе установка '
              'отменяется.\n\n'
              'Сеть используется только здесь. Часть приложения, которая проверяет звонки '
              'и ведёт журнал, доступа к сети не имеет — это свойство сборки, а не обещание.',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// `0.1.0+1` → `0.1.0 (сборка 1)`. Если формат окажется другим — отдаём как есть,
/// потому что выдумывать за него нельзя.
String _readable(String? version) {
  if (version == null || version.isEmpty) return '—';
  final plus = version.indexOf('+');
  if (plus <= 0 || plus == version.length - 1) return version;
  return '${version.substring(0, plus)} (сборка ${version.substring(plus + 1)})';
}

class _UpdateCard extends StatelessWidget {
  const _UpdateCard({
    required this.status,
    required this.checking,
    required this.installing,
    required this.installError,
    required this.onCheck,
    required this.onInstall,
    required this.onOpenNotes,
  });

  final UpdateStatusDto? status;
  final bool checking;
  final bool installing;
  final String? installError;
  final VoidCallback onCheck;
  final VoidCallback onInstall;
  final ValueChanged<String> onOpenNotes;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final s = status;
    final available = s?.state == 'AVAILABLE';

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Обновление', style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(_describe(s, checking), style: theme.textTheme.bodyMedium),
            if (s?.error != null) ...[
              const SizedBox(height: 4),
              Text(
                s!.error!,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
            if (installError != null) ...[
              const SizedBox(height: 8),
              Text(
                installError!,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.error,
                ),
              ),
            ],
            const SizedBox(height: 12),
            if (available)
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: installing ? null : onInstall,
                  icon: installing
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.download),
                  label: Text(installing ? 'Загружаю…' : 'Обновить'),
                ),
              ),
            if (available) const SizedBox(height: 8),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton(
                onPressed: checking ? null : onCheck,
                child: Text(checking ? 'Проверяю…' : 'Проверить обновление'),
              ),
            ),
            if (s?.notesUrl != null) ...[
              const SizedBox(height: 4),
              SizedBox(
                width: double.infinity,
                child: TextButton(
                  onPressed: () => onOpenNotes(s!.notesUrl!),
                  child: const Text('Открыть страницу выпуска'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  static String _describe(UpdateStatusDto? s, bool checking) {
    if (checking && s == null) return 'Проверяю…';
    return switch (s?.state) {
      'AVAILABLE' =>
        'Доступна версия ${s!.version ?? '—'}'
            '${s.sizeBytes != null ? ' · ${_mb(s.sizeBytes!)}' : ''}',
      'UP_TO_DATE' => 'Установлена актуальная версия.',
      'FAILURE' => 'Проверить обновление не удалось.',
      _ => 'Обновление ещё не проверялось.',
    };
  }

  static String _mb(int bytes) =>
      '${(bytes / (1024 * 1024)).toStringAsFixed(1)} МБ';
}
