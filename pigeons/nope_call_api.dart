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
    required this.defaultAction,
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

  /// Что произойдёт, если ни одно правило не совпало: ALLOW / REJECT / DROP / SILENCE.
  /// Часть состояния, а не только настройка: от него зависит, что приложение обещает
  /// на главном экране, и обещание обязано совпадать с поведением.
  String defaultAction;
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
    required this.parts,
    this.error,
  });

  bool valid;
  String canonical;

  /// Что именно правило будет искать. Показывается пользователю: непрозрачное нечёткое
  /// сравнение недопустимо, ложное срабатывание — пропущенный звонок от врача (ТЗ §6.3.2).
  List<String> variants;
  bool variantsTruncated;

  /// Перечисленные значения в канонической форме — по одному на категорию.
  ///
  /// Отдельно от [variants]: `[gostinicy, dostavka]` — это две категории, а
  /// `[poleznyy, polezniy]` — два написания одной, и объяснять их надо по-разному.
  List<String> parts;
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
    required this.sourceRank,
    required this.occurredAt,
    required this.kind,
    required this.rawNumber,
    required this.nameSource,
    required this.blockedByUs,
    required this.hadSignature,
    required this.nameLate,
    this.action,
    this.reason,
    this.latencyMs,
    this.durationSeconds,
    this.e164,
    this.nameRaw,
    this.matchedRuleId,
    this.matchedRuleTitle,
    this.eventId,
    this.systemId,
    this.phoneAccountId,
  });

  int id;

  /// `0` — запись системного журнала, `1` — только наша проверка. Вместе с `id` уникально:
  /// идентификаторы двух таблиц легко совпадают численно.
  int sourceRank;
  int occurredAt;

  /// Тип записи для интерфейса (ТЗ §7.4): BLOCKED_BY_APP, BLOCKED_EXTERNAL, SILENCED,
  /// INCOMING_ANSWERED, MISSED, REJECTED_BY_USER, OUTGOING, VOICEMAIL, CHECKED_ALLOWED, UNKNOWN.
  String kind;
  String rawNumber;
  String nameSource;
  bool blockedByUs;
  bool hadSignature;

  /// Название стало известно уже после решения: правила по названию на этом звонке
  /// сработать не могли, и показывать его как «было» нельзя.
  bool nameLate;

  /// `null` — звонок проверяли не мы: запись пришла из системного журнала.
  String? action;
  String? reason;
  int? latencyMs;

  /// `null` — исход неизвестен, а не «ноль секунд» (ТЗ §7.2).
  int? durationSeconds;
  String? e164;
  String? nameRaw;
  int? matchedRuleId;
  String? matchedRuleTitle;
  int? eventId;
  int? systemId;
  String? phoneAccountId;
}

/// Курсор страницы журнала. Тройной: метки времени совпадают у соседних записей при пакетной
/// вставке зеркала, а `id` уникален только внутри своей таблицы (архитектура §7.3).
class JournalCursorDto {
  JournalCursorDto({
    required this.at,
    required this.sourceRank,
    required this.id,
  });

  int at;
  int sourceRank;
  int id;
}

/// Фильтры журнала (ТЗ §7.5). Складываются по «И»; `null` — «не фильтровать».
class JournalFilterDto {
  JournalFilterDto({
    required this.kind,
    this.digitsQuery,
    this.nameQuery,
    this.hadSignature,
    this.fromAt,
    this.toAt,
    this.ruleId,
    this.sim,
  });

  /// ALL, BLOCKED_BY_US, BLOCKED_ANY, INCOMING, OUTGOING, MISSED, SILENCED.
  String kind;
  String? digitsQuery;
  String? nameQuery;
  bool? hadSignature;
  int? fromAt;
  int? toAt;
  int? ruleId;
  String? sim;
}

class JournalPageDto {
  JournalPageDto({required this.items, required this.hasMore, this.next});

  List<JournalItemDto> items;
  bool hasMore;
  JournalCursorDto? next;
}

