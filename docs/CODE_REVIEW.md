# Полное ревью кода проекта «МГКАП» (Pulse)

Версия: 1.3.5 (versionCode 8)
Пакет: `com.example.pulse`
Дата ревью: 2026-11-09
Объём: 43 Kotlin-файла, около 16 142 строк

---

## 1. Назначение и паспорт проекта

«МГКАП» — Android-приложение для студента колледжа. Основные возможности:

- загрузка и разбор PDF-расписания с сайта колледжа;
- просмотр расписания на день, неделю и месяц;
- ведение домашних заданий с дедлайнами и привязкой к парам;
- учёт успеваемости, расчёт среднего балла, стипендии и рисков;
- передача домашних заданий между устройствами через NFC и QR;
- уведомления об изменениях расписания и напоминания о парах;
- виджеты рабочего стола;
- синхронизация с часами Wear OS (заявлена, фактически не подключена);
- проверка обновлений приложения через GitHub.

Технологический стек:

- Kotlin 2.2.10, Jetpack Compose (BOM 2026.02.01), Material 3;
- Android Gradle Plugin 9.3.1, Gradle 9.5.0;
- `compileSdk`/`targetSdk` 37, `minSdk` 24;
- WorkManager, Glance (виджеты), PDFBox-Android, ZXing, Play Services Wearable, Firebase Analytics;
- хранение данных — `SharedPreferences` + JSON, без Room и без собственного сетевого API.

---

## 2. Архитектура

Проект разделён на слои:

| Слой | Каталог | Назначение |
|---|---|---|
| Точка входа | `App.kt`, `screen/MainActivity.kt` | инициализация, навигация, фоновые задачи |
| UI | `screen/*.kt` | Compose-экраны и диалоги |
| Состояние | `viewmodel/*.kt` | `ScheduleViewModel`, `PerformanceViewModel` |
| Данные | `data/*.kt` | домашние задания, уведомления, заметки |
| Фоновая работа | `worker/*.kt` | WorkManager, broadcast-приёмники |
| Утилиты | `utils/*.kt` | парсинг, аналитика, NFC/QR, изображения, будильники |
| Виджеты | `widget/*.kt` | Glance-виджеты |

Фактическая схема зависимостей:

```
MainActivity
  ├─ ScheduleViewModel ── SharedPreferences("PulseAppPrefs")
  ├─ PerformanceViewModel ── SharedPreferences + HomeworkManager
  └─ NavHost ── ScheduleScreen / PerformanceScreen / HomeworkScreen / ...

App
  ├─ PulseCrashProtection
  ├─ ScheduleUpdateWorker (каждые 2 часа)
  └─ HomeworkWorker (раз в сутки)

MainActivity
  └─ WidgetUpdateWorker (каждые 15 минут)
```

Особенности архитектуры:

- Единого репозитория данных нет. `ScheduleViewModel`, `HomeworkWorker`, `ScheduleUpdateWorker` и виджеты independently читают и пишут одни и те же ключи `SharedPreferences`.
- `ScheduleViewModel` создаётся через `viewModel()` внутри `MainActivity` и передаётся в экраны; отдельного DI-контейнера нет.
- Навигация — `navigation-compose`, маршруты задаются строками (`"schedule"`, `"performance"`, `"homework"`, `"settings"`, `"notifications"`, `"teachers"`, `"migration"`, `"onboarding"`).
- Глобальные цвета вынесены в `MainActivity` как `mutableStateOf` (`appPrimaryColor`, `appSurfaceColor`, `appBgColor`), что связывает оформление с Activity и мешает переиспользованию.

---

## 3. Потоки данных

### 3.1 Расписание

1. `ScheduleViewModel.refreshData()` получает список PDF-ссылок со страницы колледжа.
2. PDF скачивается, проверяется и разбирается PDFBox.
3. Результат — `Map<LocalDate, List<LessonPair>>`.
4. `updateCacheWithRollingWindow()` объединяет новые данные с кэшем, сохраняет JSON в `schedule_history`, обновляет состояние и виджеты.
5. `ScheduleScreen` читает `state.lessons` и `scheduleCache` для выбранной даты.

### 3.2 Домашние задания

1. `HomeworkManager` хранит список в JSON (`SharedPreferences`).
2. `HomeworkScreen` создаёт и редактирует задания, поддерживает привязку к паре (`targetPairs`) или к дате (`dueDate`).
3. `HomeworkWorker` раз в сутки проверяет задания на завтра и отправляет уведомление.
4. Передача между устройствами: `HomeworkNfcHelper` (NFC/HCE) и `HomeworkQrHelper` (QR).

