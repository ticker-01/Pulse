# Карта проекта МГКАП (Pulse)

Документ описывает назначение файлов, ключевые функции и связи между модулями. Стиль изложения технический, без оценочных суждений.

## 1. Общая архитектура

Приложение построено по слоям:

1. **Точка входа**: `App.kt`, `screen/MainActivity.kt`.
2. **UI**: Compose-экраны в `screen/`, тема в `ui/theme/`.
3. **Состояние**: `ScheduleViewModel`, `PerformanceViewModel`.
4. **Данные**: менеджеры в `data/`, хранение в `SharedPreferences` в виде JSON.
5. **Интеграции и фоновые задачи**: `utils/`, `worker/`, `widget/`.

Поток данных расписания:

```
college-ripo.by (HTML + PDF)
        |
        v
ScheduleViewModel.refreshData()
  fetchAllPdfUrls -> downloadAndExtractPdf (PDFBox)
  parseRawText -> extractTableChunks -> extractParsedRows
        |
        +--> SharedPreferences schedule_history
        +--> SharedPreferences widget_lessons_today
        +--> LessonAlarmManager.scheduleAlarms()
        +--> StateFlow ScheduleState -> Compose-экраны
```

Поток данных успеваемости:

```
PerformanceScreen
        |
        v
PerformanceViewModel (semesters_data в SharedPreferences)
        +--> PerformanceAnalytics (расчеты и прогнозы)
        +--> PerformanceWidget / loadGlobalAverageScore()
```

Фоновые задачи:

```
App.onCreate()
  ScheduleUpdateWorker  каждые 2 часа (нужна сеть)
  HomeworkWorker        раз в сутки

MainActivity.onCreate()
  WidgetUpdateWorker    каждые 15 минут

AlarmManager -> LessonReminderReceiver -> уведомление / LiveLessonService
BootReceiver -> SystemEventReceiver (заглушка)
```

---

## 2. Корневые файлы

