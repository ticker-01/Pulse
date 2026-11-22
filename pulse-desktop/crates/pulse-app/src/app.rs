//! Корневая структура приложения: состояние, вкладки, фоновое обновление.

use std::sync::mpsc::{self, Receiver, TryRecvError};
use std::time::{Duration, Instant};
use chrono::{Datelike, Days, Local, NaiveDate, NaiveTime};
use pulse_core::bells::{default_schedule, BellConfig, BellDay, BellSlot};
use pulse_core::changes::{self, ScheduleChange};
use pulse_core::datetime;
use pulse_core::homework::HomeworkItem;
use pulse_core::model::{LessonPair, ScheduleCache, Teacher};
use pulse_core::parser;
use pulse_core::performance::Semester;
use pulse_core::source::{self, FetchOutcome};
use pulse_core::updater;

use crate::store::{Role, Settings, Store};
use crate::theme;
use crate::views;

/// Разделы бокового меню.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Tab {
    Schedule,
    Groups,
    Teachers,
    Performance,
    Homework,
    Notifications,
    Settings,
}

impl Tab {
    /// Все вкладки приложения — перечисление для тестов.
    #[cfg_attr(not(test), allow(dead_code))]
    pub(crate) const ALL: [Tab; 7] = [
        Tab::Schedule,
        Tab::Groups,
        Tab::Teachers,
        Tab::Performance,
        Tab::Homework,
        Tab::Notifications,
        Tab::Settings,
    ];

    /// Вкладки студента: учёба, справочник, оценки, задания, напоминания.
    const STUDENT: [Tab; 6] = [
        Tab::Schedule,
        Tab::Teachers,
        Tab::Performance,
        Tab::Homework,
        Tab::Notifications,
        Tab::Settings,
    ];

    /// Вкладки преподавателя: своё расписание, группы, справочник, настройки.
    const TEACHER: [Tab; 4] = [Tab::Schedule, Tab::Groups, Tab::Teachers, Tab::Settings];

