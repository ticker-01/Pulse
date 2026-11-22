//! Общие элементы интерфейса и подмодули вкладок.

pub(crate) mod groups;
pub(crate) mod homework;
pub(crate) mod notifications;
pub(crate) mod performance;
pub(crate) mod schedule;
pub(crate) mod settings;
pub(crate) mod teachers;

use eframe::egui;
use pulse_core::performance::{
    COLOR_EXCELLENT, COLOR_FAIL, COLOR_GOOD, COLOR_NEUTRAL, COLOR_SATISFACTORY,
};
use pulse_core::subject_theme;

use crate::theme;

pub(crate) fn dark(ui: &egui::Ui) -> bool {
    ui.visuals().dark_mode
}

pub(crate) fn muted_color(ui: &egui::Ui) -> egui::Color32 {
    theme::muted(dark(ui))
}

pub(crate) fn border_color(ui: &egui::Ui) -> egui::Color32 {
    theme::color(if dark(ui) {
        theme::SURFACE_MUTED_DARK
    } else {
        theme::SURFACE_MUTED_LIGHT
    })
}

/// Карточка с рамкой и отступами — основной контейнер интерфейса.
pub(crate) fn card(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    card_with(ui, None, add);
}

/// Карточка с подложкой заданного цвета (для акцентных блоков).
pub(crate) fn card_with(
    ui: &mut egui::Ui,
    fill: Option<egui::Color32>,
    add: impl FnOnce(&mut egui::Ui),
) {
    // Карточка занимает всю доступную ширину: содержимое растягиваем на месте.
    let width = ui.available_width();
    egui::Frame::new()
        .fill(fill.unwrap_or_else(|| theme::surface(dark(ui))))
        .stroke(egui::Stroke::new(1.0, border_color(ui)))
        .inner_margin(egui::Margin::same(14))
        .corner_radius(egui::CornerRadius::same(12))
        .show(ui, |ui| {
            // Карточка всегда на всю ширину — иначе блоки «пляшут» по ширине.
            let inner = (width - 28.0).max(0.0);
            ui.set_min_width(inner);
            add(ui);
        });
    ui.add_space(8.0);
}

pub(crate) fn section_title(ui: &mut egui::Ui, text: impl Into<String>) {
    ui.add_space(4.0);
    ui.label(egui::RichText::new(text.into()).size(16.0).strong());
    ui.add_space(2.0);
}

pub(crate) fn muted(ui: &mut egui::Ui, text: impl Into<String>) -> egui::Response {
    let color = muted_color(ui);
    ui.label(egui::RichText::new(text.into()).size(12.0).color(color))
}

/// Подпись-«таблетка» с полупрозрачной подложкой.
pub(crate) fn badge(ui: &mut egui::Ui, text: &str, color: egui::Color32) {
    badge_with_fill(ui, text, color, 0.18);
}

fn badge_with_fill(ui: &mut egui::Ui, text: &str, color: egui::Color32, fill: f32) {
    let font = egui::FontId::proportional(12.0);
    let padding = egui::vec2(8.0, 3.0);
    let galley = ui.painter().layout_no_wrap(text.to_owned(), font, color);
    let size = galley.size() + padding * 2.0;
    let (rect, _) = ui.allocate_exact_size(size, egui::Sense::hover());
    ui.painter()
        .rect_filled(rect, egui::CornerRadius::same(8), color.gamma_multiply(fill));
    ui.painter().galley(rect.min + padding, galley, color);
}

/// Бейдж «сейчас»: подложка мягко дышит, чтобы идущую пару было видно
/// с одного взгляда, но текст не мигал.
pub(crate) fn pulse_badge(ui: &mut egui::Ui, text: &str, color: egui::Color32) {
    if !animations_on(ui.ctx()) {
        badge(ui, text, color);
        return;
    }
    let time = ui.ctx().input(|input| input.time) as f32;
    // Период 1.8 с — заметно, но не отвлекает.
    let phase = (time * std::f32::consts::TAU / 1.8).sin() * 0.5 + 0.5;
    badge_with_fill(ui, text, color, 0.12 + 0.20 * phase);
    ui.ctx()
        .request_repaint_after(std::time::Duration::from_millis(33));
}

/// Ключ флага анимаций в `Context`.
///
/// Флаг живёт в контексте, а не в `PulseApp`, потому что бейджи и полоски
/// рисуются свободными функциями, куда `self` не передаётся.
const ANIMATIONS_KEY: &str = "pulse-animations";

/// Включает или выключает анимации на этот кадр.
pub(crate) fn set_animations(ctx: &egui::Context, on: bool) {
    ctx.data_mut(|data| data.insert_temp(egui::Id::unique(ANIMATIONS_KEY), on));
}