| Файл | Назначение |
|---|---|
| `settings.gradle.kts` | Имя проекта `МГКАП`, подключение модуля `:app`, репозитории Google и Maven Central. |
| `build.gradle.kts` | Корневые плагины: Android Application, Kotlin Compose, Google Services (объявлен, в `app` не применён). |
| `gradle/libs.versions.toml` | Версии AGP, Kotlin, Compose BOM и базовых библиотек. |
| `app/build.gradle.kts` | Параметры Android-модуля: `applicationId`, SDK, версия, Compose, зависимости. |
| `gradle.properties` | Параметры JVM и configuration cache. |
| `local.properties` | Локальный путь к Android SDK. В репозиторий не входит. |
| `Key/Pulse_key` | Приватный ключ подписи (PKCS#8). В репозиторий не входит. |
| `.gitignore` | Исключения Git: сборка, SDK, IDE, ключи. |
| `README.md` | Краткое описание, сборка, структура. |
| `docs/PROJECT_MAP.md` | Настоящий документ. |
| `docs/ROLLBACK.md` | Инструкция по откату версии. |

### `app/build.gradle.kts`

- `namespace` и `applicationId`: `com.example.pulse`.
- `minSdk 24`, `compileSdk 37`, `targetSdk 37`.
- `versionCode 8`, `versionName 1.3.5`.
- Отключена оптимизация release (`optimization { enable = false }`).
- Зависимости: Compose BOM, Material 3, Navigation Compose, WorkManager, Glance, PDFBox Android, ZXing, ML Kit code scanner, Play Services Wearable, Firebase Analytics, Media.

---

## 3. `AndroidManifest.xml`

Разрешения:

- `INTERNET`, `POST_NOTIFICATIONS`;
- `SET_ALARM`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`;
- `RECEIVE_BOOT_COMPLETED`;
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`;
- `NFC` (hardware не обязателен).

Компоненты:

| Компонент | Роль |
|---|---|
| `.App` | Application-класс. |
| `.screen.MainActivity` | Единственная Activity, launcher. |
| `FileProvider` | Выдача PNG-файлов расписания другим приложениям. |
| `CurrentLessonWidgetReceiver`, `NextLessonWidgetReceiver`, `DayScheduleWidgetReceiver`, `PerformanceWidgetReceiver`, `QuickAccessWidgetReceiver` | Приёмники виджетов Glance. |
| `.worker.LessonReminderReceiver` | Приём будильников пар. |
| `.worker.SystemEventReceiver` | Загрузка устройства, смена времени и часового пояса. |
| `.utils.LiveLessonService` | Foreground service для уведомления о текущей паре. |
| `.utils.PulseHceService` | Эмуляция NFC-карты, AID `F0010203040506`. |

Дополнительно объявлен флаг Samsung `ongoing_activity`.

---

## 4. Пакет `com.example.pulse` (корень)

### `App.kt`

Наследник `Application`. В `onCreate()`:

1. Устанавливает `PulseCrashProtection`.
2. Инициализирует `PDFBoxResourceLoader`.
3. Создаёт каналы уведомлений через `PulseNotificationManager`.
4. Регистрирует периодические задачи WorkManager:
   - `ScheduleUpdateWorker` каждые 2 часа с требованием сети, имя работы `ScheduleCheckWork`;
   - `HomeworkWorker` раз в сутки, имя работы `HomeworkCheckWork`.

Связан с `worker/`, `data/PulseNotificationManager`, `utils/PulseCrashProtection`.

### `screen/MainActivity.kt`

Точка входа UI.

- Определяет глобальные цвета Compose: `appPrimaryColor`, `appSurfaceColor`, `appBgColor`, а также `BgDark`, `SurfaceDark`, `PrimaryPurple`, `TextGray`. Эти константы используют почти все экраны и генераторы изображений.
- Загружает `PulseAppPrefs` и выбирает стартовый маршрут:
  - `onboarding`, если `onboarding_completed = false`;
  - `migration`, если не выполнен ключ `migration_1.1.0_clean`;
  - иначе `schedule`.
- Включает повышенную частоту обновления экрана до 120 Гц.
- Создаёт периодическую задачу `WidgetUpdateWorker` каждые 15 минут.
- Применяет динамическую тему Material You при `dynamic_color_enabled`.
- `PulseApp` создаёт `NavHost` и общую панель навигации.

Маршруты:

| Маршрут | Экран |
|---|---|
| `onboarding` | `OnboardingScreen` |
| `migration` | `UpdateMigrationScreen` |
| `schedule` | `ScheduleScreen` |
| `teachers` | `TeachersScreen` |
| `performance` | `PerformanceScreen` |
| `notifications` | `NotificationsScreen` |
| `settings` | `SettingsScreen` |
| `homework` | `HomeworkScreen` |
| `teacher_schedule/{teacherName}` | `TeacherScheduleScreen` |

Ключевые функции: `PulseApp`, `PulseBottomBar`, `MoreActionSheet`, `MoreMenuItem`.

Особенность: `ScheduleViewModel` и `PerformanceViewModel` создаются на уровне Activity и передаются экранам, поэтому состояние общее для вкладок.

---

## 5. Пакет `data/`

### `data/HomeworkManager.kt`

Локальное хранилище домашних заданий.

- Модель `HomeworkItem`: `id`, `subject`, `task`, `dueDate`, `notifyDayBefore`, `isCompleted`, `assignDate`, `targetPairs`.
- Файл настроек: `PulseHomeworkPrefs`, ключ `homework_list`.
- Формат: JSON-массив.
- Функции: `saveHomework`, `deleteHomework`, `toggleCompleted`, `deleteCompleted`, `loadHomeworks`, `saveList`.
- Сортировка при чтении: сначала активные, затем по дате сдачи.

Связан с `HomeworkScreen`, `ScheduleScreen`, `HomeworkWorker`, `HomeworkQrHelper`, `HomeworkNfcHelper`.

### `data/PulseNotificationManager.kt`

Системные уведомления и история уведомлений.

- Канал `pulse_schedule_updates` с высокой важностью.
- `createNotificationChannel` также создаёт канал живого уведомления через `LiveNotificationManager`.
- `sendAndSaveNotification`: публикует уведомление и добавляет запись в начало списка.
- `loadNotifications`: читает историю из `PulseNotificationsPrefs`, ключ `notifications_list`.
- Хранимые поля: `title`, `message`, `time` (`dd.MM HH:mm`), `type`, `isImportant`.

Использует типы `NotificationItem` и `NotificationType` из `screen/NotificationsScreen.kt`. Связан с воркерами и `LessonReminderReceiver`.

### `data/TeacherNotesManager.kt`

Заметки о преподавателях.

- Файл `PulseTeacherNotes`, ключ — имя преподавателя.
- Функции: `saveNote`, `getNote`.
- Используется в `TeacherScheduleScreen`.

---

## 6. Пакет `screen/`

### `screen/OnboardingScreen.kt`

Первичная настройка.

- Шаги: приветствие, имя, группа, разрешение на уведомления.
- Сохраняет `user_name`, `user_group`, `onboarding_completed`.
- Содержит анимированный фон созвездия, пасхалку по серии нажатий.
- По завершении переходит на `schedule`.

Ключевые функции: `OnboardingScreen`, `StepContentUI`, `AnimatedConstellationBackground`.

### `screen/MigrationScreen.kt`

Одноразовая миграция версии 1.1.0.

- При первом запуске удаляет `schedule_history`, `saved_teachers`, `last_raw_pdf_text`, `widget_lessons_today`, `last_pdf_url`, `last_pdf_filename`, `last_pdf_hash`.
- Ставит флаг `migration_1.1.0_clean`.
- Показывает анимацию и переходит на `schedule`.

### `screen/ScheduleScreen.kt`

Главный экран расписания. Самый крупный экран проекта.

Основные функции:

- `ScheduleScreen` — загружает состояние ViewModel, домашние задания, истории, обновления; обрабатывает свайпы между днями; собирает экран из блоков.
- `HeaderSection` — приветствие, группа, переход к сверке и конструктору.
- `LiveScheduleEngine` — определяет текущую пару, следующую пару или отсутствие занятий.
- `StatusActiveCard`, `StatusNextCard`, `FinishedOrFutureCard`, `CalmStatusCard` — карточки состояний.
- `LiveFocusDashboardDialog` — полноэкранный режим текущей пары.
- `WeekCalendarCard` — недельный календарь с отметками домашних заданий.
- `ScheduleTimelineList`, `TimelineItem` — список пар на выбранный день.
- `HomeworkInteractiveCard`, `HomeworkDialog` — карточка и диалог добавления/редактирования задания.
- `CompareGroupBottomSheet` — временный просмотр расписания другой группы без смены настроек.
- `ScheduleConstructorDialog` — выбор стиля и генерация PNG для отправки.
- `EmptyScheduleNeonCard`, `LiveCardWaveBackground`.
- `getSubjectStyle` — цвет и иконка предмета.

Связи: `ScheduleViewModel`, `HomeworkManager`, `PulseStoryAnalyzer`, `StoriesViewer`, `ScheduleImageGenerator`, `UpdateChecker`.

### `screen/TeachersScreen.kt`

Список преподавателей.

- Объединяет преподавателей из оценок (`PerformanceViewModel`) и из текущего расписания.
- Поиск по имени, вкладки «Все» и «Мои».
- Переход на `teacher_schedule/{URI-encoded имя}`.

Функции: `TeachersScreen`, `TeacherTabButton`, `TeacherCard`.

### `screen/TeacherScheduleScreen.kt`

Карточка преподавателя.

- Получает расписание через `ScheduleViewModel.getTeacherSchedule`.
- Показывает метрики, пары по дням, заметку из `TeacherNotesManager`.
- Функции: `TeacherScheduleScreen`, `MetricBadge`, `TeacherTimelineItem`.

### `screen/PerformanceScreen.kt`

Экран успеваемости.

- Семестры: создание, переключение, удаление.
- Предметы: добавление, редактирование, удаление, обычные оценки и оценки за контрольные работы.
- Объединение похожих названий предметов: `levenshteinDistance`, `stringSimilarity`, `areSubjectsSimilar`, `getSubjectGroupKey`, `chooseBestSubjectName`, `mergeSimilarGrades`.
- Средний балл, режимы округления, диаграммы, статистика.
- Аналитические блоки: стипендия, качество знаний, распределение оценок, влияние предметов, диагностика, динамика, симулятор оценок.
- Вспомогательные функции: `formatScoreByMode`, `customRound`, `getNeededGradesText`, `getGradeVisual`.

Связи: `PerformanceViewModel`, `ScheduleViewModel` (автодобавление предметов), `PerformanceAnalytics`.

### `screen/NotificationsScreen.kt`

История уведомлений.

- Вкладки по типам, свайп для удаления, карточки.
- При первом открытии создаёт приветственное уведомление.
- Определяет общие типы:
  - `NotificationType`: `SCHEDULE_CHANGE`, `LESSON_REMINDER`, `REMINDER` и другие;
  - `NotificationItem`.
- `removeNotificationLocally` удаляет запись из `SharedPreferences`.

Функции: `NotificationsScreen`, `SwipeLiquidBackground`, `NotificationItemCard`, `TabButton`.

### `screen/SettingsScreen.kt`

Настройки приложения.

Разделы:

- Профиль: имя и группа.
- Отображение: динамические цвета, сокращённые дни, редактор звонков.
- Уведомления: напоминание до пары и интервал, начало пары, конец пары, живое отслеживание, автобудильник и время на сборы.
- Приложение: проверка обновлений, очистка кэша расписания, версия.
- Пасхалка: три нажатия по версии открывают `Magic8BallDialog`.

Ключи `PulseAppPrefs`: `user_name`, `user_group`, `dynamic_color_enabled`, `lesson_reminders_enabled`, `reminder_offset_minutes`, `notify_lesson_start`, `notify_lesson_end`, `live_notifications_enabled`, `auto_alarm_enabled`, `auto_alarm_prep_minutes`, `shortened_days`, `custom_bell_schedule`.

Функции: `SettingsScreen`, `WaveCacheClearOverlay`, `TimeInputField`, `BellScheduleEditorDialog`. Модель `PdfSourceItem` описывает загруженные PDF-источники.

Связи: `SystemAlarmHelper`, `LiveNotificationManager`, `LiveLessonService`, `UpdateChecker`, `updateAllPulseWidgets`.

### `screen/HomeworkScreen.kt`

Экран домашних заданий.

- Вкладки «Активные» и «Выполненные», группировка по дате сдачи.
- Удаление одного задания и очистка выполненных.
- Экспорт активных заданий в QR (`HomeworkQrHelper`).
- Сканирование QR через ML Kit.
- NFC: запись на метку и чтение с другой карты (`HomeworkNfcHelper`, `PulseHceService`).
- Компоненты: `NfcHologramCardDialog`, `MgcapMinimalistCard`, `TabButtonHW`, `DateHeader`, `EmptyState`, `HomeworkCard`.
- `Context.findActivity()` — поиск Activity из Compose-контекста.

### `screen/Magic8BallDialog.kt`

Пасхалка «магический шар».

- Список ответов, анимация жидкости и пузырьков.
- Функция `Magic8BallDialog`.

---

## 7. Пакет `viewmodel/`

### `viewmodel/ScheduleViewModel.kt`

Центральный компонент расписания.

Модели:

- `LessonPair`: номер пары, номера занятий, время начала и конца, предмет, аудитория, преподаватель, группа. Содержит вычисляемые `startLocalTime`, `endLocalTime`, `cleanSubject`.
- `Teacher`: имя, предметы, аудитории, email, цвет аватара.
- `ScheduleState`: загрузка, выбранная дата, пары, преподаватели, группа, имя, URL PDF, группа сравнения, доступные группы.

Состояние и хранение:

- `StateFlow<ScheduleState>` для UI.
- `scheduleCache`: карта `LocalDate -> List<LessonPair>`.
- `lastRawText`: исходный текст PDF в `PulseAppPrefs`.
- `previewScheduleCache`: расписание другой группы без сохранения в настройки.

Основные функции:

| Функция | Назначение |
|---|---|
| `refreshData` | Скачивает все PDF замен, извлекает текст, парсит, сохраняет кэш, преподавателей и группы. |
| `fetchAllPdfUrls` | Ищет ссылки на PDF на странице замен. |
| `downloadAndExtractPdf` | Скачивает PDF с лимитом 20 МБ и извлекает текст через PDFBox. |
| `parseRawText` | Разбирает текст в расписание для указанной группы. |
| `extractParsedRows` | Разбирает строки таблицы по разделителям и регулярным выражениям. |
| `extractTableChunks` | Делит строки на блоки отдельных групп. |
| `extractAllGroups` | Собирает список групп из текста. |
| `setPreviewGroup` | Включает и выключает режим сверки с другой группой. |
| `selectDate` | Переключает выбранный день. |
| `getAllGroupLessons` | Возвращает все пары из кэша. |
| `getRemainingPairs` | Считает, сколько пар по предмету осталось до сдачи задания. |
| `countLessonsUntil` | Считает пары по предмету до даты. |
| `findDateForNextSubject` | Ищет дату следующей пары по предмету. |
| `getTeacherSchedule` | Строит расписание конкретного преподавателя. |
| `getBellSchedule` | Возвращает звонки с учётом дня, сокращённых дней и пользовательского расписания. |
| `updateCacheWithRollingWindow` | Хранит 14 дней истории и все будущие загруженные дни. |
| `updateWidgetData` | Сохраняет пары на сегодня для виджетов и ставит будильники. |
| `loadTeachersFromPrefs`, `saveTeachersToPrefs`, `extractAndSaveTeachers` | Работа с локальным списком преподавателей. |

Связан с `ScheduleScreen`, `TeachersScreen`, `TeacherScheduleScreen`, `PerformanceScreen`, `PerformanceViewModel`, `LessonAlarmManager`, `PulseWidgetsCollection`.

### `viewmodel/PerformanceViewModel.kt`

Состояние успеваемости.

- `SubjectGrade`: предмет, преподаватель, обычные оценки, оценки за КР, целевой балл. Вычисляет `average`, `krAverage`, `effectiveAverage` (среднее между обычными оценками и КР).
- `Semester`: имя и список предметов.
- Хранение: `PulsePerformancePrefs`, ключ `semesters_data`.
- Функции: `selectSemester`, `addSemester`, `deleteSemester`, `addOrUpdateGrade`, `deleteGrade`, `syncWithLessons`, `saveData`, `loadData`.
- `syncWithLessons` при включённом `auto_transfer` добавляет новые предметы из расписания в последний семестр.

---

## 8. Пакет `utils/`

### `utils/DateTimeUtils.kt`

- Русские форматтеры даты и времени.
- `parseLocalTimeOrNull`: поддерживает `HH:mm`, `8:30`, `08.30`, `8:30:00`.
- `parseLocalDateOrNull`: поддерживает ISO и `dd.MM.yyyy` / `dd.MM.yy`.
- Extension-функции для строк.

### `utils/LessonAlarmManager.kt`

- Читает настройки напоминаний.
- Ставит точные будильники на сегодня и завтра:
  - `PRE` — за N минут до пары;
  - `START` — начало;
  - `END` — конец.
- `setExactAndAllowWhileIdle` при наличии разрешения.
- Вызывает `SystemAlarmHelper.syncAutoAlarmForTomorrow`.
- Синхронизирует живое уведомление.

Связан с `LessonReminderReceiver`, `ScheduleViewModel`.

### `utils/SystemAlarmHelper.kt`

- Создаёт будильник в системном приложении «Часы» на первую пару завтрашнего дня.
- Ограничение: не позже 4-й пары.
- Учитывает время на сборы `auto_alarm_prep_minutes`.
- Защита от повторной установки через `last_auto_alarm_state`.
- `triggerAutoAlarmNow` читает `schedule_history` и запускает установку вручную.
- `getBellStartTimeForLesson` возвращает время звонка с учётом дня и пользовательского расписания.

### `utils/LiveNotificationManager.kt`

- Канал `pulse_live_lesson_v2`.
- `showOngoingLesson`: постоянное уведомление с обратным отсчётом до конца пары.
- `showBreak`: уведомление о перемене и следующей паре.
- `clear`: удаляет уведомление и останавливает foreground-режим.
- `triggerTestNotification`: тестовая пара.
- `syncCurrentLiveStatus`: по `widget_lessons_today` определяет текущую пару или перемену.

### `utils/LiveLessonService.kt`

- Foreground service для живого уведомления.
- `start` и `stop` через companion object.
- При ошибке запуска публикует уведомление напрямую.
- Возвращает `START_STICKY`.

### `utils/PulseCrashProtection.kt`

- Перехватывает необработанные исключения.
- Пишет стек в `filesDir/mgkap_crash_log.txt`.
- Перезапускает `MainActivity`.
- При повторном падении в течение 4 секунд передаёт обработку системе.

### `utils/UpdateChecker.kt`

- Загружает `app_version.json` из `https://raw.githubusercontent.com/ticker-01/Pulse-Releases/main/app_version.json`.
- Сравнивает `latest_version_code` с текущим.
- Возвращает `UpdateInfo` или `null`.
- `openDownloadLink` открывает ссылку.
- `UpdateAvailableDialog` — диалог обновления.

### `utils/WearSyncManager.kt`

- Отправляет данные на Wear OS через `Wearable DataClient`, путь `/pulse_wear_data`.
- Передаёт имя, следующую пару, аудиторию, время, минуты, средний балл, число пар, дату.
- В текущем коде прямых вызовов нет; модуль подготовлен, но не подключён к UI.

### `utils/HomeworkQrHelper.kt`

- `exportToQrPayload`: активные задания -> JSON -> GZIP -> Base64, префикс `MGKAP_HW:`.
- `importFromQrPayload`: поддерживает `MGKAP_HW:` и `PULSE_HW:`, проверяет дубликаты по предмету и тексту.
- `generateQrBitmap`: рисует QR через ZXing.

### `utils/HomeworkNfcHelper.kt`

Обмен домашними заданиями через NFC.

- Результаты: `NfcWriteResult` и `NfcImportResult`.
- `isNfcSupported`, `isNfcEnabled`.
- `buildCardPayload`: компактный формат `PULSE_B:` для небольших меток.
- `compressForCard`: формат `PULSE_C:Имя|PULSE_HW:...`.
- `parseAnyPayload`: разбирает `PULSE_C`, JSON и QR-формат.
- `importAnyPayload`: импорт из HCE, NDEF или QR.
- `readPayloadFromTag`: читает IsoDep с чанками, затем NDEF.
- `writePayloadToPhysicalTag`: запись в NDEF, форматируемую метку или Mifare Ultralight.

### `utils/PulseHceService.kt`

- `HostApduService`, эмулирует NFC-карту.
- Отдаёт `currentPayload` по SELECT AID.
- Если данных больше 220 байт, делит их на чанки с маркером `0xAA`.
- Команда `00 C0 index 00 00` возвращает следующий чанк.
- `onPayloadSent` уведомляет UI.

Связан с `HomeworkNfcHelper` и `HomeworkScreen`.

### `utils/PerformanceAnalytics.kt`

Расчёты успеваемости.

- Модели: `ScholarshipStatus`, `AcademicStatus`, `GradeDistribution`, `SubjectImpact`, `SubjectRisk`, `SubjectTrend`, `DiagnosticsSummary`, `ActionRecommendation`, `SimulationResult`, `DeepPerformanceReport`.
- `buildDeepReport` собирает полный отчёт.
- `calculateDistribution` — распределение оценок, качество знаний, успеваемость.
- `calculateScholarshipStatus` — пороги стипендии для 10- и 5-балльной шкалы.
- `calculateAcademicStatus` — текстовый статус.
- `calculateDriversAndAnchors` — предметы, повышающие и понижающие средний балл.
- `detectRisks` — задолженности, пограничные случаи, отсутствие оценок, низкие КР.
- `calculateTrends` — динамика по первой и второй половине оценок.
- `calculateDiagnostics` — влияние КР, стабильность, выбросы.
- `generateRecommendations` — рекомендации по действиям.
- `simulateGrades` — прогноз при добавлении оценок.

Примечание: в текущей реализации `buildDeepReport` передаёт `risks = emptyList()` и `recommendations = emptyList()`, хотя соответствующие функции реализованы.

### `utils/PulseStories.kt`

Истории и обучение.

- Модели `StoryPage`, `StoryStepItem`, `StoryGroup`.
- `PulseStoryAnalyzer.getGreetingStory` — обучающая история.
- `getHolidayStory` — праздничная история по дате.
- `generateWeeklyStories` — статистика недели.
- `markAsViewed` — отметка просмотра в `PulseAppPrefs`.
- UI: `AnimatedGlowingWaveBackground`, `StoryAvatar`, `StoriesViewer` с таймером и паузой.

### `utils/ScheduleImageGenerator.kt`

- `CustomTheme` — цвета изображения.
- `getDynamicTheme` — тема из текущих цветов приложения.
- `generateStoryShareBitmap` — PNG 1080x1920 для истории.
- `generateBitmap` — PNG расписания на день с карточками пар.
- `shareBitmap` — сохранение в `cache/shared_schedules` и отправка через `FileProvider`.

### `utils/SubjectThemeHelper.kt`

- `SubjectTheme`: emoji, цвет, иконка, категория.
- `getTheme` кэширует результат по названию предмета.
- `computeTheme` сопоставляет название с одной из примерно 40 категорий: математика, IT, физика, электроника, химия, языки, история, экономика, логистика, машиностроение и другие.

### `utils/WidgetWaveGenerator.kt`

- `createWaveBitmap`: рисует фон виджета с волной и градиентом.
- Уровень волны зависит от переданного прогресса.

---

## 9. Пакет `widget/`

### `widget/PulseWidgetsCollection.kt`

Пять виджетов Glance:

| Класс | Содержимое |
|---|---|
| `CurrentLessonWidget` | Текущая пара, прогресс, прошло и осталось минут. |
| `NextLessonWidget` | Следующая пара, время и аудитория. |
| `DayScheduleWidget` | Список пар на сегодня. |
| `PerformanceWidget` | Средний балл. |
| `QuickAccessWidget` | Три кнопки быстрого доступа. |

Общие функции:

- `updateAllPulseWidgets` — обновление всех пяти.
- `loadWidgetData` — читает `widget_lessons_today`, вычисляет текущую и следующую пару.
- `loadGlobalAverageScore` — читает последний семестр из `PulsePerformancePrefs`.

### `widget/PulseGlanceTheme.kt`

- `CustomWidgetColors` — фиксированная палитра виджетов.
- `PulseWidgetTheme` — переключает динамические цвета при наличии поддержки.

### `widget/WidgetWaveGenerator.kt`

Описано в разделе `utils/` из-за расположения файла в `widget/`.

---

## 10. Пакет `worker/`

### `worker/ScheduleUpdateWorker.kt`

- Периодическая проверка новых PDF каждые 2 часа.
- Если `changes_enabled = false`, завершается без работы.
- Скачивает PDF, проверяет сигнатуру `%PDF`, считает MD5 каждого файла и общий MD5.
- Сравнивает с `last_pdf_composite_hash`.
- При изменении отправляет уведомление и сохраняет URL, имена, хэши.
- Повторяет логику поиска ссылок из `ScheduleViewModel`, но не парсит расписание.

### `worker/HomeworkWorker.kt`

- Раз в сутки проверяет задания на завтра.
- Для заданий с `targetPairs` считает оставшиеся пары по `schedule_history`.
- Отправляет одно сводное уведомление.

### `worker/LessonReminderReceiver.kt`

- Принимает будильники `PRE`, `START`, `END`.
- `PRE` — уведомление о скорой паре.
- `START` — уведомление о начале и запуск живого уведомления.
- `END` — уведомление о конце и синхронизация живого статуса.

### `worker/SystemEventReceiver.kt`

- Принимает `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`.
- В текущей версии тело обработчика пустое: receiver зарегистрирован, но восстановление будильников не выполняет.

### `worker/WidgetUpdateWorker.kt`

- Каждые 15 минут обновляет четыре виджета: текущая пара, следующая пара, расписание дня, успеваемость.
- `QuickAccessWidget` не обновляется.

---

## 11. Пакет `ui/theme/`

| Файл | Назначение |
|---|---|
| `Theme.kt` | `PulseTheme` с тёмной, светлой и динамической схемой. В `MainActivity` используется собственная настройка `MaterialTheme`, поэтому `PulseTheme` фактически не вызывается. |
| `Color.kt` | Стандартная палитра шаблона Android Studio, в интерфейсе не используется. |
| `Type.kt` | Базовая типографика Material 3, переопределён только `bodyLarge`. |

---

## 12. Ресурсы

| Путь | Назначение |
|---|---|
| `res/values/strings.xml` | Единственная строка `app_name = МГКАП`. |
| `res/values/colors.xml` | Стандартные цвета шаблона. |
| `res/values/themes.xml` | `Theme.MGKAP` и `Theme.Pulse` на основе `android:Theme.Material.Light.NoActionBar`. |
| `res/drawable/ic_launcher_*` | Иконка приложения. |
| `res/mipmap-*` | Растровые иконки. |
| `res/xml/apduservice.xml` | AID `F0010203040506` для HCE. |
| `res/xml/provider_paths.xml` | Доступ FileProvider к `cache/shared_schedules`. |
| `res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Правила резервного копирования. |
| `res/xml/widget_*.xml` | Метаданные пяти виджетов. |
| `keepRules/rules.keep` | Заготовка правил R8. |

---

## 13. Тесты

| Файл | Содержание |
|---|---|
| `app/src/test/.../ExampleUnitTest.kt` | Проверка `2 + 2 = 4`. |
| `app/src/androidTest/.../ExampleInstrumentedTest.kt` | Проверка package name. |

Содержательных тестов бизнес-логики нет.

---

## 14. Хранилища и ключи `SharedPreferences`

| Файл | Ключи | Кто пишет |
|---|---|---|
| `PulseAppPrefs` | `onboarding_completed`, `migration_1.1.0_clean`, `user_name`, `user_group`, `dynamic_color_enabled`, `lesson_reminders_enabled`, `reminder_offset_minutes`, `notify_lesson_start`, `notify_lesson_end`, `live_notifications_enabled`, `auto_alarm_enabled`, `auto_alarm_prep_minutes`, `shortened_days`, `custom_bell_schedule`, `schedule_history`, `last_raw_pdf_text`, `last_pdf_url`, `last_pdf_filename`, `last_pdf_urls_json`, `last_pdf_hash`, `last_pdf_composite_hash`, `widget_lessons_today`, `saved_teachers`, `rounding_mode`, `auto_transfer`, `changes_enabled`, `tutorial_auto_opened`, `story_viewed_*`, `ignored_update_version`, `last_auto_alarm_state` | `MainActivity`, `SettingsScreen`, `ScheduleViewModel`, `ScheduleUpdateWorker`, `PulseStories`, `ScheduleScreen` |
| `PulseHomeworkPrefs` | `homework_list` | `HomeworkManager` |
| `PulseNotificationsPrefs` | `notifications_list` | `PulseNotificationManager` |
| `PulsePerformancePrefs` | `semesters_data` | `PerformanceViewModel` |
| `PulseTeacherNotes` | имя преподавателя | `TeacherNotesManager` |
| `pulse_crash_protection_prefs` | `last_crash_time` | `PulseCrashProtection` |

---

## 15. Замеченные технические особенности

1. `minSdk = 24`, но код использует `java.time` без core library desugaring. На Android 7.x возможен `NoClassDefFoundError`.
2. `SystemEventReceiver` не восстанавливает будильники после перезагрузки.
3. `WidgetUpdateWorker` не обновляет `QuickAccessWidget`.
4. Логика поиска PDF-ссылок дублируется в `ScheduleViewModel` и `ScheduleUpdateWorker`.
5. `PerformanceAnalytics.detectRisks` и `generateRecommendations` реализованы, но не подключены в `buildDeepReport`.
6. `WearSyncManager` не вызывается из UI.
7. Корневой `build.gradle.kts` объявляет Google Services, но модуль `app` плагин не применяет; `google-services.json` отсутствует.
8. `ScheduleUpdateWorker` только уведомляет об изменении PDF, но не обновляет расписание в приложении. Данные обновляются при следующем открытии `ScheduleScreen`.
9. Цвета и тема частично задаются глобальными изменяемыми переменными в `MainActivity`, а не через `MaterialTheme`.
10. `Key/Pulse_key` является приватным ключом подписи и исключён из репозитория.

---

## 16. Сборка и запуск

```bash
# debug
./gradlew assembleDebug

# release
./gradlew assembleRelease

# очистка
./gradlew clean
```

Для release необходимы локальный ключ подписи и настройка `signingConfigs` в `app/build.gradle.kts`. В текущем файле она отсутствует.