    /// Набор вкладок для роли — от него зависит и навигация, и доступность экранов.
    pub(crate) fn for_role(role: Role) -> &'static [Tab] {
        match role {
            Role::Student => &Self::STUDENT,
            Role::Teacher => &Self::TEACHER,
        }
    }

    pub(crate) fn label(self) -> &'static str {
        match self {
            Tab::Schedule => "Расписание",
            Tab::Groups => "Группы",
            Tab::Teachers => "Преподаватели",
            Tab::Performance => "Успеваемость",
            Tab::Homework => "Домашние задания",
            Tab::Notifications => "Уведомления",
            Tab::Settings => "Настройки",
        }
    }

    pub(crate) fn icon(self) -> &'static str {
        match self {
            Tab::Schedule => "📅",
            Tab::Groups => "👥",
            Tab::Teachers => "👤",
            Tab::Performance => "📊",
            Tab::Homework => "📝",
            Tab::Notifications => "🔔",
            Tab::Settings => "⚙",
        }
    }

    /// Длинная подсказка при наведении на вкладку.
    pub(crate) fn hint(self) -> &'static str {
        match self {
            Tab::Schedule => "Пары на день или неделю",
            Tab::Groups => "Расписание любой группы колледжа",
            Tab::Teachers => "Справочник и личное расписание преподавателя",
            Tab::Performance => "Оценки, средний балл, риски и симуляция",
            Tab::Homework => "Задания со сроками и остатком пар",
            Tab::Notifications => "Что требует внимания прямо сейчас",
            Tab::Settings => "Профиль, тема, звонки и данные",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum StatusKind {
    Info,
    Success,
    Error,
}

/// Строка состояния внизу окна.
pub(crate) struct StatusLine {
    pub(crate) text: String,
    pub(crate) kind: StatusKind,
    pub(crate) shown_at: Instant,
}

/// Загруженное в фоне расписание: поток живёт отдельно от UI-потока.
pub(crate) struct FetchJob {
    pub(crate) rx: Receiver<anyhow::Result<FetchOutcome>>,
    pub(crate) started: Instant,
}

/// Окно создания/редактирования предмета.
pub(crate) struct SubjectDialog {
    pub(crate) is_new: bool,
    pub(crate) id: String,
    pub(crate) name: String,
    pub(crate) teacher: String,
    pub(crate) target: String,
    pub(crate) scores: String,
    pub(crate) kr: String,
}

impl SubjectDialog {
    fn from_grade(grade: &pulse_core::performance::SubjectGrade) -> Self {
        Self {
            is_new: false,
            id: grade.id.clone(),
            name: grade.subject_name.clone(),
            teacher: grade.teacher.clone(),
            target: grade
                .target_score
                .map(|value| format!("{value}"))
                .unwrap_or_default(),
            scores: grade
                .scores
                .iter()
                .map(|value| value.to_string())
                .collect::<Vec<_>>()
                .join(" "),
            kr: grade
                .kr_scores
                .iter()
                .map(|value| value.to_string())
                .collect::<Vec<_>>()
                .join(" "),
        }
    }

    fn for_new() -> Self {
        Self {
            is_new: true,
            id: String::new(),
            name: String::new(),
            teacher: String::new(),
            target: String::new(),
            scores: String::new(),
            kr: String::new(),
        }
    }
}

/// Разбор строки с оценками: «8 9,10» → [8, 9, 10].
pub(crate) fn parse_scores(text: &str) -> Vec<i32> {
    text.split(|c: char| c.is_whitespace() || c == ',' || c == ';')
        .filter_map(|part| part.trim().parse::<i32>().ok())
        .filter(|value| (1..=10).contains(value))
        .collect()
}

/// Окно создания/редактирования домашнего задания.
pub(crate) struct HomeworkDialog {
    pub(crate) is_new: bool,
    pub(crate) id: String,
    pub(crate) subject: String,
    pub(crate) task: String,
    pub(crate) due: String,
    pub(crate) notify: bool,
    pub(crate) target_pairs: String,
    pub(crate) assign_date: NaiveDate,
}

impl HomeworkDialog {
    fn from_item(item: &HomeworkItem) -> Self {
        Self {
            is_new: false,
            id: item.id.clone(),
            subject: item.subject.clone(),
            task: item.task.clone(),
            due: datetime::format_date_input(item.due_date),
            notify: item.notify_day_before,
            target_pairs: item
                .target_pairs
                .map(|value| value.to_string())
                .unwrap_or_default(),
            assign_date: item.assign_date,
        }
    }

    fn for_new(subject: String, today: NaiveDate) -> Self {
        Self {
            is_new: true,
            id: String::new(),
            subject,
            task: String::new(),
            due: datetime::format_date_input(today.succ_opt().unwrap_or(today)),
            notify: true,
            target_pairs: String::new(),
            assign_date: today,
        }
    }
}

/// Редактор звонков для одного типа дня.
pub(crate) struct BellsEditor {
    pub(crate) day: BellDay,
    pub(crate) slots: Vec<BellSlot>,
}

impl BellsEditor {
    pub(crate) fn new(day: BellDay, bells: &BellConfig) -> Self {
        let slots = bells
            .custom
            .get(day.key())
            .cloned()
            .unwrap_or_else(|| default_schedule(day));
        Self { day, slots }
    }

    fn is_custom(&self, bells: &BellConfig) -> bool {
        bells.custom.contains_key(self.day.key())
    }
}

pub(crate) struct PulseApp {
    pub(crate) store: Store,
    pub(crate) tab: Tab,
    pub(crate) status: Option<StatusLine>,

    /// Анимации включены в обычном запуске; тесты и скриншоты их выключают,
    /// иначе кадр зависит от момента отрисовки.
    pub(crate) animations: bool,
    /// Когда (в секундах egui) сменилась вкладка и какая вкладка отрисована.
    tab_switched_at: f64,
    shown_tab: Tab,

    pub(crate) settings: Settings,
    pub(crate) bells: BellConfig,
    pub(crate) schedule: ScheduleCache,
    pub(crate) teachers: Vec<Teacher>,
    pub(crate) homework: Vec<HomeworkItem>,
    pub(crate) semesters: Vec<Semester>,
    pub(crate) active_semester: usize,

    pub(crate) raw_text: String,
    pub(crate) groups: Vec<String>,
    pub(crate) active_group: String,
    pub(crate) fetch: Option<FetchJob>,
    /// Замены и переносы, найденные при последнем обновлении с сайта.
    pub(crate) changes: Vec<ScheduleChange>,

    pub(crate) selected_date: NaiveDate,
    pub(crate) week_mode: bool,

    pub(crate) teacher_query: String,
    pub(crate) selected_teacher: Option<String>,
    pub(crate) teacher_date: NaiveDate,
    pub(crate) teacher_schedule: ScheduleCache,
    pub(crate) teacher_schedule_for: Option<String>,
    pub(crate) teacher_week_mode: bool,

    /// Расписание «себя» в режиме преподавателя: считается по `settings.teacher_name`
    /// и не зависит от того, кого смотрят в справочнике.
    pub(crate) my_schedule: ScheduleCache,
    pub(crate) my_schedule_for: Option<String>,

    /// Вкладка «Группы»: какую группу смотрит преподаватель и её расписание.
    pub(crate) group_view_group: String,
    pub(crate) group_view_schedule: ScheduleCache,
    pub(crate) group_view_date: NaiveDate,

    pub(crate) subject_dialog: Option<SubjectDialog>,
    pub(crate) simulation_subject: Option<String>,
    pub(crate) simulate_input: String,

    pub(crate) homework_dialog: Option<HomeworkDialog>,

    pub(crate) bells_editor: Option<BellsEditor>,
    pub(crate) shortened_input: String,
    pub(crate) confirm_reset: bool,

    applied_theme: (bool, u32),

    // ── обновление приложения ───────────────────────────────────────────────

    /// Информация о доступном обновлении (None — не проверяли или нет).
    pub(crate) update_available: Option<updater::UpdateInfo>,
    /// Канал для получения результата фоновой проверки.
    pub(crate) update_rx: Option<mpsc::Receiver<Result<Option<updater::UpdateInfo>, String>>>,
    /// Проверка обновлений сейчас в процессе.
    pub(crate) update_check_active: bool,
    /// Флаг выхода: приложение готовится к перезапуску с обновлением.
    pub(crate) running: bool,
}

impl PulseApp {
    pub(crate) fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let (store, warning) = Store::open();
        Self::with_store(cc, store, warning)
    }

    /// Собирает приложение поверх готового хранилища.
    ///
    /// Отдельный конструктор нужен тестам: они подставляют каталог во временной
    /// папке, чтобы не трогать данные пользователя.
    pub(crate) fn with_store(
        cc: &eframe::CreationContext<'_>,
        store: Store,
        warning: Option<String>,
    ) -> Self {
        let settings = store.load_settings();
        let bells = store.load_bells();
        let schedule = store.load_schedule();
        let teachers = store.load_teachers();
        let homework = store.load_homework();
        let semesters = store.load_semesters();
        let raw_text = store.load_raw_text();
        let changes = store.load_changes();

        theme::install_fonts(&cc.egui_ctx);
        theme::apply(&cc.egui_ctx, settings.dark_theme, settings.accent);

        let today = Local::now().date_naive();
        let groups = parser::extract_all_groups(&raw_text);
        let active_group = if settings.user_group.trim().is_empty() {
            groups.first().cloned().unwrap_or_default()
        } else {
            settings.user_group.clone()
        };
        // Тема уже применена выше — запоминаем ровно то, что установили.
        let applied_theme = (settings.dark_theme, settings.accent);

        let mut app = Self {
            store,
            tab: Tab::Schedule,
            status: None,
            animations: true,
            // Минус бесконечность: первый кадр сразу полностью видимый.
            tab_switched_at: f64::NEG_INFINITY,
            shown_tab: Tab::Schedule,
            settings,
            bells,
            schedule,
            teachers,
            homework,
            semesters,
            active_semester: 0,
            raw_text,
            groups,
            active_group,
            fetch: None,
            changes,
            selected_date: today,
            week_mode: false,
            teacher_query: String::new(),
            selected_teacher: None,
            teacher_date: today,
            teacher_schedule: ScheduleCache::new(),
            teacher_schedule_for: None,
            teacher_week_mode: false,
            my_schedule: ScheduleCache::new(),
            my_schedule_for: None,
            group_view_group: String::new(),
            group_view_schedule: ScheduleCache::new(),
            group_view_date: today,
            subject_dialog: None,
            simulation_subject: None,
            simulate_input: String::new(),
            homework_dialog: None,
            bells_editor: None,
            shortened_input: String::new(),
            confirm_reset: false,
            applied_theme,
            update_available: None,
            update_rx: None,
            update_check_active: false,
            running: true,
        };

        if let Some(warning) = warning {
            app.set_status(StatusKind::Error, warning);
        }

        // В режиме преподавателя сразу строим его расписание из сохранённого текста.
        // Справочник живёт отдельным кэшем и «своё» расписание не трогает.
        if app.settings.role == Role::Teacher {
            if app.settings.teacher_name.trim().is_empty() {
                if let Some(first) = app.teachers.first().map(|teacher| teacher.name.clone()) {
                    app.set_teacher_name(first);
                }
            }
            app.rebuild_my_schedule();
        }

        // Расписание обновляем сами: при первом запуске и когда данные устарели.
        if app.should_auto_fetch(Local::now().timestamp()) {
            app.start_fetch();
        }

        // Фоновая проверка обновления приложения (если включено).
        app.schedule_update_check();

        app
    }

    // ── общие помощники ──────────────────────────────────────────────────────

    pub(crate) fn today(&self) -> NaiveDate {
        Local::now().date_naive()
    }

    pub(crate) fn now(&self) -> NaiveTime {
        Local::now().time()
    }

    /// Текущее Unix-время (для меток автообновления, как `last_fetch_at`).
    pub(crate) fn now_unix(&self) -> i64 {
        Local::now().timestamp()
    }

    pub(crate) fn set_status(&mut self, kind: StatusKind, text: impl Into<String>) {
        self.status = Some(StatusLine {
            text: text.into(),
            kind,
            shown_at: Instant::now(),
        });
    }

    pub(crate) fn save_settings(&mut self) {
        if let Some(error) = self.store.save_settings(&self.settings) {
            self.set_status(StatusKind::Error, error);
        }
    }

    pub(crate) fn save_homework(&mut self) {
        if let Some(error) = self.store.save_homework(&self.homework) {
            self.set_status(StatusKind::Error, error);
        }
    }

    pub(crate) fn save_semesters(&mut self) {
        if let Some(error) = self.store.save_semesters(&self.semesters) {
            self.set_status(StatusKind::Error, error);
        }
    }

    pub(crate) fn semester(&self) -> Option<&Semester> {
        self.semesters.get(self.active_semester)
    }

    pub(crate) fn semester_mut(&mut self) -> Option<&mut Semester> {
        self.semesters.get_mut(self.active_semester)
    }

    /// Пары на дату (пустой срез, если дня нет в кэше).
    pub(crate) fn lessons_on(&self, date: NaiveDate) -> &[LessonPair] {
        self.schedule
            .get(&date)
            .map(|lessons| lessons.as_slice())
            .unwrap_or(&[])
    }

    /// Дни недели, в которую попадает `date`, начиная с понедельника.
    pub(crate) fn week_of(&self, date: NaiveDate) -> Vec<NaiveDate> {
        let monday = date - Days::new(date.weekday().num_days_from_monday() as u64);
        (0..7).filter_map(|shift| monday.checked_add_days(Days::new(shift))).collect()
    }

    // ── группа и пересчёт ────────────────────────────────────────────────────

    /// Переключает активную группу и пересобирает кэш из сохранённого текста.
    pub(crate) fn set_active_group(&mut self, group: String) {
        if self.active_group == group {
            return;
        }
        self.active_group = group;
        self.settings.user_group = self.active_group.clone();
        self.save_settings();
        self.reparse();
    }

    /// Переключает выбранного преподавателя и пересобирает его расписание.
    pub(crate) fn select_teacher(&mut self, name: Option<String>) {
        if self.selected_teacher == name {
            return;
        }
        self.selected_teacher = name;
        self.rebuild_teacher_schedule();
    }

    /// Переключает роль и подгоняет интерфейс под неё.
    pub(crate) fn set_role(&mut self, role: Role) {
        if self.settings.role == role {
            return;
        }
        self.settings.role = role;
        if !Tab::for_role(role).contains(&self.tab) {
            self.tab = Tab::for_role(role)[0];
        }
        if role == Role::Teacher {
            // Подставляем имя из справочника, чтобы экран не был пустым.
            if self.settings.teacher_name.trim().is_empty() {
                if let Some(first) = self.teachers.first() {
                    self.settings.teacher_name = first.name.clone();
                }
            }
            self.rebuild_my_schedule();
        }
        self.save_settings();
    }

    /// Выбирает «себя» в режиме преподавателя.
    pub(crate) fn set_teacher_name(&mut self, name: String) {
        self.settings.teacher_name = name;
        self.save_settings();
        self.rebuild_my_schedule();
    }

    /// Собирает расписание «себя» из `raw_text` по имени в настройках.
    ///
    /// Отдельный кэш нужен потому, что вкладка «Преподаватели» показывает
    /// чужое расписание: раньше оба вида жили в одном поле, и просмотр любого
    /// преподавателя подменял «Моё расписание».
    pub(crate) fn rebuild_my_schedule(&mut self) {
        let name = self.settings.teacher_name.trim().to_string();
        self.my_schedule_for = if name.is_empty() {
            None
        } else {
            Some(name.clone())
        };
        if name.is_empty() || self.raw_text.trim().is_empty() {
            self.my_schedule = ScheduleCache::new();
            return;
        }
        self.my_schedule = parser::get_teacher_schedule(&self.raw_text, &name, &self.bells);
    }

    /// Ctrl+1…N — переход по вкладкам текущей роли.
    fn handle_shortcuts(&mut self, ctx: &egui::Context) {
        // В текстовом поле цифры нужны самому полю ввода.
        if ctx.egui_wants_keyboard_input() {
            return;
        }
        const KEYS: [egui::Key; 6] = [
            egui::Key::Num1,
            egui::Key::Num2,
            egui::Key::Num3,
            egui::Key::Num4,
            egui::Key::Num5,
            egui::Key::Num6,
        ];
        for (index, key) in KEYS.into_iter().enumerate() {
            let pressed = ctx.input(|input| input.modifiers.command && input.key_pressed(key));
            if pressed {
                if let Some(tab) = Tab::for_role(self.settings.role).get(index) {
                    self.tab = *tab;
                }
            }
        }
    }

    /// Расписание выбранного преподавателя (считается из `raw_text`).
    pub(crate) fn rebuild_teacher_schedule(&mut self) {
        let Some(name) = self.selected_teacher.clone() else {
            self.teacher_schedule = ScheduleCache::new();
            self.teacher_schedule_for = None;
            return;
        };
        if self.raw_text.trim().is_empty() {
            self.teacher_schedule = ScheduleCache::new();
        } else {
            self.teacher_schedule =
                parser::get_teacher_schedule(&self.raw_text, &name, &self.bells);
        }
        self.teacher_schedule_for = Some(name);
    }

    /// Выбирает группу для вкладки «Группы» (режим преподавателя).
    pub(crate) fn set_group_view(&mut self, group: String) {
        if self.group_view_group == group {
            return;
        }
        self.group_view_group = group;
        self.rebuild_group_view();
    }

    /// Пересобирает расписание просматриваемой группы из `raw_text`.
    pub(crate) fn rebuild_group_view(&mut self) {
        if self.group_view_group.trim().is_empty() || self.raw_text.trim().is_empty() {
            self.group_view_schedule = ScheduleCache::new();
            return;
        }
        self.group_view_schedule =
            parser::parse_raw_text(&self.raw_text, &self.group_view_group, &self.bells);
    }

    /// Убирает выбранную группу и чистит кэш: чужие пары показывать нельзя.
    pub(crate) fn clear_group(&mut self) {
        self.active_group.clear();
        self.settings.user_group.clear();
        self.schedule = ScheduleCache::new();
        if let Some(error) = self.store.save_schedule(&self.schedule) {
            self.set_status(StatusKind::Error, error);
        }
        self.save_settings();
    }

    /// Как часто обновлять расписание само, если включено автообновление.
    pub(crate) const AUTO_REFRESH_INTERVAL_SECS: i64 = 6 * 60 * 60;

    /// Сколько дней хранить найденные замены и переносы.
    pub(crate) const CHANGES_KEEP_DAYS: i64 = 21;

    /// Скачивать ли расписание при запуске: пустой кэш или устаревшие данные.
    pub(crate) fn should_auto_fetch(&self, now_ts: i64) -> bool {
        if !self.settings.auto_refresh {
            return false;
        }
        // Текста нет вовсе — без загрузки показывать нечего.
        if self.raw_text.trim().is_empty() {
            return true;
        }
        let last = self.settings.last_fetch_at;
        last <= 0 || now_ts.saturating_sub(last) >= Self::AUTO_REFRESH_INTERVAL_SECS
    }

    /// Сколько длится проявление содержимого после смены вкладки, в секундах.
    pub(crate) const TAB_FADE_SECS: f64 = 0.18;

    /// Непрозрачность содержимого вкладки: 0 сразу после переключения,
    /// 1 — через [`Self::TAB_FADE_SECS`]. Без анимаций всегда 1.
    pub(crate) fn tab_fade(&self, now_time: f64) -> f32 {
        if !self.animations {
            return 1.0;
        }
        let progress =
            ((now_time - self.tab_switched_at) / Self::TAB_FADE_SECS).clamp(0.0, 1.0) as f32;
        // ease-out: проявление быстрое, конец мягкий.
        1.0 - (1.0 - progress).powi(3)
    }

    /// Пересобирает кэш расписания активной группы из `raw_text`.
    pub(crate) fn reparse(&mut self) {
        if self.active_group.trim().is_empty() || self.raw_text.trim().is_empty() {
            self.rebuild_my_schedule();
            return;
        }
        let parsed = parser::parse_raw_text(&self.raw_text, &self.active_group, &self.bells);

        let cutoff = Local::now().date_naive() - Days::new(14);
        let mut cache: ScheduleCache = ScheduleCache::new();
        for (date, lessons) in parsed {
            if !lessons.is_empty() && date >= cutoff {
                cache.insert(date, lessons);
            }
        }

        self.schedule = cache;
        if let Some(error) = self.store.save_schedule(&self.schedule) {
            self.set_status(StatusKind::Error, error);
        }
        if self.selected_teacher.is_some() {
            self.rebuild_teacher_schedule();
        }
        self.rebuild_my_schedule();
        if self.schedule.is_empty() {
            let message = if self.active_group.is_empty() {
                "Группа не выбрана: укажите её в настройках или нажмите «Обновить».".to_string()
            } else {
                format!(
                    "Для группы {} в последнем расписании пар не нашлось.",
                    self.active_group
                )
            };
            self.set_status(StatusKind::Info, message);
        }
    }

    // ── загрузка с сайта ─────────────────────────────────────────────────────

    pub(crate) fn start_fetch(&mut self) {
        if self.fetch.is_some() {
            return;
        }
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            let _ = tx.send(source::fetch_schedule_text());
        });
        self.fetch = Some(FetchJob {
            rx,
            started: Instant::now(),
        });
        self.set_status(
            StatusKind::Info,
            "Загружаем расписание с сайта колледжа… Это может занять до минуты.",
        );
    }

    fn poll_fetch(&mut self) {
        let received = match self.fetch.as_ref() {
            None => return,
            Some(job) => match job.rx.try_recv() {
                Ok(result) => Some(result),
                Err(TryRecvError::Empty) => None,
                Err(TryRecvError::Disconnected) => Some(Err(anyhow::anyhow!(
                    "фоновое обновление завершилось без ответа"
                ))),
            },
        };

        let Some(result) = received else { return };
        self.fetch = None;

        match result {
            Ok(outcome) => self.finish_fetch(outcome),
            Err(error) => self.set_status(
                StatusKind::Error,
                format!("Не удалось обновить расписание: {error:#}"),
            ),
        }
    }

    fn finish_fetch(&mut self, outcome: FetchOutcome) {
        // Прошлые версии нужны, чтобы показать, что именно поменял колледж.
        let previous_group = self.schedule.clone();
        let previous_my = self.my_schedule.clone();

        // Запоминаем, откуда пришли данные — это видно в настройках.
        if let Some(url) = outcome.urls.last() {
            self.settings.last_pdf_url = url.clone();
            self.settings.last_pdf_file = outcome
                .last_file_name()
                .map(|name| name.to_string())
                .unwrap_or_default();
        }

        self.raw_text = outcome.text;
        self.groups = parser::extract_all_groups(&self.raw_text);
        self.teachers = parser::extract_teachers(&self.raw_text, &self.teachers);

        if self.active_group.trim().is_empty() {
            self.active_group = self.groups.first().cloned().unwrap_or_default();
            self.settings.user_group = self.active_group.clone();
            self.save_settings();
        }

        if let Some(error) = self.store.save_raw_text(&self.raw_text) {
            self.set_status(StatusKind::Error, error);
        }
        if let Some(error) = self.store.save_teachers(&self.teachers) {
            self.set_status(StatusKind::Error, error);
        }
        // Время загрузки нужно, чтобы при следующем запуске понять,
        // устарело ли расписание.
        self.settings.last_fetch_at = Local::now().timestamp();
        self.save_settings();

        self.reparse();
        self.rebuild_group_view();

        // Что изменилось по сравнению с прошлой версией расписания.
        let found = match self.settings.role {
            Role::Teacher => changes::diff_schedules(&previous_my, &self.my_schedule),
            Role::Student => changes::diff_schedules(&previous_group, &self.schedule),
        };
        let found = changes::keep_recent(found, self.today(), Self::CHANGES_KEEP_DAYS);
        let found_count = found.len();
        self.changes = found;
        if let Some(error) = self.store.save_changes(&self.changes) {
            self.set_status(StatusKind::Error, error);
        }

        let days = self.schedule.len();
        let lessons: usize = self.schedule.values().map(|items| items.len()).sum();
        let mut message = format!(
            "Расписание обновлено: {} файл(ов), дней в кэше — {days}, пар — {lessons}.",
            outcome.parsed_files
        );
        if self.active_group.is_empty() {
            message.push_str(" Группа не выбрана — укажите её в настройках.");
        }
        if found_count > 0 {
            message.push_str(&format!(
                " Изменений в расписании: {found_count} — см. блок «Замены и переносы»."
            ));
        }
        if !outcome.errors.is_empty() {
            message.push_str(&format!(
                " Проблемы с {} файл(ом) — см. журнал ниже.",
                outcome.errors.len()
            ));
            for error in outcome.errors {
                eprintln!("pulse: {error}");
            }
        }
        self.set_status(StatusKind::Success, message);
    }

    // ── диалоги ──────────────────────────────────────────────────────────────

    pub(crate) fn open_subject_dialog(&mut self, id: Option<&str>) {
        let dialog = match id {
            Some(id) => self
                .semester()
                .and_then(|semester| semester.grades.iter().find(|grade| grade.id == id))
                .map(SubjectDialog::from_grade)
                .unwrap_or_else(SubjectDialog::for_new),
            None => SubjectDialog::for_new(),
        };
        self.subject_dialog = Some(dialog);
    }

    pub(crate) fn open_homework_dialog(&mut self, id: Option<&str>) {
        let today = self.today();
        let default_subject = self
            .semester()
            .and_then(|semester| semester.grades.first())
            .map(|grade| grade.subject_name.clone())
            .unwrap_or_default();

        let dialog = match id {
            Some(id) => self
                .homework
                .iter()
                .find(|item| item.id == id)
                .map(HomeworkDialog::from_item)
                .unwrap_or_else(|| HomeworkDialog::for_new(default_subject, today)),
            None => HomeworkDialog::for_new(default_subject, today),
        };
        self.homework_dialog = Some(dialog);
    }

    fn apply_subject_dialog(&mut self, dialog: &SubjectDialog) {
        if dialog.name.trim().is_empty() {
            self.set_status(StatusKind::Error, "Название предмета не может быть пустым.");
            return;
        }
        let target = dialog.target.trim().replace(',', ".");
        let target_score = if target.is_empty() {
            None
        } else {
            match target.parse::<f64>() {
                Ok(value) if (0.0..=10.0).contains(&value) => Some(value),
                _ => {
                    self.set_status(
                        StatusKind::Error,
                        "Целевой балл должен быть числом от 0 до 10.",
                    );
                    return;
                }
            }
        };

        let scores = parse_scores(&dialog.scores);
        let kr_scores = parse_scores(&dialog.kr);

        let Some(semester) = self.semester_mut() else {
            self.set_status(StatusKind::Error, "Семестр не найден.");
            return;
        };

        if dialog.is_new {
            let mut grade = pulse_core::performance::SubjectGrade::new(
                dialog.name.trim(),
                if dialog.teacher.trim().is_empty() {
                    "-"
                } else {
                    dialog.teacher.trim()
                },
            );
            grade.scores = scores;
            grade.kr_scores = kr_scores;
            grade.target_score = target_score;
            semester.grades.push(grade);
        } else if let Some(grade) = semester
            .grades
            .iter_mut()
            .find(|grade| grade.id == dialog.id)
        {
            grade.subject_name = dialog.name.trim().to_string();
            grade.teacher = if dialog.teacher.trim().is_empty() {
                "-".to_string()
            } else {
                dialog.teacher.trim().to_string()
            };
            grade.scores = scores;
            grade.kr_scores = kr_scores;
            grade.target_score = target_score;
        }

        self.save_semesters();
        self.set_status(StatusKind::Success, "Предмет сохранён.");
    }

    fn apply_homework_dialog(&mut self, dialog: &HomeworkDialog) {
        if dialog.subject.trim().is_empty() {
            self.set_status(StatusKind::Error, "Укажите предмет задания.");
            return;
        }
        if dialog.task.trim().is_empty() {
            self.set_status(StatusKind::Error, "Опишите задание.");
            return;
        }
        let due_date = match datetime::parse_date(&dialog.due) {
            Some(date) => date,
            None => {
                self.set_status(
                    StatusKind::Error,
                    "Дата сдачи должна быть в формате 25.11.2026.",
                );
                return;
            }
        };
        let target_pairs = match dialog.target_pairs.trim() {
            "" => None,
            raw => match raw.parse::<u32>() {
                Ok(value) => Some(value),
                Err(_) => {
                    self.set_status(StatusKind::Error, "Количество пар должно быть числом.");
                    return;
                }
            },
        };

        let mut item = match self.homework.iter().find(|item| item.id == dialog.id) {
            Some(existing) => existing.clone(),
            None => HomeworkItem {
                id: dialog.id.clone(),
                ..HomeworkItem::default()
            },
        };
        if dialog.is_new || item.id.is_empty() {
            item = HomeworkItem::default();
        }
        item.subject = dialog.subject.trim().to_string();
        item.task = dialog.task.trim().to_string();
        item.due_date = due_date;
        item.notify_day_before = dialog.notify;
        item.target_pairs = target_pairs;
        item.assign_date = dialog.assign_date;

        pulse_core::homework::upsert(&mut self.homework, item);
        self.save_homework();
        self.set_status(StatusKind::Success, "Задание сохранено.");
    }

    /// Пересчитывает звонки после правки.
    pub(crate) fn apply_bells(&mut self) {
        if let Some(error) = self.store.save_bells(&self.bells) {
            self.set_status(StatusKind::Error, error);
        }
        self.reparse();
        self.rebuild_group_view();
    }

    // ── отрисовка ────────────────────────────────────────────────────────────

    fn ui_top(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.add_space(4.0);
            ui.label(
                egui::RichText::new("МГКАП")
                    .size(20.0)
                    .strong()
                    .color(theme::color(self.settings.accent)),
            );
            ui.add_space(12.0);

            // Роль переключается одним кликом: набор вкладок собирается сразу.
            let mut role = self.settings.role;
            for value in [Role::Student, Role::Teacher] {
                let hint = match value {
                    Role::Student => "Расписание группы, оценки и задания",
                    Role::Teacher => "Своё расписание, группы и кафедра",
                };
                if ui
                    .add(egui::Button::new(value.label()).selected(role == value))
                    .on_hover_text(hint)
                    .clicked()
                {
                    role = value;
                }
            }
            if role != self.settings.role {
                self.set_role(role);
            }

            ui.add_space(12.0);
            match self.settings.role {
                Role::Student => self.ui_group_picker(ui),
                Role::Teacher => self.ui_teacher_picker(ui),
            }

            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let elapsed = self.fetch.as_ref().map(|job| job.started.elapsed().as_secs());
                let label = match elapsed {
                    Some(seconds) => format!("Загружаем… {seconds} с"),
                    None => "⟳ Обновить".to_string(),
                };
                let busy = elapsed.is_some();
                if ui
                    .add_enabled(
                        !busy,
                        egui::Button::new(label.as_str()).min_size(egui::vec2(120.0, 28.0)),
                    )
                    .on_hover_text("Скачать свежий PDF с сайта колледжа и пересобрать расписание")
                    .clicked()
                {
                    self.start_fetch();
                }
                if busy {
                    ui.spinner();
                }
                if ui
                    .button(if self.settings.dark_theme { "☀" } else { "🌙" })
                    .on_hover_text("Сменить тему")
                    .clicked()
                {
                    self.settings.dark_theme = !self.settings.dark_theme;
                    self.save_settings();
                }
            });
        });
    }

    fn ui_group_picker(&mut self, ui: &mut egui::Ui) {
        let label = ui.label("Группа:");
        if self.groups.is_empty() {
            ui.label(egui::RichText::new("—").color(theme::muted(self.settings.dark_theme)));
            return;
        }

        let current = if self.active_group.is_empty() {
            "выбрать".to_string()
        } else {
            self.active_group.clone()
        };
        let mut chosen: Option<String> = None;
        let combo = egui::ComboBox::from_id_salt("group-picker")
            .selected_text(current)
            .width(110.0)
            .show_ui(ui, |ui| {
                for group in &self.groups {
                    let selected = group.eq_ignore_ascii_case(&self.active_group);
                    if ui.selectable_label(selected, group).clicked() {
                        chosen = Some(group.clone());
                    }
                }
            });
        // Список получает имя из подписи — иначе он нечитаем для скринридера.
        combo.response.labelled_by(label.id);
        if let Some(group) = chosen {
            self.set_active_group(group);
        }
    }

    fn ui_teacher_picker(&mut self, ui: &mut egui::Ui) {
        let label = ui.label("Я:");
        if self.teachers.is_empty() {
            ui.label(
                egui::RichText::new("обновите расписание")
                    .color(theme::muted(self.settings.dark_theme)),
            );
            return;
        }

        let current = if self.settings.teacher_name.trim().is_empty() {
            "выбрать".to_string()
        } else {
            self.settings.teacher_name.clone()
        };
        let mut chosen: Option<String> = None;
        let combo = egui::ComboBox::from_id_salt("self-teacher-picker")
            .selected_text(current)
            .width(190.0)
            .show_ui(ui, |ui| {
                for teacher in &self.teachers {
                    let selected = teacher.name == self.settings.teacher_name;
                    if ui.selectable_label(selected, &teacher.name).clicked() {
                        chosen = Some(teacher.name.clone());
                    }
                }
            });
        combo.response.labelled_by(label.id);
        if let Some(name) = chosen {
            self.set_teacher_name(name);
        }
    }

    fn ui_nav(&mut self, ui: &mut egui::Ui) {
        ui.add_space(8.0);
        let mut chosen: Option<Tab> = None;
        for tab in Tab::for_role(self.settings.role) {
            let selected = self.tab == *tab;
            // Иконку рисуем крупнее текста: монохромные эмодзи мельче букв.
            let text = match self.nav_counter(*tab) {
                Some(count) if count > 0 => format!("{} · {count}", tab.label()),
                _ => tab.label().to_string(),
            };
            let button = egui::Button::new((
                egui::RichText::new(tab.icon()).size(16.0),
                egui::RichText::new(text),
            ))
            .selected(selected)
            .min_size(egui::vec2(self.nav_width(), 34.0));
            if ui.add(button).on_hover_text(tab.hint()).clicked() {
                chosen = Some(*tab);
            }
        }
        if let Some(tab) = chosen {
            self.tab = tab;
        }

        ui.add_space(12.0);
        ui.separator();
        ui.add_space(8.0);

        let muted = theme::muted(self.settings.dark_theme);
        let (name, subtitle) = match self.settings.role {
            Role::Student => (
                if self.settings.user_name.trim().is_empty() {
                    "Студент".to_string()
                } else {
                    self.settings.user_name.trim().to_string()
                },
                (!self.settings.user_group.is_empty())
                    .then(|| format!("гр. {}", self.settings.user_group)),
            ),
            Role::Teacher => (
                if self.settings.teacher_name.trim().is_empty() {
                    "Преподаватель".to_string()
                } else {
                    self.settings.teacher_name.trim().to_string()
                },
                Some("преподаватель".to_string()),
            ),
        };
        ui.label(egui::RichText::new(name).strong());
        if let Some(subtitle) = subtitle {
            ui.label(egui::RichText::new(subtitle).color(muted).size(12.0));
        }

        ui.add_space(10.0);
        let lessons_today = match self.settings.role {
            Role::Student => self.lessons_on(self.today()).len(),
            Role::Teacher => self
                .my_schedule
                .get(&self.today())
                .map(Vec::len)
                .unwrap_or(0),
        };
        ui.label(
            egui::RichText::new(format!("Сегодня пар: {lessons_today}"))
                .color(muted)
                .size(12.0),
        );
        if self.settings.role == Role::Student {
            let pending = self
                .homework
                .iter()
                .filter(|item| !item.is_completed)
                .count();
            ui.label(
                egui::RichText::new(format!("Активных заданий: {pending}"))
                    .color(muted)
                    .size(12.0),
            );
        }

        ui.with_layout(egui::Layout::bottom_up(egui::Align::LEFT), |ui| {
            ui.add_space(6.0);
            ui.label(
                egui::RichText::new(format!("версия {}", pulse_core::VERSION))
                    .size(11.0)
                    .color(muted),
            );
        });
    }

    /// Число рядом с названием вкладки — чтобы важное было видно без перехода.
    fn nav_counter(&self, tab: Tab) -> Option<usize> {
        match tab {
            Tab::Homework => Some(self.homework.iter().filter(|item| !item.is_completed).count()),
            Tab::Notifications => Some(self.collect_notices().len()),
            Tab::Teachers => Some(self.teachers.len()),
            Tab::Groups => Some(self.groups.len()),
            _ => None,
        }
    }

    fn nav_width(&self) -> f32 {
        // Самая длинная подпись со счётчиком («Домашние задания · 999»)
        // занимает 219 px — держим запас, иначе текст обрезается.
        224.0
    }

    fn ui_status(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            match &self.status {
                Some(status) => {
                    let color = match status.kind {
                        StatusKind::Info => theme::muted(self.settings.dark_theme),
                        StatusKind::Success => theme::color(pulse_core::performance::COLOR_EXCELLENT),
                        StatusKind::Error => theme::color(pulse_core::performance::COLOR_FAIL),
                    };
                    // Проявляем 0.25 с и гаснем в последние 0.8 с из 12 —
                    // строка появляется и уходит мягко, а не рывком.
                    let alpha = if views::animations_on(ui.ctx()) {
                        let age = status.shown_at.elapsed().as_secs_f32();
                        let mut alpha = (age / 0.25).clamp(0.0, 1.0);
                        if status.kind != StatusKind::Error {
                            alpha = alpha.min(((12.0 - age) / 0.8).clamp(0.0, 1.0));
                        }
                        if alpha < 1.0 {
                            ui.ctx()
                                .request_repaint_after(Duration::from_millis(33));
                        }
                        alpha
                    } else {
                        1.0
                    };
                    ui.label(
                        egui::RichText::new(&status.text)
                            .color(color.gamma_multiply(alpha))
                            .size(12.0),
                    );
                }
                None => {
                    ui.label(
                        egui::RichText::new("Готово. Данные хранятся локально.")
                            .color(theme::muted(self.settings.dark_theme))
                            .size(12.0),
                    );
                }
            }

            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                ui.label(
                    egui::RichText::new(self.store.data_dir())
                        .size(11.0)
                        .color(theme::muted(self.settings.dark_theme)),
                );
            });
        });
    }

    fn ui_dialogs(&mut self, ctx: &egui::Context) {
        if let Some(mut dialog) = self.subject_dialog.take() {
            let mut open = true;
            let mut apply = false;
            let mut cancel = false;
            let title = if dialog.is_new {
                "Новый предмет"
            } else {
                "Предмет"
            };
            egui::Window::new(title)
                .open(&mut open)
                .collapsible(false)
                .resizable(false)
                .default_width(360.0)
                .anchor(egui::Align2::CENTER_CENTER, egui::vec2(0.0, -40.0))
                .show(ctx, |ui| {
                    egui::Grid::new("subject-grid")
                        .num_columns(2)
                        .spacing([10.0, 8.0])
                        .show(ui, |ui| {
                            ui.label("Предмет");
                            ui.add(
                                egui::TextEdit::singleline(&mut dialog.name)
                                    .hint_text("Математика")
                                    .desired_width(240.0),
                            );
                            ui.end_row();

                            ui.label("Преподаватель");
                            ui.add(
                                egui::TextEdit::singleline(&mut dialog.teacher)
                                    .hint_text("Иванов И.И.")
                                    .desired_width(240.0),
                            );
                            ui.end_row();

                            ui.label("Оценки");
                            ui.add(
                                egui::TextEdit::singleline(&mut dialog.scores)
                                    .hint_text("8 9 10")
                                    .desired_width(240.0),
                            );
                            ui.end_row();

                            ui.label("КР");
                            ui.add(
                                egui::TextEdit::singleline(&mut dialog.kr)
                                    .hint_text("7 8")
                                    .desired_width(240.0),
                            );
                            ui.end_row();

                            ui.label("Цель");
                            ui.add(
                                egui::TextEdit::singleline(&mut dialog.target)
                                    .hint_text("9.0")
                                    .desired_width(240.0),
                            );
                            ui.end_row();
                        });
                    ui.add_space(4.0);
                    ui.label(
                        egui::RichText::new("Оценки — числа от 1 до 10 через пробел или запятую.")
                            .size(11.0)
                            .color(theme::muted(self.settings.dark_theme)),
                    );
                    ui.add_space(8.0);
                    ui.horizontal(|ui| {
                        if ui.button("Сохранить").clicked() {
                            apply = true;
                        }
                        if ui.button("Отмена").clicked() {
                            cancel = true;
                        }
                    });
                });

            if apply {
                self.apply_subject_dialog(&dialog);
            } else if open && !cancel {
                self.subject_dialog = Some(dialog);
            }
        }

        if let Some(mut dialog) = self.homework_dialog.take() {
            let mut open = true;
            let mut apply = false;
            let mut cancel = false;
            let title = if dialog.is_new {
                "Новое задание"
            } else {
                "Задание"
            };
            egui::Window::new(title)
                .open(&mut open)
                .collapsible(false)
                .resizable(false)
                .default_width(400.0)
                .anchor(egui::Align2::CENTER_CENTER, egui::vec2(0.0, -40.0))
                .show(ctx, |ui| {
                    egui::Grid::new("homework-grid")
                        .num_columns(2)
                        .spacing([10.0, 8.0])
                        .show(ui, |ui| {
                            ui.label("Предмет");
                            ui.add(
                                egui::TextEdit::singleline(&mut dialog.subject)
                                    .hint_text("Физика")
                                    .desired_width(260.0),
                            );
                            ui.end_row();

                            ui.label("Срок");
                            ui.add(
                                egui::TextEdit::singleline(&mut dialog.due)
                                    .hint_text("25.11.2026")
                                    .desired_width(260.0),
                            );
                            ui.end_row();

                            ui.label("Осталось пар");
                            ui.add(
                                egui::TextEdit::singleline(&mut dialog.target_pairs)
                                    .hint_text("необязательно")
                                    .desired_width(260.0),
                            );
                            ui.end_row();
                        });
                    ui.label("Задание");
                    ui.add(
                        egui::TextEdit::multiline(&mut dialog.task)
                            .hint_text("Лабораторная № 3, оформить отчёт")
                            .desired_rows(3)
                            .desired_width(340.0),
                    );
                    ui.add_space(4.0);
                    ui.checkbox(&mut dialog.notify, "Напомнить накануне");
                    ui.add_space(8.0);
                    ui.horizontal(|ui| {
                        if ui.button("Сохранить").clicked() {
                            apply = true;
                        }
                        if ui.button("Отмена").clicked() {
                            cancel = true;
                        }
                    });
                });

            if apply {
                self.apply_homework_dialog(&dialog);
            } else if open && !cancel {
                self.homework_dialog = Some(dialog);
            }
        }
    }

    pub(crate) fn ui_bells_editor(&mut self, ui: &mut egui::Ui) {
        let Some(mut editor) = self.bells_editor.take() else {
            return;
        };

        let mut save = false;
        let mut reset = false;
        let mut close = false;
        let muted = theme::muted(self.settings.dark_theme);
        let is_custom = editor.is_custom(&self.bells);

        wrap_card(ui, |ui| {
            ui.horizontal(|ui| {
                ui.label(
                    egui::RichText::new(format!("Звонки: {}", editor.day.label_ru())).strong(),
                );
                if is_custom {
                    ui.label(egui::RichText::new("свой вариант").size(11.0).color(muted));
                } else {
                    ui.label(egui::RichText::new("по умолчанию").size(11.0).color(muted));
                }
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.small_button("×").on_hover_text("Закрыть").clicked() {
                        close = true;
                    }
                });
            });
            ui.add_space(4.0);

            let mut remove_at: Option<usize> = None;
            for (index, slot) in editor.slots.iter_mut().enumerate() {
                ui.horizontal(|ui| {
                    ui.label(format!("{}.", index + 1));
                    ui.add_sized(
                        [64.0, 22.0],
                        egui::TextEdit::singleline(&mut slot.start).hint_text("08:00"),
                    );
                    ui.label("–");
                    ui.add_sized(
                        [64.0, 22.0],
                        egui::TextEdit::singleline(&mut slot.end).hint_text("09:30"),
                    );
                    if ui.small_button("×").clicked() {
                        remove_at = Some(index);
                    }
                });
            }
            if let Some(index) = remove_at {
                editor.slots.remove(index);
            }

            ui.horizontal(|ui| {
                if ui.small_button("+ пара").clicked() {
                    editor
                        .slots
                        .push(BellSlot::new("08:30".to_string(), "09:15".to_string()));
                }
                if ui.button("Сохранить").clicked() {
                    save = true;
                }
                if ui.button("Сбросить к стандартным").clicked() {
                    reset = true;
                }
            });
        });
        ui.add_space(8.0);

        if save {
            let valid = editor
                .slots
                .iter()
                .all(|slot| datetime::parse_time(&slot.start).is_some() && datetime::parse_time(&slot.end).is_some());
            if !valid {
                self.set_status(
                    StatusKind::Error,
                    "Время звонков должно быть в формате ЧЧ:ММ.",
                );
                self.bells_editor = Some(editor);
                return;
            }
            self.bells.set_custom(editor.day, editor.slots.clone());
            self.apply_bells();
            self.set_status(StatusKind::Success, "Звонки сохранены, расписание пересчитано.");
            return;
        }

        if reset {
            self.bells.custom.remove(editor.day.key());
            editor.slots = default_schedule(editor.day);
            self.apply_bells();
            self.set_status(StatusKind::Success, "Возвращены стандартные звонки.");
            self.bells_editor = Some(editor);
            return;
        }

        if !close {
            self.bells_editor = Some(editor);
        }
    }
}

