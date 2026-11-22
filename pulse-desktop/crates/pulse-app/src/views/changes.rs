//! Блок «Замены и переносы» на вкладке «Расписание».
//!
//! Сайт колледжа не публикует отдельный файл замен: на странице «Замены в
//! расписании» лежит новый PDF расписания, где замены уже внесены. Поэтому
//! список изменений — это результат сравнения прошлой и новой версии
//! (`pulse_core::changes`), а не разбор пометок в PDF.

use eframe::egui;
use pulse_core::changes::{ChangeKind, ScheduleChange};
use pulse_core::datetime;
use pulse_core::performance::{COLOR_FAIL, COLOR_GOOD, COLOR_ORANGE, COLOR_SATISFACTORY};

use crate::app::PulseApp;
use crate::theme;
use crate::views::{badge, card, muted};

/// Сколько строк показываем до «показать все».
const SHOWN: usize = 5;

impl PulseApp {
    /// Карточка с последними изменениями; пустая — не рисуется вовсе.
    pub(crate) fn ui_changes_card(&mut self, ui: &mut egui::Ui) {
        if self.changes.is_empty() {
            return;
        }
        let accent = self.settings.accent;
        let total = self.changes.len();
        let changes = &self.changes;
        let mut hide = false;

        card(ui, |ui| {
            ui.horizontal(|ui| {
                ui.label(egui::RichText::new("Замены и переносы").size(15.0).strong());
                badge(ui, &format!("{total}"), theme::color(accent));
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui
                        .small_button("✕")
                        .on_hover_text("Скрыть до следующего обновления")
                        .clicked()
                    {
                        hide = true;
                    }
                });
            });
            muted(
                ui,
                "Сравнение прошлой и новой версии расписания с сайта колледжа.",
            );
            ui.add_space(4.0);

            if total > SHOWN {
                muted(ui, format!("Показаны первые {SHOWN} из {total}."));
            }
            for change in changes.iter().take(SHOWN) {
                change_row(ui, change);
            }
        });
        ui.add_space(8.0);

        if hide {
            self.clear_changes();
        }
    }

    /// Убирает список изменений с экрана и с диска.
    pub(crate) fn clear_changes(&mut self) {
        self.changes.clear();
        if let Some(error) = self.store.save_changes(&self.changes) {
            self.set_status(crate::app::StatusKind::Error, error);
        }
    }
}

fn color_of(kind: ChangeKind) -> u32 {
    match kind {
        ChangeKind::Replaced => COLOR_ORANGE,
        ChangeKind::Moved => COLOR_SATISFACTORY,
        ChangeKind::Removed => COLOR_FAIL,
        ChangeKind::Added => COLOR_GOOD,
    }
}

fn change_row(ui: &mut egui::Ui, change: &ScheduleChange) {
    let color = theme::color(color_of(change.kind));
    ui.horizontal_wrapped(|ui| {
        ui.label(
            egui::RichText::new(datetime::format_short_date(change.date))
                .size(12.0)
                .monospace(),
        );
        badge(ui, change.kind.label(), color);
        ui.label(egui::RichText::new(format!("{} пара", change.pair_number)).size(12.0));
        if !change.group.is_empty() {
            muted(ui, format!("гр. {}", change.group));
        }
    });
    muted(ui, change.describe());
    ui.add_space(2.0);
}