### 3.3 Успеваемость

1. `PerformanceScreen` собирает `SubjectGrade`.
2. `PerformanceViewModel` объединяет дубли предметов, применяет режим округления.
3. `PerformanceAnalytics.buildDeepReport()` считает распределение, стипендию, статус, тренды и диагностику.

### 3.4 Уведомления

1. `PulseNotificationManager.sendAndSaveNotification()` показывает системное уведомление и добавляет запись в JSON-историю.
2. `NotificationsScreen` читает историю и группирует её по типу.

### 3.5 Виджеты

1. `ScheduleViewModel.updateWidgetData()` кладёт расписание в `widget_lessons_today`.
2. `WidgetUpdateWorker` каждые 15 минут обновляет четыре виджета.
3. `PulseWidgetsCollection.kt` содержит пять виджетов; `QuickAccessWidget` в worker не обновляется.

### 3.6 Обновление приложения

`UpdateChecker` запрашивает `app_version.json` из GitHub-репозитория `Pulse-Releases` и сравнивает версию с текущей.

---

## 4. Сильные стороны

1. Приложение покрывает широкий пользовательский сценарий: расписание, домашние задания, успеваемость, уведомления, виджеты, NFC/QR.
2. Продумана деградация: сетевые ошибки и ошибки парсинга обрабатываются `try/catch`, есть fallback на кэш.
3. Реализована проверка целостности PDF (заголовок, MD5) и защита от повреждённых файлов.
4. Есть механизм rolling-кэша расписания и сохранение истории в `SharedPreferences`.
5. Разделение на ViewModel и UI позволяет тестировать логику отдельно от Compose.
6. Широко используются корутины и `Dispatchers.IO`/`Default`, тяжёлые операции не блокируют UI (кроме генерации QR).
7. Реализованы полезные детали: динамические цвета, темы, хаптика, кастомные темы шаринга расписания.
8. Присутствует защита от crash-loop в `PulseCrashProtection`.
9. Настроены WorkManager-задачи с ограничением по сети.
10. Код читается, имена в целом отражают назначение; комментарии есть в ключевых местах.

---

## 5. Дефекты и риски

### 5.1 Критические

#### C-1. `java.time` при `minSdk 24` без core library desugaring

Файл: `app/build.gradle.kts`, строки 13–17; использование `java.time.*` по всему проекту.

`minSdk = 24`, при этом `java.time` появился в API 26. В `android { compileOptions }` не включён `isCoreLibraryDesugaringEnabled`, зависимость `desugar_jdk_libs` отсутствует. На Android 7.0–7.1 (API 24–25) приложение упадёт с `NoClassDefFoundError`/`NoSuchMethodError` при первом обращении к `LocalDate`, `LocalTime`, `DateTimeFormatter`.

Исправление: либо поднять `minSdk` до 26, либо включить desugaring и добавить `coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.x")`.

#### C-2. `updateCacheWithRollingWindow` удаляет актуальные даты

Файл: `app/src/main/java/com/example/pulse/viewmodel/ScheduleViewModel.kt`, строки 253–267.

```kotlin
val validDays = scheduleCache.keys
    .filter { !it.isBefore(minRetainDate) }
    .sorted()
    .takeLast(14)
    .toSet()
scheduleCache.keys.retainAll(validDays)
```

Комментарий обещает «14 дней истории + все будущие загруженные дни», но `takeLast(14)` оставляет 14 самых поздних дат. Если в PDF загружен месяц, будут сохранены только последние 14 дней, а сегодняшняя и ближайшие даты удалены. Это ломает расписание, подсчёт пар и виджеты.

Исправление: разделить историю и будущее:

```kotlin
val history = scheduleCache.keys.filter { it in minRetainDate..today }.sorted().takeLast(14)
val future = scheduleCache.keys.filter { it.isAfter(today) }
scheduleCache.keys.retainAll((history + future).toSet())
```

#### C-3. `SystemEventReceiver` не восстанавливает будильники после перезагрузки

Файл: `app/src/main/java/com/example/pulse/worker/SystemEventReceiver.kt`, строки 11–32.