/// SIM, встречавшаяся в журнале (ТЗ §7.4).
///
/// [id] — то, что отдал Telecom (`phoneAccountId`); на большинстве прошивок это серийный номер
/// карты. Фильтр сравнивает по нему. [label] — то, что видит пользователь: «МТС · SIM 1», либо
/// «Карта …6644», если имя оператора недоступно. Раньше в списке стоял сам [id], и выбрать
/// нужную карту по серийному номеру было невозможно.
class SimDto {
  SimDto({required this.id, required this.label, required this.nameKnown});

  String id;
  String label;

  /// Настоящее ли это имя. `false` — показана короткая форма, и интерфейс обязан сказать,
  /// какого разрешения не хватает, а не делать вид, что так и надо.
  bool nameKnown;
}

class SummaryDto {
  SummaryDto({
    required this.blockedToday,
    required this.totalEvents,
    required this.withSignatureLast100,
    required this.checkedLast100,
    required this.mirrorRecords,
    this.lastEventAt,
  });

  int blockedToday;
  int totalEvents;
  int withSignatureLast100;
  int checkedLast100;

  /// Сколько записей пришло из системного журнала. Ноль при выданном разрешении — сигнал,
  /// что синхронизация не работает, а не что звонков не было.
  int mirrorRecords;
  int? lastEventAt;
}

/// Итог синхронизации зеркала системного журнала (ТЗ §7.2).
class SyncResultDto {
  SyncResultDto({
    required this.available,
    required this.fetched,
    required this.stitched,
    required this.lateNames,
  });

  /// Есть ли доступ к системному журналу. Без него зеркало не наполняется вообще.
  bool available;
  int fetched;
  int stitched;

  /// Названий, ставших известными уже после решения, любого происхождения. Показатель §21 п. 4
  /// считается не здесь: узнать, дослал ли название оператор или диалер, по системному журналу
  /// нельзя (см. `CallLogSyncer.lateNameSource`).
  int lateNames;
}

class PreviewDto {
  PreviewDto({
    required this.count,
    required this.truncated,
    required this.contactsTruncated,
    required this.contactsState,
    this.allowRulesCovered,
    this.contactsCovered,
  });

  int count;

  /// Окно предпросмотра усечено: показывать надо «≥ N» (ТЗ §18 п. 16).
  bool truncated;

  /// Книга контактов прочитана не до конца — [contactsCovered] это нижняя граница.
  bool contactsTruncated;

  /// Сколько разрешающих правил новое правило перекрывает. `null` — не считали.
  int? allowRulesCovered;

  /// Сколько номеров из телефонной книги попадёт под правило. Осмысленно только
  /// при `contactsState == 'COUNTED'`.
  int? contactsCovered;

  /// COUNTED — книга прочитана; NOT_APPLICABLE — правило не про номера, книга тут не при чём;
  /// NO_ACCESS — правило про номера, но доступа к книге нет. Три разных состояния, а не один
  /// `null`: иначе интерфейс сообщает «нет доступа» там, где показатель просто неприменим.
  String contactsState;
}

/// Отчёт об импорте правил (ТЗ §15.8): что добавлено, что пропущено, что отклонено и почему.
class ImportReportDto {
  ImportReportDto({
    required this.ok,
    required this.added,
    required this.updated,
    required this.duplicates,
    required this.removed,
    required this.rejected,
    required this.snapshotRebuilt,
    this.error,
  });

  /// `false` — файл не разобран целиком: причина в [error], остальные поля пусты.
  bool ok;
  int added;
  int updated;
  int duplicates;

  /// Названия удалённых правил, а не их число: «удалено 7» ничего не объясняет.
  List<String> removed;

  /// Отклонённые записи: «строка 3 «Мой банк» — неверное регулярное выражение».
  List<String> rejected;

  /// Снимок правил пересобран. `false` — правила в базе новые, а решения ещё старые.
  bool snapshotRebuilt;
  String? error;
}

/// Состояние обновления (ТЗ §15.5). Ошибка приходит текстом и показывается только
/// на своём экране: всплывающих окон при автопроверке быть не должно.
class UpdateStatusDto {
  UpdateStatusDto({
    required this.state,
    required this.currentVersion,
    this.version,
    this.build,
    this.notesUrl,
    this.error,
    this.sizeBytes,
  });

