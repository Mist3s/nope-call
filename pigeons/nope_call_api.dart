// Контракт моста Kotlin <-> Flutter (архитектура §8.1).
//
// Только Pigeon: MethodChannel вручную не используется — на журнале в сотни тысяч записей
// нетипизированные словари превращаются в источник ошибок.
//
// Генерация:
//   dart run pigeon --input pigeons/nope_call_api.dart
import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/data/nope_call_api.g.dart',
    kotlinOut:
        'android/app/src/main/kotlin/com/mist3s/nopecall/bridge/NopeCallApi.g.kt',
    kotlinOptions: KotlinOptions(package: 'com.mist3s.nopecall.bridge'),
    dartPackageName: 'nope_call',
  ),
)

/// Состояние настройки. `blockingActive` намеренно отдельно от `hasRole`:
/// без роли сервис не вызывается вообще, и обещать «блокировка активна» нельзя (ТЗ §18 п. 2).
class SetupStatus {
  SetupStatus({
    required this.hasRole,
    required this.hasCallLog,
    required this.hasContacts,
    required this.hasNotifications,
    required this.blockingEnabled,
    required this.blockingActive,
    required this.enabledRuleCount,
    required this.problems,
    this.lastScreeningAt,
  });

  bool hasRole;
  bool hasCallLog;
  bool hasContacts;
  bool hasNotifications;
  bool blockingEnabled;
  bool blockingActive;
  int enabledRuleCount;

  /// Коды проблем: NO_ROLE, DISABLED_BY_USER, NO_RULES, NO_CALL_LOG, NO_CONTACTS,
  /// NO_NOTIFICATIONS. Строками, а не перечислением, чтобы добавление кода не ломало мост.
  List<String> problems;
  int? lastScreeningAt;
}

class RuleDto {
  RuleDto({
    required this.id,
    required this.title,
    required this.targetType,
    required this.matchType,
    required this.pattern,
    required this.patternCanonical,
    required this.action,
    required this.orderIndex,
    required this.isEnabled,
    required this.translitVariants,
    required this.matchCount,
    this.regexField,
    this.comment,
    this.lastMatchedAt,
  });

  int id;
  String title;
  String targetType;
  String matchType;

  /// Как ввёл пользователь — это и показывается ему.
  String pattern;

  /// Канонизированный вид: по нему идёт сопоставление. Полезно показать в отладке.
  String patternCanonical;
  String action;
  int orderIndex;
  bool isEnabled;
  bool translitVariants;
  int matchCount;
  String? regexField;
  String? comment;
  int? lastMatchedAt;
}

/// Результат проверки шаблона в редакторе.
class PatternCheckResult {
  PatternCheckResult({
    required this.valid,
    required this.canonical,
    required this.variants,
    required this.variantsTruncated,
    this.error,
  });

  bool valid;
  String canonical;

  /// Что именно правило будет искать. Показывается пользователю: непрозрачное нечёткое
  /// сравнение недопустимо, ложное срабатывание — пропущенный звонок от врача (ТЗ §6.3.2).
  List<String> variants;
  bool variantsTruncated;
  String? error;
}

class SaveRuleResult {
  SaveRuleResult({
    required this.saved,
    required this.id,
    required this.variants,
    required this.variantsTruncated,
    this.error,
  });

  bool saved;
  int id;
  List<String> variants;
  bool variantsTruncated;
  String? error;
}

class JournalItemDto {
  JournalItemDto({
    required this.id,
    required this.occurredAt,
    required this.rawNumber,
    required this.nameSource,
    required this.action,
    required this.reason,
    required this.latencyMs,
    required this.blockedByUs,
    required this.hadSignature,
    this.e164,
    this.nameRaw,
    this.matchedRuleId,
    this.matchedRuleTitle,
  });

  int id;
  int occurredAt;
  String rawNumber;
  String nameSource;
  String action;
  String reason;
  int latencyMs;
  bool blockedByUs;
  bool hadSignature;
  String? e164;
  String? nameRaw;
  int? matchedRuleId;
  String? matchedRuleTitle;
}

class JournalPageDto {
  JournalPageDto({
    required this.items,
    required this.hasMore,
    this.nextBeforeTime,
    this.nextBeforeId,
  });

  List<JournalItemDto> items;
  bool hasMore;

  /// Курсор составной: метки времени в миллисекундах совпадают у соседних записей при
  /// пакетной вставке, и курсор по одному полю пропускал бы строки (архитектура §7.3).
  int? nextBeforeTime;
  int? nextBeforeId;
}

class SummaryDto {
  SummaryDto({
    required this.blockedToday,
    required this.totalEvents,
    required this.withSignatureLast100,
    required this.checkedLast100,
    this.lastEventAt,
  });

  int blockedToday;
  int totalEvents;
  int withSignatureLast100;
  int checkedLast100;
  int? lastEventAt;
}

class PreviewDto {
  PreviewDto({required this.count, required this.truncated});

  int count;

  /// Окно предпросмотра усечено: показывать надо «≥ N» (ТЗ §18 п. 16).
  bool truncated;
}

@HostApi()
abstract class StatusApi {
  SetupStatus status();

  /// Открывает системный диалог запроса роли. Возвращает false, если роль недоступна.
  @async
  bool requestRole();

  void openAppSettings();
}

@HostApi()
abstract class RulesApi {
  @async
  List<RuleDto> list();

  @async
  SaveRuleResult save(
    int? id,
    String title,
    String targetType,
    String matchType,
    String pattern,
    String action,
    bool enabled,
    String? regexField,
    bool? translitVariants,
    String? comment,
  );

  @async
  void setEnabled(int id, bool enabled);

  @async
  void delete(int id);

  /// Переупорядочивание одной операцией: перенумерация идёт в одной транзакции.
  @async
  void reorder(List<int> idsInOrder);

  PatternCheckResult checkPattern(
    String targetType,
    String matchType,
    String pattern,
  );

  @async
  PreviewDto preview(String targetType, String matchType, String pattern);
}

@HostApi()
abstract class JournalApi {
  @async
  JournalPageDto page(int? beforeTime, int? beforeId, int limit);

  @async
  SummaryDto summary();
}

@HostApi()
abstract class SettingsApi {
  @async
  Map<String, String> all();

  @async
  void put(String key, String value);
}