Приёмник зарегистрирован на `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, получает `schedule_history`, но тело условия пустое. `LessonAlarmManager` не вызывается, поэтому после перезагрузки устройства напоминания о парах не восстанавливаются.

Исправление: прочитать расписание, определить ближайшие пары и пересоздать `AlarmManager`-события; для API 31+ проверить `canScheduleExactAlarms()`.

### 5.2 Высокие

#### H-1. Онбординг не выставляет `migration_1.1.0_clean`

Файлы: `screen/OnboardingScreen.kt`, строки 90–99; `screen/MainActivity.kt`, строки 103–108.

Онбординг записывает `onboarding_completed`, но не `migration_1.1.0_clean`. При следующем запуске `MainActivity` видит `isMigrated = false` и отправляет пользователя на экран миграции, который удаляет `schedule_history`, `saved_teachers` и другие ключи. Новый пользователь теряет только что введённые данные.

Исправление: в `completeOnboarding()` выставлять `putBoolean("migration_1.1.0_clean", true)`.

#### H-2. `ScheduleUpdateWorker` только уведомляет, но не обновляет расписание

Файл: `worker/ScheduleUpdateWorker.kt`, строки 75–90.

При обнаружении изменений worker отправляет уведомление и сохраняет хэши, но не скачивает и не разбирает новое расписание. Обновление произойдёт только после открытия приложения. Пользователь видит уведомление «Расписание обновлено», хотя локальные данные ещё старые.

Исправление: вынести общий репозиторий загрузки/парсинга и вызывать его из worker либо явно формулировать уведомление как «Доступно обновление».

#### H-3. Расхождение подсчёта оставшихся пар

Файлы: `viewmodel/ScheduleViewModel.kt`, строки 432–451; `worker/HomeworkWorker.kt`, строки 45–67.

Различия:

- ViewModel включает день выдачи (`!date.isBefore(hw.assignDate)`), worker исключает его (`date.isAfter(hw.assignDate)`);
- ViewModel для сегодняшнего дня считает только пары, начавшиеся до текущего времени; worker считает все пары дня;
- worker читает `schedule_history`, который ограничен дефектом C-2; ViewModel использует in-memory `scheduleCache`;
- worker уведомляет при `pairsLeft <= 1`, а UI может показывать иное значение.

Итог: уведомление и экран могут показывать разные остатки. Логику нужно вынести в один общий метод.

#### H-4. `PerformanceAnalytics.buildDeepReport` не заполняет риски и рекомендации

Файл: `utils/PerformanceAnalytics.kt`, строки 142–170.

```kotlin
val risks = emptyList<SubjectRisk>()
val recommendations = emptyList<ActionRecommendation>()
```

При этом в файле уже реализованы `detectRisks()` (строка 499) и `generateRecommendations()` (строка 709). Они не вызываются, поэтому разделы «Риски» и «Рекомендации» в отчёте всегда пусты.

Исправление:

```kotlin
val risks = detectRisks(grades)
val recommendations = generateRecommendations(grades, risks, globalAverage)
```

Сигнатуры нужно сверить с фактическими параметрами функций.

#### H-5. История уведомлений не ограничена и хрупка к изменению enum

Файл: `data/PulseNotificationManager.kt`, строки 78–98.

- `saveNotificationToPrefs()` добавляет запись в начало, но никогда не удаляет старые. JSON в `SharedPreferences` растёт без предела.
- `NotificationType.valueOf(obj.getString("type"))` вызывается внутри общего `try/catch` цикла. Одно неизвестное значение (после переименования/удаления enum) приводит к исключению и потере всей истории.

Исправление: ограничить историю (например, 200 записей), использовать `runCatching { NotificationType.valueOf(...) }.getOrNull()` и пропускать только повреждённую запись.

### 5.3 Средние

#### M-1. NFC-payload может превысить ёмкость

Файл: `utils/HomeworkNfcHelper.kt`, строки 59–96.

```kotlin
if (lines.isNotEmpty() && currentBytes + lineBytes > maxCapacityBytes) break
```

Первая строка добавляется всегда, даже если превышает `maxCapacityBytes`. При длинном названии предмета или задачи payload не поместится в NFC-сообщение.

Исправление: проверять лимит и для первой строки, при необходимости обрезать текст.

#### M-2. Генерация QR в главном потоке и без обработки переполнения

Файлы: `utils/HomeworkQrHelper.kt`, строки 101–110; `screen/HomeworkScreen.kt`, строка 190.

`generateQrBitmap()` вызывается из `onClick` на главном потоке, выполняет `QRCodeWriter.encode()` и попиксельный цикл 600×600. При большом payload `encode()` бросает `WriterException`, приложение падает. Цикл `setPixel` медленный, возможны подвисания.

Исправление: вынести в `Dispatchers.Default`, использовать `IntArray` + `Bitmap.setPixels()`, обернуть `encode()` в `runCatching`, ограничить объём payload.

#### M-3. Риск OOM при генерации изображений расписания

Файл: `utils/ScheduleImageGenerator.kt`, строки 46–155.

Создаются `Bitmap` 1080×1920 ARGB_8888 (около 8,3 МБ) и изображения с высотой, зависящей от числа пар. Генерация выполняется при каждом изменении темы или даты в `LaunchedEffect`. На слабых устройствах возможны `OutOfMemoryError` и подвисания.

Исправление: ограничить высоту, использовать `RGB_565`, переиспользовать bitmap, добавить `try/catch` и освобождение ресурсов.

#### M-4. `WidgetUpdateWorker` не обновляет `QuickAccessWidget`

Файл: `worker/WidgetUpdateWorker.kt`, строки 18–21.

Обновляются `CurrentLessonWidget`, `NextLessonWidget`, `DayScheduleWidget`, `PerformanceWidget`. `QuickAccessWidget` отсутствует, хотя в `PulseWidgetsCollection.updateAllPulseWidgets()` он есть. Виджет быстрого доступа показывает устаревшие данные.

#### M-5. Захардкоженная версия в настройках

Файл: `screen/SettingsScreen.kt`, строка 735.

```kotlin
text = "МГКАП v1.3.5 • Build 2026"
```

При смене `versionName`/`versionCode` текст не обновится. Использовать `BuildConfig.VERSION_NAME` и `BuildConfig.VERSION_CODE` (включить `buildConfig = true`).

#### M-6. Firebase Analytics подключён, но не сконфигурирован

Файлы: `app/build.gradle.kts`, строки 66–67; `build.gradle.kts`, строка 5.

Зависимость `firebase-analytics` добавлена, плагин `com.google.gms.google-services` объявлен в корневом build-файле, но не применён в `app`, `google-services.json` отсутствует. Firebase не инициализируется, аналитика не работает, при этом зависимость увеличивает размер APK.

Исправление: либо добавить плагин и конфиг, либо удалить зависимость.

#### M-7. Дублирование логики загрузки и проверки PDF

Файлы: `viewmodel/ScheduleViewModel.kt`, строки 274+; `worker/ScheduleUpdateWorker.kt`, строки 118+.

Два независимых набора `fetchAllPdfUrls`, `downloadFileSecurely`, `isValidPdf`, `calculateMD5`. Любое изменение формата страницы или проверки PDF нужно вносить в двух местах; поведение может разойтись.

Исправление: вынести в `ScheduleRepository`/`PdfScheduleLoader`.

#### M-8. Кастомный обработчик падений перезапускает приложение

Файл: `utils/PulseCrashProtection.kt`, строки 22–60.

При любом `UncaughtException` приложение пишет лог и перезапускает `MainActivity`, затем убивает процесс. Это скрывает реальные падения от пользователя и системной диагностики, может маскировать дефекты и мешать crash-reporting. Защита от цикла реализована, но порог 4 секунды грубый.

Рекомендация: оставить логирование, но не перезапускать приложение автоматически; передавать исключение системному обработчику.

### 5.4 Низкие

1. **Неиспользуемые файлы темы.** `ui/theme/Theme.kt`, `Color.kt`, `Type.kt` не используются: `MainActivity` вызывает `MaterialTheme(colorScheme = colorScheme)` напрямую. `PulseTheme` и `Typography` не подключены; `Color.kt` — шаблонные цвета.
2. **Нет реальных тестов.** `ExampleUnitTest` проверяет `2 + 2`, `ExampleInstrumentedTest` — имя пакета. Бизнес-логика (парсинг, подсчёт пар, аналитика) не покрыта.
3. **Крупные composable-файлы.** `ScheduleScreen.kt` — 2369 строк, `PerformanceScreen.kt` — 1856, `HomeworkScreen.kt` — 1254. Высокая связность, сложно сопровождать и тестировать.
4. **`SystemEventReceiver` экспортирован.** `android:exported="true"` для системных broadcast-событий не требуется; лучше `false`.
5. **`SharedPreferences` + JSON как основное хранилище.** Нет транзакций, миграций и индексов; крупные JSON читаются/пишутся целиком, возможна потеря данных при конкурентной записи.
6. **`release` без оптимизации.** `optimization { enable = false }` — нет R8/ProGuard, APK больше, код не обфусцирован.
7. **Рассинхрон версий зависимостей.** Compose BOM 2026.02.01 соседствует с `material-icons-extended:1.6.8`, `navigation-compose:2.7.7`, `activity-compose:1.8.0`; возможны несовместимости.
8. **`WearSyncManager` не вызывается.** Код и зависимость `play-services-wearable` присутствуют, но синхронизация с часами не запускается.
9. **Политика exact alarm.** Одновременно объявлены `SCHEDULE_EXACT_ALARM` и `USE_EXACT_ALARM`; для приложения-расписания второй может вызвать вопросы при публикации в Google Play.
10. **`allowBackup="true"`.** Резервная копия включает расписание и домашние задания; стоит проверить `data_extraction_rules`/`backup_rules`.

---

## 6. Технический долг

| Область | Проблема | Приоритет |
|---|---|---|
| Хранилище | `SharedPreferences` + JSON вместо Room/DataStore | Высокий |
| Сетевой слой | дублирование загрузки/парсинга PDF | Высокий |
| Логика пар | два разных алгоритма подсчёта | Высокий |
| Фоновая работа | worker не обновляет данные, приёмник пуст | Высокий |
| UI | гигантские composable-файлы | Средний |
| Тесты | отсутствуют | Средний |
| Конфигурация | мёртвые theme-файлы, Firebase без конфига, Wear без вызова | Средний |
| Сборка | отключена оптимизация release | Средний |
| Версионирование | версия захардкожена | Низкий |
| Документация | карта проекта и rollback созданы, ревью добавлено | Низкий |

---

## 7. Приоритетный план исправлений

### P0 — до следующего релиза

1. Включить core library desugaring или поднять `minSdk` до 26 (C-1).
2. Исправить `updateCacheWithRollingWindow`, сохранять историю и все будущие даты (C-2).
3. Реализовать восстановление будильников в `SystemEventReceiver` (C-3).
4. Выставлять `migration_1.1.0_clean` в онбординге (H-1).
5. Ограничить историю уведомлений и безопасно разбирать `NotificationType` (H-5).

### P1 — в течение цикла

6. Свести подсчёт пар в один общий метод (H-3).
7. Подключить `detectRisks` и `generateRecommendations` в отчёте (H-4).
8. Определить поведение `ScheduleUpdateWorker`: обновлять данные или менять текст уведомления (H-2).
9. Вынести общий `ScheduleRepository` и убрать дублирование PDF-логики (M-7).
10. Добавить `QuickAccessWidget` в `WidgetUpdateWorker` (M-4).
11. Обернуть QR-генерацию в корутину и обработку ошибок (M-2).
12. Ограничить размеры bitmap и добавить защиту от OOM (M-3).

### P2 — техническое развитие

13. Перевести хранение на Room или DataStore.
14. Разбить крупные экраны на компоненты и вынести состояние в ViewModel.
15. Добавить unit-тесты для парсинга, подсчёта пар и аналитики.
16. Удалить или подключить `Theme.kt`, `Color.kt`, `Type.kt`.
17. Удалить неиспользуемый `WearSyncManager` или подключить его.
18. Настроить Firebase Analytics либо удалить зависимость.
19. Включить R8 и оптимизацию release.
20. Синхронизировать версии зависимостей и использовать `BuildConfig` для версии.

---

## 8. Заключение

Проект представляет собой функционально насыщенный студенческий продукт с широким набором сценариев и продуманным пользовательским опытом. Основные пользовательские потоки — расписание, домашние задания, успеваемость, уведомления и виджеты — реализованы и связаны между собой.

При этом архитектура остаётся прототипной:

- данные хранятся в `SharedPreferences` и JSON без единого репозитория;
- бизнес-логика частично дублируется между ViewModel и worker;
- отсутствуют реальные тесты;
- есть критические дефекты совместимости (`minSdk 24` + `java.time`) и логики кэша;
- часть функций заявлена, но не подключена (Wear OS, Firebase Analytics, восстановление будильников, риски и рекомендации в аналитике).

Состояние кода можно оценить как «рабочий прототип с хорошим охватом функций». Для стабильного релиза необходимо закрыть P0-дефекты, устранить рассинхронизацию данных и добавить минимальное тестовое покрытие. После этого проект можно развивать в сторону полноценной слоистой архитектуры с Room, единым репозиторием и предсказуемым фоновым обновлением.
