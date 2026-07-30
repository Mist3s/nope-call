import 'nope_call_api.g.dart';

/// Единственная точка доступа к платформе (архитектура §8.6).
///
/// Бизнес-логики в Dart нет: ни сопоставления, ни канонизации, ни порядка правил. Иначе
/// появится вторая реализация правил, расходящаяся с первой. Здесь только вызовы моста.
class PlatformRepository {
  PlatformRepository({
    StatusApi? status,
    RulesApi? rules,
    JournalApi? journal,
    SettingsApi? settings,
  }) : _status = status ?? StatusApi(),
       _rules = rules ?? RulesApi(),
       _journal = journal ?? JournalApi(),
       _settings = settings ?? SettingsApi();

  final StatusApi _status;
  final RulesApi _rules;
  final JournalApi _journal;
  final SettingsApi _settings;

  Future<SetupStatus> status() => _status.status();
  Future<bool> requestRole() => _status.requestRole();
  void openAppSettings() => _status.openAppSettings();

  Future<List<RuleDto>> rules() => _rules.list();

  Future<SaveRuleResult> saveRule({
    int? id,
    required String title,
    required String targetType,
    required String matchType,
    required String pattern,
    required String action,
    bool enabled = true,
    String? regexField,
    bool? translitVariants,
    String? comment,
  }) {
    return _rules.save(
      id,
      title,
      targetType,
      matchType,
      pattern,
      action,
      enabled,
      regexField,
      translitVariants,
      comment,
    );
  }

  Future<void> setRuleEnabled(int id, bool enabled) =>
      _rules.setEnabled(id, enabled);

  Future<void> deleteRule(int id) => _rules.delete(id);

  Future<void> reorderRules(List<int> ids) => _rules.reorder(ids);

  /// Синхронно намеренно: проверка чистая, без базы, и вызывается на каждое нажатие клавиши.
  Future<PatternCheckResult> checkPattern(
    String targetType,
    String matchType,
    String pattern,
  ) => _rules.checkPattern(targetType, matchType, pattern);

  Future<PreviewDto> preview(
    String targetType,
    String matchType,
    String pattern,
  ) => _rules.preview(targetType, matchType, pattern);

  Future<JournalPageDto> journalPage({
    int? beforeTime,
    int? beforeId,
    int limit = 50,
  }) => _journal.page(beforeTime, beforeId, limit);

  Future<SummaryDto> summary() => _journal.summary();

  Future<Map<String, String>> settings() => _settings.all();
  Future<void> putSetting(String key, String value) =>
      _settings.put(key, value);
}

/// Человекочитаемые названия для интерфейса. Интерфейс на русском (ТЗ §11.5).
abstract final class Labels {
  static const targets = {
    'NUMBER': 'Номер',
    'NAME': 'Вся подпись',
    'NAME_ORG': 'Наименование',
    'NAME_CATEGORY': 'Категория вызова',
    'CONTACT': 'Контакты',
  };

  static const matchTypes = {
    'EXACT': 'Точное совпадение',
    'PREFIX': 'Начинается с',
    'SUFFIX': 'Заканчивается на',
    'CONTAINS': 'Содержит',
    'TOKEN': 'Содержит слово',
    'REGEX': 'Регулярное выражение',
    'IN_CONTACTS': 'Есть в контактах',
  };

  static const actions = {
    'REJECT': 'Отклонить',
    'DROP': 'Тихий сброс',
    'SILENCE': 'Без звука',
    'ALLOW': 'Разрешить',
  };

  static const actionHints = {
    'REJECT': 'Звонящий услышит сброс сразу',
    'DROP': 'Звонящий будет слышать гудки до конца',
    'SILENCE': 'Звонок дойдёт, телефон промолчит',
    'ALLOW': 'Пропустить, даже если ниже есть блокирующее правило',
  };

  static const problems = {
    'NO_ROLE': 'Приложение не назначено средством проверки звонков',
    'DISABLED_BY_USER': 'Блокировка выключена в настройках',
    'NO_RULES': 'Нет ни одного включённого правила',
    'NO_CALL_LOG': 'Нет доступа к журналу звонков',
    'NO_CONTACTS': 'Нет доступа к контактам',
    'NO_NOTIFICATIONS': 'Уведомления запрещены',
  };

  static const reasons = {
    'RULE_MATCH': 'сработало правило',
    'DEFAULT_ACTION': 'ни одно правило не совпало',
    'DISABLED_BY_USER': 'блокировка выключена',
    'EMERGENCY': 'экстренный номер',
    'RESTRICTED_NUMBER': 'скрытый номер',
    'UNKNOWN_NUMBER': 'номер не определён',
    'ENGINE_BUDGET_EXCEEDED': 'не успели проверить — звонок пропущен',
    'SNAPSHOT_UNAVAILABLE': 'правила недоступны — звонок пропущен',
    'WATCHDOG_ANSWERED': 'не успели проверить — звонок пропущен',
  };

  static String target(String code) => targets[code] ?? code;
  static String matchType(String code) => matchTypes[code] ?? code;
  static String action(String code) => actions[code] ?? code;
  static String problem(String code) => problems[code] ?? code;
  static String reason(String code) => reasons[code] ?? code;
}
