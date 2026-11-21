//! Source workspace three-pane geometry, mirroring the Compose reference.
//!
//! Below the protected minimum, callers use one outer horizontal viewport;
//! shrinking the editor or rails below usable bounds is not an alternative.

pub(super) const WORKSPACES_DEFAULT: f32 = 360.;
pub(super) const FILES_DEFAULT: f32 = 320.;
pub(super) const WORKSPACES_MIN: f32 = 220.;
pub(super) const FILES_MIN: f32 = 220.;
pub(super) const SOURCE_MIN: f32 = 360.;
pub(super) const SPLITTER_WIDTH: f32 = 7.;
/// The gutter a splitter leaves on each side of its 1 px divider line. Panes
/// flanked by splitters get it for free; the workspace rail's left edge is the
/// shell divider, which has no gutter, so the rail adds it as a leading inset.
pub(super) const SPLITTER_HALO: f32 = (SPLITTER_WIDTH - 1.) / 2.;
pub(super) const MIN_CONTENT_WIDTH: f32 =
    WORKSPACES_MIN + FILES_MIN + SOURCE_MIN + SPLITTER_WIDTH * 2.;

#[derive(Debug, Clone, Copy, PartialEq)]
pub(super) struct PaneWidths {
    pub(super) workspaces: f32,
    pub(super) files: f32,
}

impl Default for PaneWidths {
    fn default() -> Self {
        Self {
            workspaces: WORKSPACES_DEFAULT,
            files: FILES_DEFAULT,
        }
    }
}

pub(super) fn minimum_content_width() -> f32 {
    MIN_CONTENT_WIDTH
}

pub(super) fn fit(widths: PaneWidths, available_width: f32) -> PaneWidths {
    let available_width = bounded_available(available_width);
    let side_budget = available_width - SOURCE_MIN - SPLITTER_WIDTH * 2.;
    let workspaces_max = (side_budget - FILES_MIN).max(WORKSPACES_MIN);
    let workspaces =
        finite_or(widths.workspaces, WORKSPACES_DEFAULT).clamp(WORKSPACES_MIN, workspaces_max);
    let files_max = (side_budget - workspaces).max(FILES_MIN);
    let files = finite_or(widths.files, FILES_DEFAULT).clamp(FILES_MIN, files_max);
    PaneWidths { workspaces, files }
}

pub(super) fn drag_workspaces(widths: PaneWidths, delta: f32, available_width: f32) -> PaneWidths {
    let widths = fit(widths, available_width);
    let maximum =
        (bounded_available(available_width) - widths.files - SOURCE_MIN - SPLITTER_WIDTH * 2.)
            .max(WORKSPACES_MIN);
    PaneWidths {
        workspaces: (widths.workspaces + finite_or(delta, 0.)).clamp(WORKSPACES_MIN, maximum),
        ..widths
    }
}

pub(super) fn drag_files(widths: PaneWidths, delta: f32, available_width: f32) -> PaneWidths {
    let widths = fit(widths, available_width);
    let maximum =
        (bounded_available(available_width) - widths.workspaces - SOURCE_MIN - SPLITTER_WIDTH * 2.)
            .max(FILES_MIN);
    PaneWidths {
        files: (widths.files + finite_or(delta, 0.)).clamp(FILES_MIN, maximum),
        ..widths
    }
}

fn finite_or(value: f32, fallback: f32) -> f32 {
    if value.is_finite() { value } else { fallback }
}

fn bounded_available(value: f32) -> f32 {
    finite_or(value, MIN_CONTENT_WIDTH).max(MIN_CONTENT_WIDTH)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wide_window_keeps_reference_defaults() {
        let widths = fit(PaneWidths::default(), 1280.);
        assert_eq!(
            widths,
            PaneWidths {
                workspaces: 360.,
                files: 320.
            }
        );
        assert_eq!(minimum_content_width(), 814.);
        assert_eq!(SPLITTER_WIDTH, 7.);
    }

    #[test]
    fn fitting_protects_the_editor_and_rail_minima() {
        let widths = fit(PaneWidths::default(), 1000.);
        assert_eq!(
            widths,
            PaneWidths {
                workspaces: 360.,
                files: 266.
            }
        );
        assert_eq!(
            1000. - widths.workspaces - widths.files - SPLITTER_WIDTH * 2.,
            SOURCE_MIN
        );
        assert_eq!(
            fit(widths, 814.),
            PaneWidths {
                workspaces: 220.,
                files: 220.
            }
        );
    }

    #[test]
    fn narrow_windows_use_the_minimum_scrollable_surface() {
        for available in [0., 100., 500., 813., -1.] {
            let widths = fit(PaneWidths::default(), available);
            assert_eq!(
                widths,
                PaneWidths {
                    workspaces: 220.,
                    files: 220.
                }
            );
            assert!(minimum_content_width() > available);
        }
    }

    #[test]
    fn workspace_drag_clamps_without_moving_file_rail() {
        let widths = PaneWidths::default();
        assert_eq!(
            drag_workspaces(widths, -1000., 1280.),
            PaneWidths {
                workspaces: 220.,
                files: 320.
            }
        );
        assert_eq!(
            drag_workspaces(widths, 1000., 1280.),
            PaneWidths {
                workspaces: 586.,
                files: 320.
            }
        );
    }

    #[test]
    fn file_drag_clamps_without_moving_workspace_rail() {
        let widths = PaneWidths::default();
        assert_eq!(
            drag_files(widths, -1000., 1280.),
            PaneWidths {
                workspaces: 360.,
                files: 220.
            }
        );
        assert_eq!(
            drag_files(widths, 1000., 1280.),
            PaneWidths {
                workspaces: 360.,
                files: 546.
            }
        );
    }

    #[test]
    fn invalid_float_geometry_never_produces_nan_or_panics() {
        let widths = fit(
            PaneWidths {
                workspaces: f32::NAN,
                files: f32::INFINITY,
            },
            f32::NAN,
        );
        assert_eq!(
            widths,
            PaneWidths {
                workspaces: 220.,
                files: 220.
            }
        );
        assert_eq!(drag_files(widths, f32::NAN, 1000.), widths);
        assert_eq!(drag_workspaces(widths, f32::NEG_INFINITY, 1000.), widths);
    }
}