  /// AVAILABLE — есть новее; UP_TO_DATE — установлена актуальная; FAILURE — не проверить.
  String state;
  String currentVersion;
  String? version;
  int? build;
  String? notesUrl;
  String? error;
  int? sizeBytes;
}

@HostApi()
abstract class StatusApi {
  SetupStatus status();

  /// Открывает системный диалог запроса роли. Возвращает false, если роль недоступна.
  @async
  bool requestRole();

  /// Запрашивает разрешения на журнал звонков, контакты и уведомления.
  ///
  /// Все три необязательны: без них блокировка по номеру работает, а зеркало журнала,
  /// правило «есть в контактах» и уведомления — нет. Отказ не ломает приложение.
  @async
  bool requestPermissions();

  void openAppSettings();

  /// Системные настройки уведомлений приложения.
  ///
  /// Звук, важность и способ показа — свойства канала, и после его создания приложение
  /// их менять не может: этим управляет система. Поэтому здесь именно переход, а не
  /// собственные переключатели, которые делали бы вид, что настраивают.
  void openNotificationSettings();
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

  /// Собирает JSON со всеми правилами и отдаёт его через системный выбор приложения
  /// (ТЗ §15.8). Возвращает false, если делиться нечем.
  @async
  bool exportRules();

  /// Открывает выбор файла и импортирует правила. Ответ приходит после выбора:
  /// пользователь может и отменить — тогда `ok = false` с причиной «отменено».
  @async
  ImportReportDto importRules(bool replaceAll);
}

@HostApi()
abstract class JournalApi {
  @async
  JournalPageDto page(
    JournalFilterDto filter,
    JournalCursorDto? cursor,
    int limit,
  );

  @async
  SummaryDto summary();

  /// Какие SIM встречались в журнале. Пусто — фильтр по SIM показывать незачем.
  @async
  List<SimDto> sims();

  /// Скрыть запись локально. Системный журнал Android при этом не трогается (ТЗ §7.2).
  @async
  void hide(int systemId);

  /// Очистить журнал приложения. Системный журнал Android не затрагивается (ТЗ §7.6).
  @async
  int clear();

  /// Синхронизировать зеркало системного журнала. Вызывается по жесту обновления:
  /// разрешение могли выдать только что, и ждать перезапуска приложения незачем.
  @async
  SyncResultDto syncCallLog();

  /// Выгрузка журнала в CSV за период и передача файла наружу (ТЗ §7.6).
  /// Возвращает число выгруженных строк; 0 — выгружать было нечего.
  @async
  int exportCsv(int? fromAt, int? toAt);
}

@HostApi()
abstract class UpdaterApi {
  /// Проверка обновления (ТЗ §15.5). `silent = true` — автопроверка при запуске:
  /// её ошибки никуда не показываются, а только оседают в состоянии.
  @async
  UpdateStatusDto check(bool allowPrerelease, bool silent);

  /// Скачивает APK, сверяет sha256 и отпечаток сертификата, отдаёт системному установщику.
  /// Возвращает текст причины отказа либо null, если диалог установки запущен.
  @async
  String? install(bool allowPrerelease);

  /// Открывает страницу релиза — путь установки вручную, если установщик отказал.
  void openReleasePage(String url);
}

/// Состояние режима наблюдения (ТЗ §7.7.2): настройки и занятый объём в одном месте.
class ObservationStatusDto {
  ObservationStatusDto({
    required this.enabled,
    required this.techEnabled,
    required this.techVerbose,
    required this.callsRetentionDays,
    required this.callsMaxMb,
    required this.techRetentionDays,
    required this.techMaxMb,
    required this.maskByDefault,
    required this.callsBytes,
    required this.techBytes,
    required this.dailyBytesEstimate,
    required this.droppedTechLines,
    required this.installId,
    this.oldestAt,
  });

  bool enabled;
  bool techEnabled;
  bool techVerbose;
  int callsRetentionDays;
  int callsMaxMb;
  int techRetentionDays;
  int techMaxMb;
  bool maskByDefault;
  int callsBytes;
  int techBytes;

  /// Оценка прироста в сутки: без неё «100 МБ» ни о чём не говорит.
  int dailyBytesEstimate;
  int droppedTechLines;
  String installId;

