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
    ObservationApi? observation,
    DiagnosticsApi? diagnosticsApi,
    UpdaterApi? updater,
  }) : _status = status ?? StatusApi(),
       _rules = rules ?? RulesApi(),
       _journal = journal ?? JournalApi(),
       _settings = settings ?? SettingsApi(),
       _observation = observation ?? ObservationApi(),
       _diagnostics = diagnosticsApi ?? DiagnosticsApi(),
       _updater = updater ?? UpdaterApi();

  final StatusApi _status;
  final RulesApi _rules;
  final JournalApi _journal;
  final SettingsApi _settings;
  final ObservationApi _observation;
  final DiagnosticsApi _diagnostics;
  final UpdaterApi _updater;

  Future<SetupStatus> status() => _status.status();
  Future<bool> requestRole() => _status.requestRole();
  Future<bool> requestPermissions() => _status.requestPermissions();
  void openAppSettings() => _status.openAppSettings();

  /// Системные настройки уведомлений: звук и важность канала приложение менять не может.
  void openNotificationSettings() => _status.openNotificationSettings();

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

  Future<bool> exportRules() => _rules.exportRules();

  Future<ImportReportDto> importRules({required bool replaceAll}) =>
      _rules.importRules(replaceAll);

  Future<int> exportJournalCsv({int? fromAt, int? toAt}) =>
      _journal.exportCsv(fromAt, toAt);

  Future<UpdateStatusDto> checkUpdate({
    required bool allowPrerelease,
    bool silent = false,
  }) => _updater.check(allowPrerelease, silent);

  Future<String?> installUpdate({required bool allowPrerelease}) =>
      _updater.install(allowPrerelease);

  void openReleasePage(String url) => _updater.openReleasePage(url);

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
    JournalFilterDto? filter,
    JournalCursorDto? cursor,
    int limit = 50,
  }) => _journal.page(filter ?? JournalFilterDto(kind: 'ALL'), cursor, limit);

  Future<SummaryDto> summary() => _journal.summary();

  Future<List<SimDto>> journalSims() => _journal.sims();
  Future<void> hideJournalRecord(int systemId) => _journal.hide(systemId);
  Future<int> clearJournal() => _journal.clear();
  Future<SyncResultDto> syncCallLog() => _journal.syncCallLog();

  Future<ObservationStatusDto> observationStatus() => _observation.status();

  Future<ObservationReportDto> observationReport(int periodDays) =>
      _observation.report(periodDays);

  Future<void> setObservationConfig({
    required bool enabled,
    required bool techEnabled,
    required bool techVerbose,
    required int callsRetentionDays,
    required int callsMaxMb,
    required int techRetentionDays,
    required int techMaxMb,
    required bool maskByDefault,
  }) => _observation.setConfig(
    enabled,
    techEnabled,
    techVerbose,
    callsRetentionDays,
    callsMaxMb,
    techRetentionDays,
    techMaxMb,
    maskByDefault,
  );

  Future<ExportEstimateDto> estimateLogs({
    required int fromAt,
    required int toAt,
  }) => _observation.estimate(fromAt, toAt);

  Future<bool> shareLogs({
    required int fromAt,
    required int toAt,
    required bool mask,
    required String periodLabel,
  }) => _observation.share(fromAt, toAt, mask, periodLabel);

  Future<int> deleteLogs() => _observation.deleteLogs();

  Future<DiagnosticsDto> diagnostics() => _diagnostics.report();

  Future<TestRunDto> testRun(String number, String? name) =>
      _diagnostics.testRun(number, name);

  void openBatterySettings() => _diagnostics.openBatterySettings();

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
    'SHORT_NUMBER': 'короткий номер — блокируется только точным правилом',
    'OUTGOING_CALL': 'исходящий звонок — приложение его не проверяет',
    'ENGINE_BUDGET_EXCEEDED': 'не успели проверить — звонок пропущен',
    'SNAPSHOT_UNAVAILABLE': 'правила недоступны — звонок пропущен',
    // Раздельные причины: «не разобрали данные звонка» и «сбой проверки» ведут при разборе
    // жалобы в разные места, а раньше и то и другое выглядело как «правила недоступны».
    'FACTS_FAILED': 'не разобрали данные звонка — звонок пропущен',
    'ENGINE_FAILED': 'сбой проверки — звонок пропущен',
    'WATCHDOG_ANSWERED': 'не успели проверить — звонок пропущен',
  };

  /// Типы записей журнала (ТЗ §7.4). «Заблокирован приложением» и «заблокирован системой»
  /// разделены обязательно: иначе пользователь приписывает нам блокировки прошивки.
  static const kinds = {
    'BLOCKED_BY_APP': 'Заблокирован приложением',
    'BLOCKED_EXTERNAL': 'Заблокирован системой или другим приложением',
    'SILENCED': 'Без звука',
    'INCOMING_ANSWERED': 'Входящий принятый',
    'MISSED': 'Входящий пропущенный',
    'REJECTED_BY_USER': 'Отклонён вручную',
    'OUTGOING': 'Исходящий',
    'VOICEMAIL': 'Голосовая почта',
    'CHECKED_ALLOWED': 'Проверен и пропущен',
    'UNKNOWN': 'Результат неизвестен',
  };

  /// Фильтры журнала (ТЗ §7.5).
  static const journalKinds = {
    'ALL': 'Все',
    'BLOCKED_BY_US': 'Мы заблокировали',
    'BLOCKED_ANY': 'Все блокировки',
    'INCOMING': 'Входящие',
    'OUTGOING': 'Исходящие',
    'MISSED': 'Пропущенные',
    'SILENCED': 'Без звука',
  };

  static const nameSources = {
    'CNAP': 'подпись оператора',
    'CNAP_OPERATOR_LABEL': 'подпись оператора',
    'CONTACTS': 'контакты',
    // Исходов у позднего названия два: книга подтверждена или источник не установлен.
    // «Номера в книге нет» третьим исходом не является — о происхождении названия это
    // не говорит. LATE_CNAP остаётся ярлыком под будущий канал наблюдения.
    'LATE_CONTACTS': 'контакты, узнали позже',
    'LATE_CNAP': 'подпись оператора, пришла позже',
    'LATE_UNKNOWN': 'позже, источник не установлен',
    'SYSTEM_LOG': 'позже, источник не установлен',
    'NONE': 'названия не было',
  };

  /// Короткая подпись типа — для строки списка. В карточке звонка показывается полная:
  /// «Заблокирован системой или другим приложением» занимало в списке две строки у каждой
  /// второй записи и превращало журнал в стену переносов.
  static const shortKinds = {
    'BLOCKED_BY_APP': 'Заблокирован приложением',
    'BLOCKED_EXTERNAL': 'Заблокирован системой',
    'INCOMING_ANSWERED': 'Входящий',
    'MISSED': 'Пропущенный',
    'REJECTED_BY_USER': 'Отклонён вручную',
    'CHECKED_ALLOWED': 'Проверен, пропущен',
  };

  static String kind(String code) => kinds[code] ?? code;
  static String shortKind(String code) => shortKinds[code] ?? kind(code);
  static String journalKind(String code) => journalKinds[code] ?? code;
  static String nameSource(String code) => nameSources[code] ?? code;

  /// То же, но как подпись строки: с заглавной. Формулировки в [nameSources] написаны как
  /// продолжение фразы («названия не было»), и в роли подписи рядом с «Подпись была»
  /// они смотрелись как опечатка.
  static String nameSourceLabel(String code) {
    final text = nameSource(code);
    return text.isEmpty ? text : text[0].toUpperCase() + text.substring(1);
  }

  static String target(String code) => targets[code] ?? code;
  static String matchType(String code) => matchTypes[code] ?? code;
  static String action(String code) => actions[code] ?? code;
  static String problem(String code) => problems[code] ?? code;
  static String reason(String code) => reasons[code] ?? code;
}
