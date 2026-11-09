# МГКАП (Pulse)

Android-приложение для студентов колледжа: расписание замен, домашние задания, успеваемость, преподаватели, уведомления и виджеты.

## Основные сведения

| Параметр | Значение |
|---|---|
| Название | МГКАП |
| Package | `com.example.pulse` |
| Версия | 1.3.5 (versionCode 8) |
| minSdk / targetSdk / compileSdk | 24 / 37 / 37 |
| Язык | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Виджеты | Jetpack Glance |
| Фон | WorkManager, AlarmManager, foreground service |
| Хранение | SharedPreferences + JSON (без Room и без сети, кроме загрузки PDF) |

## Структура

```
app/src/main/java/com/example/pulse/
  App.kt              точка входа Application
  data/               локальное хранилище и менеджеры данных
  screen/             Compose-экраны и навигация
  ui/theme/           тема Material 3
  utils/              парсинг, уведомления, NFC, QR, изображения, аналитика
  viewmodel/          состояние расписания и успеваемости
  widget/             App Widgets на Glance
  worker/             WorkManager и BroadcastReceiver
```

Подробное описание каждого файла: [docs/PROJECT_MAP.md](docs/PROJECT_MAP.md).
Инструкция по откату версии: [docs/ROLLBACK.md](docs/ROLLBACK.md).

## Сборка

Требования: JDK 17+, Android SDK, файл `local.properties` с `sdk.dir`.

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

Подписанный ключ `Key/Pulse_key` в репозиторий не входит. Для release-сборки его нужно добавить локально и настроить `signingConfigs`.

## Архитектура в одном абзаце

`ScheduleViewModel` загружает PDF-файлы замен с сайта колледжа, извлекает текст через PDFBox, парсит его регулярными выражениями и сохраняет расписание в `SharedPreferences` (`schedule_history`). Экраны Compose читают состояние из ViewModel и напрямую из менеджеров данных. Фоновые задачи WorkManager проверяют новые PDF и домашние задания, `AlarmManager` ставит напоминания о парах, foreground service показывает текущую пару в шторке. Виджеты Glance читают те же данные из `SharedPreferences`.