/// Локальная обёртка, чтобы не тянуть `views::card` в этот модуль.
fn wrap_card(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    let dark = ui.visuals().dark_mode;
    egui::Frame::new()
        .fill(theme::surface(dark))
        .stroke(egui::Stroke::new(
            1.0,
            theme::color(if dark { theme::SURFACE_MUTED_DARK } else { theme::SURFACE_MUTED_LIGHT }),
        ))
        .inner_margin(egui::Margin::same(14))
        .corner_radius(egui::CornerRadius::same(12))
        .show(ui, add);
}

impl eframe::App for PulseApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        // При обновлении приложения процесс выходит, а скрипт заменяет файл.
        if !self.running {
            ui.ctx().send_viewport_cmd(egui::ViewportCommand::Close);
            return;
        }
        self.draw(ui);
    }
}

impl PulseApp {
    /// Один кадр интерфейса: служебные панели, активная вкладка и окна.
    ///
    /// Отдельный метод (а не тело `eframe::App::ui`) нужен, чтобы кадр можно
    /// было отрисовать без окна — это делают тесты в конце файла.
    pub(crate) fn draw(&mut self, ui: &mut egui::Ui) {
        let ctx = ui.ctx().clone();
        views::set_animations(&ctx, self.animations);
        self.poll_fetch();
        self.poll_update_check();
        self.handle_shortcuts(&ctx);

        // Роль могли сменить в другом окне или из старого файла настроек:
        // держим вкладку в пределах доступных роли.
        let tabs = Tab::for_role(self.settings.role);
        if !tabs.contains(&self.tab) {
            self.tab = tabs[0];
        }

        // ── переход между вкладками ──────────────────────────────────────────
        // Считаем по времени egui, а не по `Instant`: тесты задают своё время.
        let now_time = ctx.input(|input| input.time);
        if self.shown_tab != self.tab {
            self.shown_tab = self.tab;
            self.tab_switched_at = now_time;
        }
        let fade = self.tab_fade(now_time);
        if fade < 1.0 {
            ctx.request_repaint();
        }

        if self.fetch.is_some() {
            ctx.request_repaint_after(Duration::from_millis(250));
        }

        // Статус «успех/инфо» сам гаснет, ошибки остаются на экране.
        if let Some(status) = &self.status {
            let expired = status.kind != StatusKind::Error
                && status.shown_at.elapsed() > Duration::from_secs(12);
            if expired {
                self.status = None;
            } else if status.kind != StatusKind::Error {
                ctx.request_repaint_after(Duration::from_secs(1));
            }
        }

        if self.applied_theme != (self.settings.dark_theme, self.settings.accent) {
            theme::apply(&ctx, self.settings.dark_theme, self.settings.accent);
            self.applied_theme = (self.settings.dark_theme, self.settings.accent);
        }

        egui::Panel::top("pulse-top").show(ui, |ui| {
            ui.add_space(6.0);
            self.ui_top(ui);
            ui.add_space(6.0);
        });

        egui::Panel::bottom("pulse-status").show(ui, |ui| {
            ui.add_space(3.0);
            self.ui_status(ui);
            ui.add_space(3.0);
        });

        egui::Panel::left("pulse-nav")
            .resizable(false)
            .exact_size(self.nav_width() + 24.0)
            .show(ui, |ui| {
                self.ui_nav(ui);
                ui.allocate_space(ui.available_size());
                self.ui_update_banner(ui);
            });

        egui::CentralPanel::default().show(ui, |ui| {
            if fade < 1.0 {
                ui.multiply_opacity(fade);
            }
            egui::ScrollArea::vertical()
                .auto_shrink([false, false])
                .show(ui, |ui| {
                    ui.add_space(6.0);
                    match self.tab {
                        Tab::Schedule => self.ui_schedule(ui),
                        Tab::Groups => self.ui_groups(ui),
                        Tab::Teachers => self.ui_teachers(ui),
                        Tab::Performance => self.ui_performance(ui),
                        Tab::Homework => self.ui_homework(ui),
                        Tab::Notifications => self.ui_notifications(ui),
                        Tab::Settings => self.ui_settings(ui),
                    }
                    ui.add_space(12.0);
                });
        });

        self.ui_dialogs(&ctx);
    }
}

