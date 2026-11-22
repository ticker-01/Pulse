//! Тема и шрифты: цвета взяты из Android-версии (`ui/theme/Theme.kt`).
//!
//! * тёмная: фон `#101014`, поверхность `#1C1C22`, акцент `#6C48E1`;
//! * светлая: фон `#FFFFFF`, поверхность `#F4F4F6`;
//! * приглушённый текст `#9090A0`.
//!
//! Шрифт: если в системе есть DejaVu/Segoe/Arial, он подключается первым —
//! так кириллица и эмодзи выглядят предсказуемо на любой ОС.

use eframe::egui;
use std::path::Path;
use std::sync::Arc;

pub const WINDOW_TITLE: &str = "МГКАП · Pulse";

pub const BG_DARK: u32 = 0x101014;
pub const SURFACE_DARK: u32 = 0x1C1C22;
pub const SURFACE_MUTED_DARK: u32 = 0x26262F;
pub const TEXT_DARK: u32 = 0xF2F2F5;

pub const BG_LIGHT: u32 = 0xFFFFFF;
pub const SURFACE_LIGHT: u32 = 0xF4F4F6;
pub const SURFACE_MUTED_LIGHT: u32 = 0xE6E6EB;
pub const TEXT_LIGHT: u32 = 0x1D1D1F;

pub const TEXT_MUTED: u32 = 0x9090A0;

/// Цвет с явной альфой: `rgb` в формате `0xRRGGBB`.
pub fn color(rgb: u32) -> egui::Color32 {
    egui::Color32::from_rgb(
        ((rgb >> 16) & 0xFF) as u8,
        ((rgb >> 8) & 0xFF) as u8,
        (rgb & 0xFF) as u8,
    )
}

/// Цвет поверхности карточек — зависит от темы.
pub fn surface(dark: bool) -> egui::Color32 {
    color(if dark { SURFACE_DARK } else { SURFACE_LIGHT })
}

pub fn muted(dark: bool) -> egui::Color32 {
    color(TEXT_MUTED).gamma_multiply(if dark { 1.0 } else { 0.85 })
}

pub fn text(dark: bool) -> egui::Color32 {
    color(if dark { TEXT_DARK } else { TEXT_LIGHT })
}

/// Применяет палитру и скругления к контексту egui.
pub fn apply(ctx: &egui::Context, dark: bool, accent: u32) {
    let mut visuals = if dark {
        egui::Visuals::dark()
    } else {
        egui::Visuals::light()
    };

    let accent = color(accent);
    let panel = color(if dark { BG_DARK } else { BG_LIGHT });
    let card = surface(dark);
    let border = color(if dark { SURFACE_MUTED_DARK } else { SURFACE_MUTED_LIGHT });

    visuals.panel_fill = panel;
    visuals.window_fill = card;
    visuals.extreme_bg_color = color(if dark { 0x0B0B0E } else { 0xFFFFFF });
    visuals.faint_bg_color = card;
    visuals.override_text_color = Some(text(dark));
    visuals.selection.bg_fill = accent.gamma_multiply(0.45);
    visuals.selection.stroke = egui::Stroke::new(1.0, accent);
    visuals.hyperlink_color = accent;

    visuals.widgets.noninteractive.bg_fill = card;
    visuals.widgets.noninteractive.bg_stroke = egui::Stroke::new(1.0, border);
    visuals.widgets.inactive.bg_fill = card;
    visuals.widgets.inactive.weak_bg_fill = card;
    visuals.widgets.inactive.bg_stroke = egui::Stroke::new(1.0, border);
    visuals.widgets.hovered.bg_fill = accent.gamma_multiply(0.16);
    visuals.widgets.hovered.weak_bg_fill = accent.gamma_multiply(0.16);
    visuals.widgets.hovered.bg_stroke = egui::Stroke::new(1.0, accent);
    visuals.widgets.active.bg_fill = accent.gamma_multiply(0.28);
    visuals.widgets.active.weak_bg_fill = accent.gamma_multiply(0.28);
    visuals.widgets.active.bg_stroke = egui::Stroke::new(1.0, accent);
    visuals.window_stroke = egui::Stroke::new(1.0, border);

    ctx.set_visuals(visuals);

    // В egui 0.37 стили живут отдельно для каждой темы, поэтому правим обе.
    ctx.all_styles_mut(|style| {
        style.spacing.item_spacing = egui::vec2(8.0, 8.0);
        style.spacing.button_padding = egui::vec2(12.0, 6.0);
    });
}

/// Системные шрифты, которые умеют кириллицу. Порядок — по приоритету.
const FONT_CANDIDATES: [&str; 10] = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/TTF/DejaVuSans.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
    "/usr/share/fonts/truetype/pt-sans/PTS55F.ttf",
    "C:/Windows/Fonts/segoeui.ttf",
    "C:/Windows/Fonts/arial.ttf",
    "C:/Windows/Fonts/tahoma.ttf",
    "/System/Library/Fonts/Supplemental/Arial.ttf",
    "/Library/Fonts/Arial.ttf",
];

/// Подключает первый найденный системный шрифт как основной.
pub fn install_fonts(ctx: &egui::Context) {
    let Some(path) = FONT_CANDIDATES.iter().find(|path| Path::new(path).exists()) else {
        return;
    };
    let Ok(bytes) = std::fs::read(path) else {
        return;
    };

    let mut fonts = egui::FontDefinitions::default();
    let name = "pulse-system".to_string();
    fonts
        .font_data
        .insert(name.clone(), Arc::new(egui::FontData::from_owned(bytes)));

    for family in [egui::FontFamily::Proportional, egui::FontFamily::Monospace] {
        let entry = fonts.families.entry(family).or_default();
        entry.insert(0, name.clone());
    }

    ctx.set_fonts(fonts);
}