/// Включены ли анимации; по умолчанию — да.
pub(crate) fn animations_on(ctx: &egui::Context) -> bool {
    ctx.data(|data| {
        data.get_temp::<bool>(egui::Id::unique(ANIMATIONS_KEY))
            .unwrap_or(true)
    })
}

/// Заглушка «данных пока нет» с подсказкой, что делать.
pub(crate) fn empty_state(
    ui: &mut egui::Ui,
    icon: &str,
    title: impl Into<String>,
    hint: impl Into<String>,
) {
    card(ui, |ui| {
        ui.vertical_centered(|ui| {
            ui.add_space(10.0);
            ui.label(egui::RichText::new(icon).size(40.0));
            ui.label(egui::RichText::new(title.into()).size(15.0).strong());
            ui.add_space(2.0);
            muted(ui, hint);
            ui.add_space(10.0);
        });
    });
}

/// Кружок с инициалами — аватар преподавателя.
pub(crate) fn avatar(ui: &mut egui::Ui, name: &str, rgb: u32) {
    let size = 36.0;
    let (rect, _) = ui.allocate_exact_size(egui::vec2(size, size), egui::Sense::hover());
    let painter = ui.painter();
    painter.circle_filled(rect.center(), size / 2.0, theme::color(rgb));
    let initials: String = name
        .split_whitespace()
        .filter_map(|part| part.chars().next())
        .take(2)
        .collect::<String>()
        .to_uppercase();
    painter.text(
        rect.center(),
        egui::Align2::CENTER_CENTER,
        if initials.is_empty() {
            "?".to_string()
        } else {
            initials
        },
        egui::FontId::proportional(14.0),
        egui::Color32::WHITE,
    );
}

/// Цвет оценки по 10-балльной шкале.
pub(crate) fn score_color(score: i32) -> u32 {
    match score {
        9..=10 => COLOR_EXCELLENT,
        7..=8 => COLOR_GOOD,
        4..=6 => COLOR_SATISFACTORY,
        1..=3 => COLOR_FAIL,
        _ => COLOR_NEUTRAL,
    }
}

/// Эмодзи и цвет предмета.
pub(crate) fn subject_visual(subject: &str) -> (&'static str, egui::Color32) {
    let theme = subject_theme::subject_theme(subject);
    (theme.emoji, theme::color(theme.color))
}

/// Оценка-«чип» с кнопкой удаления; возвращает `true`, если нажали «×».
pub(crate) fn score_chip(ui: &mut egui::Ui, score: i32, removable: bool) -> bool {
    let color = theme::color(score_color(score));
    let mut clicked = false;
    egui::Frame::new()
        .fill(color.gamma_multiply(0.18))
        .stroke(egui::Stroke::new(1.0, color.gamma_multiply(0.6)))
        .inner_margin(egui::Margin::symmetric(6, 1))
        .corner_radius(egui::CornerRadius::same(8))
        .show(ui, |ui| {
            ui.horizontal(|ui| {
                ui.label(
                    egui::RichText::new(score.to_string())
                        .color(color)
                        .strong()
                        .size(13.0),
                );
                if removable && ui.small_button("×").on_hover_text("Удалить оценку").clicked() {
                    clicked = true;
                }
            });
        });
    clicked
}

/// Полоска-индикатор на всю доступную ширину.
pub(crate) fn bar(ui: &mut egui::Ui, ratio: f32, color: egui::Color32, height: f32) {
    let width = ui.available_width().max(40.0);
    bar_width(ui, width, ratio, color, height);
}

/// Полоска заданной ширины — для строк, где рядом стоят подписи.
pub(crate) fn bar_width(
    ui: &mut egui::Ui,
    width: f32,
    ratio: f32,
    color: egui::Color32,
    height: f32,
) {
    let width = width.max(20.0);
    // Заполнение подкручивается плавно: первый вызов возвращает само значение,
    // поэтому полоска сразу нужной длины — скачков при открытии вкладки нет.
    let target = ratio.clamp(0.0, 1.0);
    let shown = if animations_on(ui.ctx()) {
        ui.ctx()
            .animate_value_with_time(ui.next_auto_id(), target, 0.35)
    } else {
        target
    };
    let (rect, _) = ui.allocate_exact_size(egui::vec2(width, height), egui::Sense::hover());
    let radius = egui::CornerRadius::same((height / 2.0) as u8);
    ui.painter()
        .rect_filled(rect, radius, border_color(ui).gamma_multiply(0.5));
    let filled = egui::Rect::from_min_size(
        rect.min,
        egui::vec2(width * shown, height),
    );
    ui.painter().rect_filled(filled, radius, color);
}

/// Строка «клавиша — значение» для метрик.
pub(crate) fn metric(ui: &mut egui::Ui, label: &str, value: impl Into<String>, color: egui::Color32) {
    ui.vertical(|ui| {
        muted(ui, label);
        ui.label(egui::RichText::new(value.into()).size(18.0).strong().color(color));
    });
}