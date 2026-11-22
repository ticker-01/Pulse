//! Десктопный клиент МГКАП (Pulse) для Windows, Linux и macOS.
//!
//! Ядро (разбор расписания, звонки, аналитика) живёт в крейте `pulse-core`
//! и покрыто тестами; здесь — только интерфейс на `egui`/`eframe`.

mod app;
mod store;
mod theme;
mod views;

use app::PulseApp;

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1200.0, 800.0])
            .with_min_inner_size([960.0, 620.0])
            .with_title(theme::WINDOW_TITLE),
        ..Default::default()
    };

    eframe::run_native(
        theme::WINDOW_TITLE,
        options,
        Box::new(|cc| Ok(Box::new(PulseApp::new(cc)))),
    )
}