  /// С какого момента есть данные — «есть данные с 12 июня» (ТЗ §7.7.2).
  int? oldestAt;
}

/// Разбивка «значение — сколько раз». Для сводки §7.7.5.
class BucketDto {
  BucketDto({required this.label, required this.total});

  String label;
  int total;
}

/// Наблюдённая операторская подпись: дословно и рядом свёрнутая форма (ТЗ §7.7.5).
class SignatureDto {
  SignatureDto({
    required this.raw,
    required this.total,
    required this.lastAt,
    this.fold,
  });

  String raw;
  int total;
  int lastAt;
  String? fold;
}

/// Сводка режима наблюдения (ТЗ §7.7.5).
class ObservationReportDto {
  ObservationReportDto({
    required this.periodDays,
    required this.checks,
    required this.withSignature,
    required this.withoutName,
    required this.lateNames,
    required this.lateSignatures,
    required this.namesAtDecision,
    required this.hiddenNumbers,
    required this.coldStarts,
    required this.watchdogFired,
    required this.latencyP50,
    required this.latencyP95,
    required this.latencyMax,
    required this.nameSources,
    required this.networkTypes,
    required this.volte,
    required this.extrasKeys,
    required this.signatures,
  });

  int periodDays;
  int checks;
  int withSignature;
  int withoutName;

  /// Сколько названий стало известно уже после решения — любого происхождения, включая имена
  /// из телефонной книги.
  int lateNames;

  /// Из них операторских подписей. Отдельно от [lateNames], потому что именно это отвечает
  /// на §21 п. 4: имя «Мама», подставленное системой позже, к поведению оператора отношения
  /// не имеет. Источник — только собственное наблюдение: происхождение названия из системного
  /// журнала неустановимо.
  int lateSignatures;

  /// Названий, известных **в момент решения**. Поздние сюда не входят: раньше их считали
  /// вместе, и сводка утверждала «название было» про звонок, где его не было.
  int namesAtDecision;
  int hiddenNumbers;
  int coldStarts;
  int watchdogFired;
  int latencyP50;
  int latencyP95;
  int latencyMax;
  List<BucketDto> nameSources;
  List<BucketDto> networkTypes;
  List<BucketDto> volte;
  List<BucketDto> extrasKeys;
  List<SignatureDto> signatures;
}

/// Что именно уйдёт в выгрузке (ТЗ §7.7.3 п. 2).
class ExportEstimateDto {
  ExportEstimateDto({required this.callLines, required this.archiveBytes});

  int callLines;
  int archiveBytes;
}

@HostApi()
abstract class ObservationApi {
  @async
  ObservationStatusDto status();

  @async
  ObservationReportDto report(int periodDays);

  /// Настройки режима. Все параметры регулируются, чтобы разбирать новое поведение
  /// без выпуска новой сборки (ТЗ §7.7.2).
  @async
  void setConfig(
    bool enabled,
    bool techEnabled,
    bool techVerbose,
    int callsRetentionDays,
    int callsMaxMb,
    int techRetentionDays,
    int techMaxMb,
    bool maskByDefault,
  );

  @async
  ExportEstimateDto estimate(int fromAt, int toAt);

  /// Собирает архив и открывает системный выбор приложения. Приложение никуда ничего
  /// не отправляет само: сеть доступна только апдейтеру (ТЗ §7.7.3 п. 4).
  @async
  bool share(int fromAt, int toAt, bool mask, String periodLabel);

  /// Удаляет накопленное. Выключение режима данные не удаляет (ТЗ §7.7.2).
  @async
  int deleteLogs();
}

/// Отчёт диагностики (ТЗ §9.7). Экран обязательный: блокировка отказывает тихо, и без
/// диагностики поддержка возможна только подключением к телефону пользователя.
class DiagnosticsDto {
  DiagnosticsDto({
    required this.checksLast7Days,
    required this.latencyP50,
    required this.latencyP95,
    required this.latencyMax,
    required this.degradedCounts,
    required this.ruleErrors,
    required this.lastEvents,
    required this.nameSources,
    required this.withSignatureLast100,
    required this.checkedLast100,
    required this.volte,
    required this.signatureLooksUnavailable,
    required this.device,
    required this.reportText,
    required this.droppedPendingEvents,
    this.snapshotFormatVersion,
    this.snapshotCanonVersion,
    this.snapshotRuleCount,
    this.snapshotBuiltAt,
    this.snapshotError,
    this.batteryUnrestricted,
  });