#[cfg(test)]
mod ui_tests {
    //! Кадры интерфейса без окна: egui умеет считать раскладку headless,
    //! поэтому битые панели и панику в отрисовке ловим обычным `cargo test`.

    use super::*;
    use crate::views;
    use pulse_core::performance::SubjectGrade;

    /// Хранилище в уникальном временном каталоге — данные пользователя не трогаем.
    pub(super) fn temp_store(tag: &str) -> Store {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|time| time.as_nanos())
            .unwrap_or_default();
        let dir = std::env::temp_dir().join(format!("pulse-ui-{tag}-{nanos}"));
        let (store, warning) = Store::open_at(&dir);
        assert!(warning.is_none(), "каталог данных недоступен: {warning:?}");
        // Тесты не должны ходить в сеть за расписанием.
        let settings = Settings {
            auto_refresh: false,
            ..Settings::default()
        };
        assert!(store.save_settings(&settings).is_none());
        store
    }

    fn test_app(tag: &str) -> (egui::Context, PulseApp) {
        let ctx = egui::Context::default();
        // Нет глифа — паникуем: «квадратик» на скриншоте легко пропустить.
        ctx.set_missing_glyph_policy(egui::MissingGlyphPolicy::Panic);
        let cc = eframe::CreationContext::_new_kittest(ctx.clone());
        let mut app = PulseApp::with_store(&cc, temp_store(tag), None);
        // Анимации зависят от времени кадра: в тестах они выключены,
        // иначе проверки вёрстки «плавали» бы от момента запуска.
        app.animations = false;
        (ctx, app)
    }

    fn frame(ctx: &egui::Context, app: &mut PulseApp) {
        let input = egui::RawInput {
            screen_rect: Some(egui::Rect::from_min_size(
                egui::Pos2::ZERO,
                egui::vec2(1280.0, 800.0),
            )),
            ..Default::default()
        };
        ctx.run_ui(input, |ui| app.draw(ui))
            .drop_without_applying_deltas();
    }

    fn lesson(pair: u32, subject: &str, start: &str, end: &str) -> LessonPair {
        LessonPair {
            pair_number: pair,
            lessons: pair.to_string(),
            start_time: start.to_string(),
            end_time: end.to_string(),
            subject: subject.to_string(),
            room: "201".to_string(),
            teacher: "Иванов И.И.".to_string(),
            group: "П-11".to_string(),
        }
    }

    /// Наполняет приложение данными, чтобы каждая вкладка рисовала непустые блоки.
    pub(super) fn fill_sample(app: &mut PulseApp) {
        let today = app.today();
        let tomorrow = today.succ_opt().unwrap_or(today);

        app.raw_text = "РАСПИСАНИЕ НА НОЯБРЬ".to_string();
        app.groups = vec!["П-11".to_string(), "П-12".to_string()];
        app.active_group = "П-11".to_string();
        app.schedule.insert(
            today,
            vec![
                lesson(1, "Математика", "08:30", "10:00"),
                lesson(2, "Физика", "10:10", "11:40"),
            ],
        );
        app.schedule
            .insert(tomorrow, vec![lesson(1, "История", "08:30", "10:00")]);

        app.teachers = vec![
            Teacher {
                name: "Иванов И.И.".to_string(),
                subject: "Математика".to_string(),
                room: "201".to_string(),
                ..Teacher::default()
            },
            Teacher {
                name: "Петрова А.С.".to_string(),
                subject: "Физика, История".to_string(),
                room: "305".to_string(),
                ..Teacher::default()
            },
        ];
        app.selected_teacher = Some("Иванов И.И.".to_string());
        app.teacher_schedule = app.schedule.clone();
        app.my_schedule = app.schedule.clone();
        app.my_schedule_for = Some("Иванов И.И.".to_string());

        // Замены и переносы: карточка на «Расписании» и строка в «Уведомлениях».
        app.changes = vec![
            pulse_core::changes::ScheduleChange {
                date: today,
                pair_number: 2,
                group: "П-11".to_string(),
                kind: pulse_core::changes::ChangeKind::Replaced,
                before: Some(pulse_core::changes::LessonBrief {
                    subject: "Физика".to_string(),
                    teacher: "Петрова А.С.".to_string(),
                    room: "305".to_string(),
                    time: "10:10 – 11:40".to_string(),
                }),
                after: Some(pulse_core::changes::LessonBrief {
                    subject: "Математика".to_string(),
                    teacher: "Иванов И.И.".to_string(),
                    room: "201".to_string(),
                    time: "10:10 – 11:40".to_string(),
                }),
            },
            pulse_core::changes::ScheduleChange {
                date: tomorrow,
                pair_number: 1,
                group: "П-11".to_string(),
                kind: pulse_core::changes::ChangeKind::Moved,
                before: Some(pulse_core::changes::LessonBrief {
                    subject: "История".to_string(),
                    teacher: "Петрова А.С.".to_string(),
                    room: "305".to_string(),
                    time: "08:30 – 10:00".to_string(),
                }),
                after: Some(pulse_core::changes::LessonBrief {
                    subject: "История".to_string(),
                    teacher: "Петрова А.С.".to_string(),
                    room: "305".to_string(),
                    time: "12:30 – 14:00".to_string(),
                }),
            },
        ];

        let mut overdue = HomeworkItem::new("Физика", "Отчёт по лабораторной", today);
        overdue.due_date = today - Days::new(2);
        overdue.assign_date = today - Days::new(9);
        let mut due_today = HomeworkItem::new("Математика", "Домашка №4", today);
        due_today.notify_day_before = true;
        let mut upcoming = HomeworkItem::new("История", "Эссе по главе 3", tomorrow);
        upcoming.target_pairs = Some(3);
        let mut done = HomeworkItem::new("Химия", "Задачи 12–18", today);
        done.is_completed = true;
        app.homework = vec![overdue, due_today, upcoming, done];

        let mut math = SubjectGrade::new("Математика", "Иванов И.И.");
        math.scores = vec![9, 10, 8];
        math.kr_scores = vec![9];
        math.target_score = Some(10.0);
        let mut physics = SubjectGrade::new("Физика", "Петрова А.С.");
        physics.scores = vec![3, 4];
        let chemistry = SubjectGrade::new("Химия", "-");
        app.semesters = vec![Semester {
            name: "1 семестр".to_string(),
            grades: vec![math, physics, chemistry],
            ..Semester::new("1 семестр")
        }];

        app.simulation_subject = app.semesters[0].grades.first().map(|grade| grade.id.clone());
        app.simulate_input = "9 10".to_string();
        app.shortened_input = "25.12.2026".to_string();
        app.confirm_reset = true;
    }

    /// Все иконки приложения должны быть в подключённых шрифтах: в тестах egui
    /// паникует на отсутствующем глифе, поэтому «квадратики» всплывут здесь,
    /// а не в собранном окне.
    #[test]
    fn every_used_icon_has_a_glyph() {
        let (ctx, mut app) = test_app("glyphs");

        // Названия подобраны так, чтобы задеть каждую ветку `subject_theme`.
        let subjects = [
            "Математика",
            "Информатика",
            "Физика",
            "Электроника",
            "Химия",
            "Биология",
            "География",
            "Астрономия",
            "Черчение",
            "Охрана труда",
            "Физкультура",
            "Допризывная подготовка",
            "Английский язык",
            "Белорусский язык",
            "Литература",
            "История",
            "Правоведение",
            "Экономика",
            "Логистика",
            "Автомобили",
            "Сервис станков",
            "ПДД",
            "Материаловедение",
            "Машиностроение",
            "Наладка оборудования",
            "Крановое дело",
            "Мехатроника",
            "Монтаж",
            "Инструменты",
            "Оснастка",
            "Нормирование",
            "Гидропривод",
            "Надежность систем",
            "Деловые коммуникации",
            "Практика",
            "Неизвестный предмет",
        ];
        let icons: Vec<&str> = subjects
            .iter()
            .map(|subject| views::subject_visual(subject).0)
            .chain(Tab::ALL.iter().map(|tab| tab.icon()))
            .collect();

        app.tab = Tab::Notifications;
        frame(&ctx, &mut app);

        // Рисуем все иконки разом: любая без глифа паникует на этом кадре.
        let input = egui::RawInput {
            screen_rect: Some(egui::Rect::from_min_size(
                egui::Pos2::ZERO,
                egui::vec2(1280.0, 800.0),
            )),
            ..Default::default()
        };
        ctx.run_ui(input, |ui| {
            for icon in &icons {
                ui.label(egui::RichText::new(*icon).size(20.0));
            }
        })
        .drop_without_applying_deltas();
    }

    #[test]
    fn nav_labels_fit_the_sidebar() {
        // Подписи в меню уже обрезались («Домашние задания ·» без счётчика),
        // поэтому ширину сайдбара проверяем измерением, а не на глаз.
        let (ctx, mut app) = test_app("nav-width");
        fill_sample(&mut app);
        frame(&ctx, &mut app);

        let style = ctx.style_of(ctx.theme());
        let font = style
            .text_styles
            .get(&egui::TextStyle::Button)
            .cloned()
            .unwrap_or_else(|| egui::FontId::proportional(13.0));
        let padding = style.spacing.button_padding.x * 2.0;
        let icon = 16.0 + style.spacing.item_spacing.x;

        for tab in Tab::ALL {
            // Худший случай: счётчик у самой длинной подписи.
            let text = format!("{} · 999", tab.label());
            let width = ctx.fonts_mut(|fonts| {
                fonts
                    .layout_no_wrap(text.clone(), font.clone(), egui::Color32::WHITE)
                    .size()
                    .x
            });
            let needed = width + icon + padding;
            assert!(
                needed <= app.nav_width(),
                "подпись «{text}» не влезает в меню: нужно {needed:.0} px, ширина {} px",
                app.nav_width()
            );
        }
    }

    #[test]
    fn every_tab_renders_with_sample_data() {
        let (ctx, mut app) = test_app("data");
        fill_sample(&mut app);

        // Вкладки студента.
        app.settings.role = Role::Student;
        for tab in Tab::for_role(Role::Student) {
            app.tab = *tab;
            frame(&ctx, &mut app);
            frame(&ctx, &mut app);
        }

        // Недельный режим и пустой выбранный день.
        let today = app.today();
        app.tab = Tab::Schedule;
        app.week_mode = true;
        app.selected_date = today;
        frame(&ctx, &mut app);
        app.week_mode = false;
        app.selected_date = today + Days::new(5);
        frame(&ctx, &mut app);

        // Режим преподавателя: свои пары, группы, справочник и настройки.
        app.settings.role = Role::Teacher;
        app.settings.teacher_name = "Иванов И.И.".to_string();
        app.selected_teacher = Some("Иванов И.И.".to_string());
        app.teacher_schedule = app.schedule.clone();
        app.my_schedule = app.schedule.clone();
        app.my_schedule_for = Some("Иванов И.И.".to_string());
        app.group_view_group = "П-11".to_string();
        app.group_view_schedule = app.schedule.clone();

        for tab in Tab::for_role(Role::Teacher) {
            app.tab = *tab;
            frame(&ctx, &mut app);
            frame(&ctx, &mut app);
        }

        // Недельные режимы: свои пары и расписание группы.
        app.tab = Tab::Schedule;
        app.teacher_week_mode = true;
        frame(&ctx, &mut app);
        app.teacher_week_mode = false;
        app.tab = Tab::Groups;
        app.week_mode = true;
        frame(&ctx, &mut app);
        app.week_mode = false;
        app.settings.role = Role::Student;

        // Открытые окна и редактор звонков.
        let subject_dialog = SubjectDialog::from_grade(&app.semesters[0].grades[0]);
        app.subject_dialog = Some(subject_dialog);
        let homework_dialog = HomeworkDialog::from_item(&app.homework[1]);
        app.homework_dialog = Some(homework_dialog);
        app.bells_editor = Some(BellsEditor::new(BellDay::Monday, &app.bells));
        for tab in [Tab::Performance, Tab::Homework, Tab::Settings, Tab::Notifications] {
            app.tab = tab;
            frame(&ctx, &mut app);
        }
    }

    #[test]
    fn teacher_role_switches_tabs_and_persists() {
        let (ctx, mut app) = test_app("role");
        fill_sample(&mut app);

        app.set_role(Role::Teacher);
        assert_eq!(app.settings.role, Role::Teacher);

        // Набор вкладок у роли свой: оценок и заданий у преподавателя нет.
        let tabs = Tab::for_role(Role::Teacher);
        assert!(tabs.contains(&Tab::Groups));
        assert!(!tabs.contains(&Tab::Performance));
        assert!(!tabs.contains(&Tab::Homework));

        // Имя подставляется из справочника — экран не остаётся пустым.
        assert_eq!(app.settings.teacher_name, "Иванов И.И.");

        // Недоступная роли вкладка откатывается к первой в кадре.
        app.tab = Tab::Homework;
        frame(&ctx, &mut app);
        assert_eq!(app.tab, Tab::Schedule);

        // Роль переживает перезапуск: она лежит в settings.json.
        assert_eq!(app.store.load_settings().role, Role::Teacher);

        app.set_role(Role::Student);
        assert!(Tab::for_role(app.settings.role).contains(&Tab::Performance));
        assert_eq!(app.store.load_settings().role, Role::Student);
    }

    /// Текст с тремя преподавателями: у каждого своя пара в один день.
    fn raw_text_for_teachers(day: NaiveDate) -> String {
        format!(
            "РАСПИСАНИЕ НА {}\n\
             УТВЕРЖДАЮ Директор\n\n\
             No гр. Предмет Преподаватель\n\n\
             │21э│1│Математика│201│Иванов И.И.│\n\
             │21э│2│Физика│202│Петрова А.С.│\n\
             │22э│1│Химия│301│Козлов К.К.│\n",
            day.format("%d.%m.%Y")
        )
    }

    /// Предметы первого дня из кэша — так тест видит, чьё расписание показано.
    fn subjects_of(cache: &pulse_core::model::ScheduleCache, day: NaiveDate) -> Vec<String> {
        cache
            .get(&day)
            .map(|lessons| {
                lessons
                    .iter()
                    .map(|lesson| lesson.subject.clone())
                    .collect()
            })
            .unwrap_or_default()
    }

    #[test]
    fn directory_selection_does_not_touch_my_schedule() {
        // Регрессия: выбор любого преподавателя в справочнике подменял пары
        // в «Моём расписании» — оба вида читали один и тот же кэш.
        let (ctx, mut app) = test_app("teacher-bug");
        let today = app.today();
        app.raw_text = raw_text_for_teachers(today);
        app.groups = vec!["21э".to_string()];
        app.active_group = "21э".to_string();
        app.teachers = parser::extract_teachers(&app.raw_text, &[]);
        app.settings.role = Role::Teacher;
        app.set_teacher_name("Иванов И.И.".to_string());

        assert_eq!(subjects_of(&app.my_schedule, today), vec!["Математика"]);

        // Клик по чужому преподавателю в справочнике.
        app.select_teacher(Some("Петрова А.С.".to_string()));
        frame(&ctx, &mut app);

        assert_eq!(app.selected_teacher.as_deref(), Some("Петрова А.С."));
        assert_eq!(
            subjects_of(&app.teacher_schedule, today),
            vec!["Физика"],
            "справочник должен показывать пары выбранного преподавателя"
        );
        assert_eq!(
            subjects_of(&app.my_schedule, today),
            vec!["Математика"],
            "«Моё расписание» не должно зависеть от справочника"
        );

        // Смена «себя» в настройках пересобирает именно своё расписание.
        app.set_teacher_name("Козлов К.К.".to_string());
        assert_eq!(subjects_of(&app.my_schedule, today), vec!["Химия"]);
    }

    /// Максимальная непрозрачность вершин содержимого вкладки.
    ///
    /// Считаем по тесселированным треугольникам: так проверяется реальный путь
    /// отрисовки (`multiply_opacity`), а не только формула кривой.
    fn content_peak_alpha(ctx: &egui::Context, app: &mut PulseApp, nav_width: f32) -> u8 {
        let input = egui::RawInput {
            screen_rect: Some(egui::Rect::from_min_size(
                egui::Pos2::ZERO,
                egui::vec2(1280.0, 800.0),
            )),
            ..Default::default()
        };
        let output = ctx.run_ui(input, |ui| app.draw(ui));
        let primitives = ctx.tessellate(output.shapes, output.pixels_per_point);
        let mut peak = 0u8;
        for primitive in primitives {
            let egui::epaint::Primitive::Mesh(mesh) = primitive.primitive else {
                continue;
            };
            for vertex in &mesh.vertices {
                // Только середина экрана: шапка и строка статуса не гаснут.
                let in_content =
                    vertex.pos.x > nav_width && vertex.pos.y > 70.0 && vertex.pos.y < 740.0;
                if in_content {
                    peak = peak.max(vertex.color.a());
                }
            }
        }
        peak
    }

    #[test]
    fn tab_switch_fades_in() {
        let (ctx, mut app) = test_app("fade");
        // В тестах анимации выключены: кадр сразу полный.
        assert_eq!(app.tab_fade(0.0), 1.0);

        app.animations = true;
        fill_sample(&mut app);
        app.tab = Tab::Performance;
        // Первый кадр фиксирует момент переключения.
        frame(&ctx, &mut app);
        assert_eq!(app.shown_tab, Tab::Performance);

        let switched_at = app.tab_switched_at;
        assert_eq!(
            app.tab_fade(switched_at),
            0.0,
            "сразу после переключения ещё пусто"
        );
        let middle = app.tab_fade(switched_at + PulseApp::TAB_FADE_SECS / 2.0);
        assert!(
            middle > 0.5 && middle < 1.0,
            "середина перехода должна быть плавной, а не скачком: {middle}"
        );
        assert_eq!(app.tab_fade(switched_at + PulseApp::TAB_FADE_SECS), 1.0);
        assert_eq!(
            app.tab_fade(switched_at + 3_600.0),
            1.0,
            "старая метка не гасит экран"
        );

        // Реальная отрисовка: в разгар перехода содержимое притушено.
        let nav_right = app.nav_width() + 24.0;
        let mid_alpha = content_peak_alpha(&ctx, &mut app, nav_right);
        assert!(
            mid_alpha < 160,
            "содержимое должно проявляться плавно, а не появляться целиком: пик {mid_alpha}"
        );

        // Через несколько кадров всё непрозрачно — «полутусклым» не остаётся.
        for _ in 0..20 {
            frame(&ctx, &mut app);
        }
        let settled = content_peak_alpha(&ctx, &mut app, nav_right);
        assert!(
            settled > 240,
            "после перехода содержимое должно быть полностью видно: пик {settled}"
        );

        // Смена вкладки заново запускает проявление.
        app.tab = Tab::Homework;
        frame(&ctx, &mut app);
        assert_eq!(app.tab_fade(app.tab_switched_at), 0.0);
    }

    #[test]
    fn update_finds_replacements_and_keeps_them_after_restart() {
        let (ctx, mut app) = test_app("changes");
        let today = app.today();

        // Первая версия расписания — она уже лежит в кэше как «прошлая».
        app.raw_text = raw_text_for_teachers(today);
        app.active_group = "21э".to_string();
        app.reparse();
        assert!(!app.schedule.is_empty(), "прошлая версия должна разобраться");

        // Колледж выложил новую версию: 1 пара вместо «Математика» — «Информатика».
        let updated = raw_text_for_teachers(today)
            .replace("│21э│1│Математика│201│Иванов И.И.│", "│21э│1│Информатика│204│Глазовский Н.В.│");
        app.finish_fetch(FetchOutcome {
            urls: vec!["https://college-ripo.by/wp-content/uploads/2026/11/new.pdf".to_string()],
            text: updated,
            parsed_files: 1,
            errors: Vec::new(),
        });

        assert_eq!(app.changes.len(), 1, "изменения: {:?}", app.changes);
        let change = &app.changes[0];
        assert_eq!(change.kind, pulse_core::changes::ChangeKind::Replaced);
        assert_eq!(change.date, today);
        assert_eq!(change.pair_number, 1);
        assert_eq!(
            change.before.as_ref().map(|brief| brief.subject.as_str()),
            Some("Математика")
        );
        assert_eq!(
            change.after.as_ref().map(|brief| brief.subject.as_str()),
            Some("Информатика")
        );

        // Список сохранён на диск: карточка не исчезнет после перезапуска.
        assert_eq!(app.store.load_changes().len(), 1);

        // Кадр с карточкой рисуется без паники (уведомления тоже её показывают).
        app.tab = Tab::Schedule;
        frame(&ctx, &mut app);
        app.tab = Tab::Notifications;
        frame(&ctx, &mut app);

        // Перезапуск с тем же каталогом данных.
        let dir = app.store.data_dir();
        let (store, warning) = Store::open_at(&dir);
        assert!(warning.is_none(), "каталог недоступен: {warning:?}");
        let cc = eframe::CreationContext::_new_kittest(ctx.clone());
        let mut restarted = PulseApp::with_store(&cc, store, None);
        restarted.animations = false;
        assert_eq!(restarted.changes.len(), 1);

        // «Скрыть» убирает карточку и с диска.
        restarted.clear_changes();
        assert!(restarted.changes.is_empty());
        assert!(restarted.store.load_changes().is_empty());
    }

    #[test]
    fn auto_fetch_only_when_data_is_stale() {
        let (_ctx, mut app) = test_app("auto-fetch");

        // Автообновление выключено пользователем — не ходим в сеть никогда.
        app.settings.auto_refresh = false;
        app.settings.last_fetch_at = 0;
        app.raw_text.clear();
        assert!(!app.should_auto_fetch(1_000));

        // Включено и текста нет вовсе — качаем сразу.
        app.settings.auto_refresh = true;
        assert!(app.should_auto_fetch(1_000));

        // Текст есть, но время загрузки неизвестно — тоже качаем.
        app.raw_text = raw_text_for_teachers(app.today());
        assert!(app.should_auto_fetch(1_000));

        // Свежие данные не трогаем: до истечения интервала запросов нет.
        app.settings.last_fetch_at = 1_000;
        assert!(!app.should_auto_fetch(1_060));
        assert!(!app.should_auto_fetch(1_000 + PulseApp::AUTO_REFRESH_INTERVAL_SECS - 1));
        // Ровно интервал выдержан — обновляем.
        assert!(app.should_auto_fetch(1_000 + PulseApp::AUTO_REFRESH_INTERVAL_SECS));
    }

    #[test]
    fn empty_state_renders_for_every_tab() {
        let (ctx, mut app) = test_app("empty");

        for role in [Role::Student, Role::Teacher] {
            app.settings.role = role;
            for tab in Tab::for_role(role) {
                app.tab = *tab;
                frame(&ctx, &mut app);
            }
        }

        assert!(app.homework.is_empty());
        assert!(app.schedule.is_empty());
    }

    #[test]
    fn reset_clears_state_and_files() {
        let (ctx, mut app) = test_app("reset");
        app.homework = vec![HomeworkItem::new("Физика", "Отчёт", app.today())];
        app.save_homework();
        app.raw_text = "РАСПИСАНИЕ НА НОЯБРЬ".to_string();
        assert!(app.store.save_raw_text(&app.raw_text).is_none());
        let today = app.today();
        app.changes = vec![ScheduleChange {
            date: today,
            pair_number: 1,
            ..ScheduleChange::default()
        }];
        assert!(app.store.save_changes(&app.changes).is_none());

        app.tab = Tab::Settings;
        app.reset_all_data();

        assert!(app.homework.is_empty());
        assert!(app.raw_text.is_empty());
        assert!(app.changes.is_empty(), "замены тоже сбрасываются");
        assert!(app.semesters.len() == 1 && app.semesters[0].grades.is_empty());
        assert_eq!(app.settings.accent, crate::store::DEFAULT_ACCENT);
        // Файлы тоже удалены, а не только состояние в памяти.
        assert!(app.store.load_raw_text().is_empty());
        assert!(app.store.load_homework().is_empty());
        assert!(app.store.load_changes().is_empty());
        // После сброса интерфейс должен отрисоваться без данных.
        frame(&ctx, &mut app);
    }
}

