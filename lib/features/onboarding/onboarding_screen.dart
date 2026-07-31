import 'package:flutter/material.dart';

import '../../data/nope_call_api.g.dart';
import '../../data/repository.dart';

/// Первоначальная настройка (ТЗ §9.8).
///
/// Три вещи здесь обязательны и все три — про честность, а не про красоту.
///
/// Первое: сказать сразу, что блокировка по названию **best effort**. Оператор передаёт подпись
/// не всегда, и пользователь, построивший десяток правил по названию, должен знать это заранее,
/// а не выяснять по жалобам.
///
/// Второе: раскрыть состав данных режима наблюдения перечислением (§7.7.4). Режим включён
/// по умолчанию и пишет номера и названия всех звонивших — это осознанный компромисс,
/// и он обязан быть осознанным и для пользователя.
///
/// Третье: любой шаг, кроме первого, пропускаем, и на отказе приложение остаётся работоспособным
/// в урезанном виде, прямо говоря, что именно недоступно.
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key, required this.onDone});

  final VoidCallback onDone;

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  final _repo = PlatformRepository();
  final _controller = PageController();

  int _page = 0;
  SetupStatus? _status;
  bool _busy = false;

  static const _pageCount = 5;

  @override
  void initState() {
    super.initState();
    _refreshStatus();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _refreshStatus() async {
    try {
      final status = await _repo.status();
      if (mounted) setState(() => _status = status);
    } catch (_) {
      // Онбординг обязан открываться даже если платформенная часть ещё не готова.
    }
  }

  void _next() {
    if (_page >= _pageCount - 1) {
      _finish();
      return;
    }
    _controller.nextPage(
      duration: const Duration(milliseconds: 250),
      curve: Curves.easeOut,
    );
  }

  Future<void> _finish() async {
    setState(() => _busy = true);
    // Флаг ставится на стороне Kotlin: он должен пережить переустановку интерфейса
    // и не зависеть от состояния Flutter.
    await _repo.putSetting('onboarding_done', 'true');
    widget.onDone();
  }

  Future<void> _requestRole() async {
    await _repo.requestRole();
    // Результат приходит из системного диалога асинхронно: состояние перечитываем,
    // а не считаем, что роль выдана.
    await Future<void>.delayed(const Duration(milliseconds: 600));
    await _refreshStatus();
  }

  Future<void> _requestPermissions() async {
    final shown = await _repo.requestPermissions();
    if (!shown) _repo.openAppSettings();
    await Future<void>.delayed(const Duration(milliseconds: 600));
    await _refreshStatus();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final status = _status;

    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: PageView(
                controller: _controller,
                onPageChanged: (i) {
                  setState(() => _page = i);
                  _refreshStatus();
                },
                children: [
                  _Page(
                    icon: Icons.shield_outlined,
                    title: 'Блокировка только по вашим правилам',
                    body: const [
                      'Приложение отклоняет звонок, только если сработало ваше правило. '
                          'Ни угадывания, ни списков «похоже на спам» — ничего такого здесь нет.',
                      'Если правило не совпало, что-то отказало или не успело — звонок проходит. '
                          'Пропущенный спам стоит раздражения, заблокированный звонок от врача '
                          'или банка — несопоставимо дороже.',
                      'Блокировка по названию звонящего работает не всегда: оператор передаёт '
                          'подпись далеко не в каждом звонке, и это зависит от сети и от договора '
                          'звонящего. Правила по номеру надёжнее.',
                    ],
                  ),
                  _Page(
                    icon: Icons.verified_user_outlined,
                    title: 'Роль средства проверки звонков',
                    body: const [
                      'Android разрешает проверять звонки только одному приложению. Без этой '
                          'роли система не покажет нам ни один звонок — блокировать будет нечего.',
                      'Отказ ничего не ломает: приложение останется рабочим, но блокировка '
                          'работать не будет, и на главном экране будет об этом сказано.',
                    ],
                    action: _StepAction(
                      label: status?.hasRole == true
                          ? 'Роль выдана'
                          : 'Назначить приложение',
                      done: status?.hasRole == true,
                      onPressed: _requestRole,
                    ),
                  ),
                  _Page(
                    icon: Icons.key_outlined,
                    title: 'Три необязательных доступа',
                    body: const [
                      'Журнал звонков — чтобы показывать исход звонка, длительность и '
                          'пропущенные. Без него журнал покажет только сами проверки.',
                      'Контакты — чтобы правило «есть в контактах» работало и чтобы широкие '
                          'правила не задевали знакомых. Без него сохранённые номера '
                          'не отличить от незнакомых.',
                      'Уведомления — чтобы сообщать о заблокированном звонке и о том, что роль '
                          'отозвали. Без них отказ будет незаметен.',
                      'Любой из трёх можно не давать: приложение останется работоспособным '
                          'и прямо скажет, что именно недоступно.',
                    ],
                    action: _StepAction(
                      label:
                          (status?.hasCallLog == true &&
                              status?.hasContacts == true &&
                              status?.hasNotifications == true)
                          ? 'Доступы выданы'
                          : 'Запросить доступы',
                      done:
                          status?.hasCallLog == true &&
                          status?.hasContacts == true &&
                          status?.hasNotifications == true,
                      onPressed: _requestPermissions,
                    ),
                  ),
                  _Page(
                    icon: Icons.science_outlined,
                    title: 'Режим наблюдения включён',
                    body: const [
                      'Приложение подробно записывает, что реально приходит на проверку: номер '
                          'и название каждого входящего звонка, время, решение, задержки, тип '
                          'сети и служебные данные, которые прислала система.',
                      'Зачем: без этих записей невозможно разобрать ни одну жалобу «почему '
                          'не сработало» и невозможно понять, передаёт ли ваш оператор подписи '
                          'вообще. Приложение первой версии само является инструментом сбора.',
                      'Записи лежат только на устройстве, в резервные копии не попадают '
                          'и никуда не отправляются сами. Сеть приложение использует '
                          'единственным образом — проверить и скачать обновление; часть, '
                          'которая ведёт журнал и логи, доступа к сети не имеет. Отправку '
                          'выбираете вы, кнопкой в настройках.',
                      'Режим выключается одним переключателем в настройках, а накопленное '
                          'удаляется отдельной кнопкой — выключение данные не стирает.',
                    ],
                  ),
                  _Page(
                    icon: Icons.rule_outlined,
                    title: 'Осталось создать первое правило',
                    body: const [
                      'Правил пока нет — значит блокировать нечего, и все звонки проходят. '
                          'Это нормальное начальное состояние.',
                      'Самый короткий путь: дождаться нежелательного звонка и создать правило '
                          'из карточки в журнале — там сразу видно, сколько записей под правило '
                          'попадёт.',
                      'Порядок правил важен: выигрывает первое совпавшее сверху, а разрешающее '
                          'правило перекрывает блокирующее.',
                    ],
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(20),
              child: Row(
                children: [
                  Row(
                    children: [
                      for (var i = 0; i < _pageCount; i++)
                        Container(
                          width: 8,
                          height: 8,
                          margin: const EdgeInsets.only(right: 6),
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: i == _page
                                ? theme.colorScheme.primary
                                : theme.colorScheme.outlineVariant,
                          ),
                        ),
                    ],
                  ),
                  const Spacer(),
                  if (_page > 0 && _page < _pageCount - 1)
                    TextButton(
                      onPressed: _busy ? null : _finish,
                      child: const Text('Пропустить'),
                    ),
                  const SizedBox(width: 8),
                  FilledButton(
                    onPressed: _busy ? null : _next,
                    child: Text(_page == _pageCount - 1 ? 'Начать' : 'Далее'),
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

class _StepAction {
  const _StepAction({
    required this.label,
    required this.done,
    required this.onPressed,
  });

  final String label;
  final bool done;
  final VoidCallback onPressed;
}

class _Page extends StatelessWidget {
  const _Page({
    required this.icon,
    required this.title,
    required this.body,
    this.action,
  });

  final IconData icon;
  final String title;
  final List<String> body;
  final _StepAction? action;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(24, 32, 24, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 44, color: theme.colorScheme.primary),
          const SizedBox(height: 20),
          Text(title, style: theme.textTheme.headlineSmall),
          const SizedBox(height: 20),
          for (final paragraph in body)
            Padding(
              padding: const EdgeInsets.only(bottom: 14),
              child: Text(paragraph, style: theme.textTheme.bodyLarge),
            ),
          if (action != null) ...[
            const SizedBox(height: 8),
            action!.done
                ? Row(
                    children: [
                      Icon(
                        Icons.check_circle,
                        color: theme.colorScheme.primary,
                      ),
                      const SizedBox(width: 8),
                      Text(action!.label, style: theme.textTheme.bodyLarge),
                    ],
                  )
                : FilledButton.tonal(
                    onPressed: action!.onPressed,
                    child: Text(action!.label),
                  ),
          ],
        ],
      ),
    );
  }
}