  /// Записей события, отброшенных по достижении предела очереди Direct Boot (§9.2).
  /// Показывается всегда: «0» здесь — это утверждение «ничего не потеряно».
  int droppedPendingEvents;

  int checksLast7Days;
  int latencyP50;
  int latencyP95;
  int latencyMax;
  List<BucketDto> degradedCounts;
  List<RuleErrorDto> ruleErrors;
  List<EventLineDto> lastEvents;
  List<BucketDto> nameSources;
  int withSignatureLast100;
  int checkedLast100;
  List<BucketDto> volte;

  /// Подписи не встречались ни разу на достаточной выборке: правила по названию не сработают,
  /// и сказать это надо до того, как пользователь их построит.
  bool signatureLooksUnavailable;
  String device;

  /// Готовый текст для кнопки «Скопировать отчёт». Номера в нём замаскированы.
  String reportText;
  int? snapshotFormatVersion;
  int? snapshotCanonVersion;
  int? snapshotRuleCount;
  int? snapshotBuiltAt;
  String? snapshotError;

  /// Исключено ли приложение из ограничений энергосбережения. `null` — определить не удалось.
  bool? batteryUnrestricted;
}

class RuleErrorDto {
  RuleErrorDto({required this.title, required this.errorCount, this.lastError});

  String title;
  int errorCount;
  String? lastError;
}

class EventLineDto {
  EventLineDto({
    required this.occurredAt,
    required this.number,
    required this.action,
    required this.reason,
    required this.latencyMs,
    this.coldStart,
  });

  int occurredAt;
  String number;
  String action;
  String reason;
  int latencyMs;
  bool? coldStart;
}

/// Шаг тестового прогона: какое правило проверялось и что вышло (ТЗ §9.7).
class TraceStepDto {
  TraceStepDto({
    required this.ruleId,
    required this.title,
    required this.target,
    required this.matchType,
    required this.canonical,
    required this.patterns,
    required this.matched,
    this.skippedReason,
  });

  int ruleId;
  String title;
  String target;
  String matchType;

  /// Все шаблоны, с которыми сравнивалось правило. У правила по категории здесь
  /// перечисленные категории, у правила по названию — варианты написания.
  List<String> patterns;
  String canonical;
  bool matched;
  String? skippedReason;
}

/// Результат тестового прогона: способ проверить решение без второго телефона (ТЗ §9.7).
class TestRunDto {
  TestRunDto({
    required this.digits,
    required this.candidates,
    required this.nameNorm,
    required this.nameFold,
    required this.orgFold,
    required this.action,
    required this.reason,
    required this.elapsedMicros,
    required this.steps,
    required this.snapshotMissing,
    this.e164,
    this.categoryFold,
    this.matchedRuleId,
    this.matchedRuleTitle,
  });

  String digits;
  List<String> candidates;
  String nameNorm;
  String nameFold;
  String orgFold;
  String action;
  String reason;
  int elapsedMicros;
  List<TraceStepDto> steps;

  /// Снимка правил нет: решение было бы «пропустить», и это надо показать прямо.
  bool snapshotMissing;
  String? e164;
  String? categoryFold;
  int? matchedRuleId;
  String? matchedRuleTitle;
}

@HostApi()
abstract class DiagnosticsApi {
  @async
  DiagnosticsDto report();

  /// Прогон идёт через настоящий снимок и настоящий движок: вторая реализация сопоставления
  /// «для диагностики» тут же начала бы расходиться с первой.
  @async
  TestRunDto testRun(String number, String? name);

  /// Открывает системный экран ограничений энергосбережения.
  void openBatterySettings();
}

@HostApi()
abstract class SettingsApi {
  @async
  Map<String, String> all();

  @async
  void put(String key, String value);
}