#[cfg(all(test, feature = "shots"))]
mod shots {
    //! Рендер вкладок в PNG без окна — для ручной проверки вёрстки.
    //! Запуск: `cargo test -p pulse-app --features shots render_tabs_to_png -- --ignored --nocapture`
    //! (фича `shots` тянет wgpu, поэтому по умолчанию не собирается)

    use super::*;
    use egui_kittest::Harness;

    #[test]
    #[ignore = "пишет PNG в /tmp/pulse-shots; запускать вручную"]
    fn render_tabs_to_png() {
        let dir = std::path::PathBuf::from("/tmp/pulse-shots");
        std::fs::create_dir_all(&dir).expect("каталог для скриншотов");

        let tabs = [
            (Tab::Schedule, "schedule"),
            (Tab::Teachers, "teachers"),
            (Tab::Performance, "performance"),
            (Tab::Homework, "homework"),
            (Tab::Notifications, "notifications"),
            (Tab::Settings, "settings"),
        ];

        for (tab, name) in tabs {
            render(&dir, name, 1280.0, 800.0, move |app| {
                ui_tests::fill_sample(app);
                app.tab = tab;
            });
        }

        // Светлая тема и высокая прокрутка: так видны блоки ниже первого экрана.
        for (tab, name) in [
            (Tab::Performance, "performance-light"),
            (Tab::Settings, "settings-light"),
            (Tab::Homework, "homework-light"),
        ] {
            render(&dir, name, 1280.0, 1500.0, move |app| {
                ui_tests::fill_sample(app);
                app.tab = tab;
                app.settings.dark_theme = false;
            });
        }

        // Режим преподавателя: своё расписание, группы, неделя.
        for (tab, name, week) in [
            (Tab::Schedule, "teacher-schedule", false),
            (Tab::Schedule, "teacher-week", true),
            (Tab::Groups, "teacher-groups", false),
            (Tab::Groups, "teacher-groups-week", true),
        ] {
            render(&dir, name, 1280.0, 1000.0, move |app| {
                ui_tests::fill_sample(app);
                app.settings.role = Role::Teacher;
                app.settings.teacher_name = "Иванов И.И.".to_string();
                app.selected_teacher = Some("Иванов И.И.".to_string());
                app.teacher_schedule = app.schedule.clone();
                app.my_schedule = app.schedule.clone();
                app.my_schedule_for = Some("Иванов И.И.".to_string());
                app.group_view_group = "П-11".to_string();
                app.group_view_schedule = app.schedule.clone();
                app.tab = tab;
                match tab {
                    Tab::Schedule => app.teacher_week_mode = week,
                    _ => app.week_mode = week,
                }
            });
        }

        // Пустое состояние и открытые диалоги.
        render(&dir, "empty-schedule", 1280.0, 800.0, |app| {
            app.tab = Tab::Schedule;
            app.groups = vec!["П-11".to_string()];
        });
        render(&dir, "schedule-week", 1280.0, 1100.0, |app| {
            ui_tests::fill_sample(app);
            app.tab = Tab::Schedule;
            app.week_mode = true;
        });
        render(&dir, "homework-dialog", 1280.0, 800.0, |app| {
            ui_tests::fill_sample(app);
            app.tab = Tab::Homework;
            app.homework_dialog = Some(HomeworkDialog::from_item(&app.homework[0]));
        });
        render(&dir, "bells-editor", 1280.0, 2300.0, |app| {
            ui_tests::fill_sample(app);
            app.tab = Tab::Settings;
            app.bells_editor = Some(BellsEditor::new(BellDay::Monday, &app.bells));
        });
    }

    fn render(
        dir: &std::path::Path,
        name: &str,
        width: f32,
        height: f32,
        setup: impl FnOnce(&mut PulseApp) + 'static,
    ) {
        let store = ui_tests::temp_store(name);
        let mut harness = Harness::builder()
            .with_size(egui::vec2(width, height))
            .wgpu()
            .build_eframe(move |cc| {
                let mut app = PulseApp::with_store(cc, store, None);
                // Кадр должен быть «успокоившимся»: без переходов и пульсации.
                app.animations = false;
                setup(&mut app);
                app
            });

        harness.run_steps(3);
        let image = harness.render().expect("рендер через wgpu");
        let path = dir.join(format!("{name}.png"));
        image.save(&path).expect("сохранить PNG");
        println!("saved {}", path.display());
    }
}