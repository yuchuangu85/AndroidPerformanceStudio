use super::*;
use crate::{FrameIdentity, parse_snapshot};
use aps_contracts::SourceDigest;
use serde_json::{Value, json};
use std::collections::BTreeSet;
use std::io::{Cursor, Write};
use zip::{CompressionMethod, ZipWriter, write::SimpleFileOptions};

const PACKAGE: &str = "com.codemx.anrdemo";
const MAIN_ENTRY: &str = "42177c9 com.codemx.anrdemo/com.codemx.anrdemo.MainActivity";

// ---------------------------------------------------------------------------
// Encoded-hierarchy fixture (port of Kotlin EncodedHierarchyFixture)
// ---------------------------------------------------------------------------

enum Fv {
    Bool(bool),
    Int(i32),
    Short(i16),
    Float(f32),
    Text(&'static str),
}

struct FixtureEncoder {
    output: Vec<u8>,
    property_ids: Vec<(String, i16)>,
}

impl FixtureEncoder {
    fn new() -> Self {
        Self {
            output: Vec::new(),
            property_ids: Vec::new(),
        }
    }

    fn property(&mut self, name: &str, value: Fv) {
        let id = self.property_id(name);
        self.write_short(id);
        self.write_value(value);
    }

    fn map(&mut self, block: impl FnOnce(&mut Self)) {
        self.output.push(b'M');
        block(self);
        self.write_short(0);
    }

    fn nested_map(&mut self, name: &str, block: impl FnOnce(&mut Self)) {
        let id = self.property_id(name);
        self.write_short(id);
        self.map(block);
    }

    fn write_property_index(&mut self) {
        let name_key = self.property_id("__name__");
        self.output.push(b'M');
        self.write_short(name_key);
        self.write_string("propertyIndex");
        let entries = self.property_ids.clone();
        for (name, id) in entries {
            self.write_short(id);
            self.write_string(&name);
        }
        self.write_short(0);
    }

    fn finish(self) -> Vec<u8> {
        self.output
    }

    fn property_id(&mut self, name: &str) -> i16 {
        if let Some((_, id)) = self
            .property_ids
            .iter()
            .find(|(existing, _)| existing == name)
        {
            return *id;
        }
        let id = (self.property_ids.len() + 1) as i16;
        self.property_ids.push((name.to_owned(), id));
        id
    }

    fn write_value(&mut self, value: Fv) {
        match value {
            Fv::Bool(value) => {
                self.output.push(b'Z');
                self.output.push(u8::from(value));
            }
            Fv::Int(value) => {
                self.output.push(b'I');
                self.output.extend_from_slice(&value.to_be_bytes());
            }
            Fv::Short(value) => self.write_short(value),
            Fv::Float(value) => {
                self.output.push(b'F');
                self.output.extend_from_slice(&value.to_be_bytes());
            }
            Fv::Text(value) => self.write_string(value),
        }
    }

    fn write_short(&mut self, value: i16) {
        self.output.push(b'S');
        self.output.extend_from_slice(&value.to_be_bytes());
    }

    fn write_string(&mut self, value: &str) {
        let bytes = value.as_bytes();
        self.output.push(b'R');
        self.output
            .extend_from_slice(&(bytes.len() as u16).to_be_bytes());
        self.output.extend_from_slice(bytes);
    }
}

#[derive(Clone, Copy)]
struct Options {
    window_left: i32,
    window_top: i32,
    root_class: &'static str,
    child_class: &'static str,
    visibility: i32,
    root_scroll: (i32, i32),
    child_translation: (f32, f32),
    child: bool,
}

impl Default for Options {
    fn default() -> Self {
        Self {
            window_left: 10,
            window_top: 20,
            root_class: "com.codemx.ui.RealRootLayout",
            child_class: "com.codemx.ui.RealTitleView",
            visibility: 0,
            root_scroll: (0, 0),
            child_translation: (0.0, 0.0),
            child: true,
        }
    }
}

fn hierarchy() -> Vec<u8> {
    hierarchy_with(&Options::default())
}

fn hierarchy_with(options: &Options) -> Vec<u8> {
    let mut encoder = FixtureEncoder::new();
    encoder.property("window:left", Fv::Int(options.window_left));
    encoder.property("window:top", Fv::Int(options.window_top));
    encoder.map(|encoder| {
        encoder.property("meta:__name__", Fv::Text(options.root_class));
        encoder.property("id", Fv::Text("NO_ID"));
        encoder.property("layout:left", Fv::Int(0));
        encoder.property("layout:top", Fv::Int(0));
        encoder.property("layout:right", Fv::Int(1080));
        encoder.property("layout:bottom", Fv::Int(2400));
        encoder.property("scrolling:scrollX", Fv::Int(options.root_scroll.0));
        encoder.property("scrolling:scrollY", Fv::Int(options.root_scroll.1));
        encoder.property("drawing:translationX", Fv::Float(0.0));
        encoder.property("drawing:translationY", Fv::Float(0.0));
        encoder.property("drawing:translationZ", Fv::Float(2.0));
        encoder.property("drawing:elevation", Fv::Float(8.0));
        encoder.property("drawing:rotation", Fv::Float(5.0));
        encoder.property("drawing:rotationX", Fv::Float(1.0));
        encoder.property("drawing:rotationY", Fv::Float(2.0));
        encoder.property("drawing:scaleX", Fv::Float(1.0));
        encoder.property("drawing:scaleY", Fv::Float(1.0));
        encoder.property("drawing:pivotX", Fv::Float(540.0));
        encoder.property("drawing:pivotY", Fv::Float(1200.0));
        encoder.property("drawing:alpha", Fv::Float(1.0));
        encoder.property("drawing:clipBounds", Fv::Text("Rect(0, 0 - 1080, 2300)"));
        encoder.property("drawing:opaque", Fv::Bool(false));
        encoder.property("drawing:willNotDraw", Fv::Bool(false));
        encoder.property("drawing:hardwareAccelerated", Fv::Bool(true));
        encoder.property("drawing:layerType", Fv::Int(2));
        encoder.property("misc:visibility", Fv::Int(options.visibility));
        encoder.property("misc:enabled", Fv::Bool(true));
        encoder.property("misc:clickable", Fv::Bool(true));
        encoder.property("misc:longClickable", Fv::Bool(true));
        encoder.property("misc:selected", Fv::Bool(false));
        encoder.property(
            "accessibility:getContentDescription()",
            Fv::Text("Root container"),
        );
        encoder.property("focus:isFocusable", Fv::Bool(true));
        encoder.property("focus:isFocused", Fv::Bool(false));
        encoder.property("padding:paddingLeft", Fv::Int(16));
        encoder.property("padding:paddingTop", Fv::Int(24));
        encoder.property("padding:paddingRight", Fv::Int(16));
        encoder.property("padding:paddingBottom", Fv::Int(24));
        encoder.property("measurement:minWidth", Fv::Int(0));
        encoder.property("measurement:minHeight", Fv::Int(0));
        encoder.property("measurement:measuredWidth", Fv::Int(1080));
        encoder.property("measurement:measuredHeight", Fv::Int(2400));
        encoder.property("drawing:clipChildren", Fv::Bool(true));
        encoder.property("drawing:clipToPadding", Fv::Bool(false));
        encoder.nested_map("layoutParams", |encoder| {
            encoder.property("class", Fv::Text("android.widget.FrameLayout.LayoutParams"));
            encoder.property("width", Fv::Int(-1));
            encoder.property("height", Fv::Int(-2));
            encoder.property("leftMargin", Fv::Int(8));
            encoder.property("topMargin", Fv::Int(12));
            encoder.property("rightMargin", Fv::Int(8));
            encoder.property("bottomMargin", Fv::Int(12));
        });
        encoder.property(
            "meta:__childCount__",
            Fv::Short(if options.child { 1 } else { 0 }),
        );
        if options.child {
            encoder.nested_map("meta:__child__0", |encoder| {
                encoder.property("meta:__name__", Fv::Text(options.child_class));
                encoder.property("id", Fv::Text("com.codemx.anrdemo:id/title"));
                encoder.property("text:mText", Fv::Text("Title"));
                encoder.property("layout:left", Fv::Int(40));
                encoder.property("layout:top", Fv::Int(80));
                encoder.property("layout:right", Fv::Int(600));
                encoder.property("layout:bottom", Fv::Int(160));
                encoder.property("scrolling:scrollX", Fv::Int(0));
                encoder.property("scrolling:scrollY", Fv::Int(0));
                encoder.property(
                    "drawing:translationX",
                    Fv::Float(options.child_translation.0),
                );
                encoder.property(
                    "drawing:translationY",
                    Fv::Float(options.child_translation.1),
                );
                encoder.property("drawing:alpha", Fv::Float(1.0));
                encoder.property("misc:visibility", Fv::Int(0));
                encoder.property("meta:__childCount__", Fv::Short(0));
            });
        }
    });
    encoder.write_property_index();
    encoder.finish()
}

fn deep_chain(levels: usize) -> Vec<u8> {
    let mut encoder = FixtureEncoder::new();
    encoder.property("window:left", Fv::Int(0));
    encoder.property("window:top", Fv::Int(0));
    encoder.map(|encoder| write_chain(encoder, levels));
    encoder.write_property_index();
    encoder.finish()
}

fn write_chain(encoder: &mut FixtureEncoder, remaining: usize) {
    encoder.property("meta:__name__", Fv::Text("android.view.View"));
    encoder.property("misc:visibility", Fv::Int(0));
    encoder.property("layout:left", Fv::Int(0));
    encoder.property("layout:top", Fv::Int(0));
    encoder.property("layout:right", Fv::Int(100));
    encoder.property("layout:bottom", Fv::Int(100));
    encoder.property(
        "meta:__childCount__",
        Fv::Short(if remaining > 0 { 1 } else { 0 }),
    );
    if remaining > 0 {
        encoder.nested_map("meta:__child__0", |encoder| {
            write_chain(encoder, remaining - 1)
        });
    }
}

fn child_order_chain() -> Vec<u8> {
    let mut encoder = FixtureEncoder::new();
    encoder.property("window:left", Fv::Int(0));
    encoder.property("window:top", Fv::Int(0));
    encoder.map(|encoder| {
        encoder.property("meta:__name__", Fv::Text("android.widget.FrameLayout"));
        encoder.property("layout:left", Fv::Int(0));
        encoder.property("layout:top", Fv::Int(0));
        encoder.property("layout:right", Fv::Int(100));
        encoder.property("layout:bottom", Fv::Int(100));
        // Insert child 10 before child 2: numeric ordering must win over
        // lexicographic map-name ordering.
        encoder.nested_map("meta:__child__10", |encoder| {
            encoder.property("meta:__name__", Fv::Text("C10"));
        });
        encoder.nested_map("meta:__child__2", |encoder| {
            encoder.property("meta:__name__", Fv::Text("C2"));
        });
    });
    encoder.write_property_index();
    encoder.finish()
}

fn zip_bytes(entries: &[(&str, &[u8])]) -> Vec<u8> {
    let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
    for (name, payload) in entries {
        writer
            .start_file(*name, options)
            .expect("start fixture file");
        writer.write_all(payload).expect("write fixture file");
    }
    writer.finish().expect("finish fixture zip").into_inner()
}

fn fixture_zip() -> Vec<u8> {
    let payload = hierarchy();
    zip_bytes(&[(MAIN_ENTRY, payload.as_slice())])
}

fn two_app_windows_zip() -> Vec<u8> {
    let first = hierarchy();
    let second = hierarchy();
    let other = hierarchy();
    zip_bytes(&[
        (MAIN_ENTRY, first.as_slice()),
        (
            "51aa71 com.codemx.anrdemo/com.codemx.anrdemo.ConfirmDialog",
            second.as_slice(),
        ),
        ("9911 other.app/other.app.MainActivity", other.as_slice()),
    ])
}

fn system_ui_zip() -> Vec<u8> {
    let status_bar = hierarchy();
    let navigation_bar = hierarchy();
    let app = hierarchy();
    zip_bytes(&[
        ("a3db2af StatusBar", status_bar.as_slice()),
        ("b473c9d NavigationBar", navigation_bar.as_slice()),
        (MAIN_ENTRY, app.as_slice()),
    ])
}

fn system_ui_launcher_taskbar_zip() -> Vec<u8> {
    let status_bar = hierarchy();
    let taskbar = hierarchy();
    let app = hierarchy();
    zip_bytes(&[
        ("a3db2af StatusBar", status_bar.as_slice()),
        (
            "b473c9d com.google.android.apps.nexuslauncher/com.android.launcher3.taskbar.Taskbar",
            taskbar.as_slice(),
        ),
        (MAIN_ENTRY, app.as_slice()),
    ])
}

fn system_ui_bare_taskbar_zip() -> Vec<u8> {
    let status_bar = hierarchy();
    let taskbar = hierarchy();
    zip_bytes(&[
        ("a3db2af StatusBar", status_bar.as_slice()),
        ("b473c9d Taskbar", taskbar.as_slice()),
    ])
}

fn parse(zip_bytes: &[u8], package: &str, target: WindowTarget) -> Vec<WindowDump> {
    parse_windows(zip_bytes, package, target, &CancellationToken::new()).expect("decoded windows")
}

fn attr<'a>(node: &'a DumpNode, key: &str) -> &'a Value {
    node.attributes
        .get(key)
        .unwrap_or_else(|| panic!("missing attribute {key}"))
}

fn int_field(value: &Value, key: &str) -> i64 {
    value
        .get(key)
        .and_then(Value::as_i64)
        .unwrap_or_else(|| panic!("missing integer field {key}"))
}

fn max_depth(node: &DumpNode) -> usize {
    node.children
        .iter()
        .map(|child| 1 + max_depth(child))
        .max()
        .unwrap_or(0)
}

fn collect_json_ids(value: &Value, ids: &mut BTreeSet<String>) {
    ids.insert(
        value
            .get("id")
            .and_then(Value::as_str)
            .expect("node id")
            .to_owned(),
    );
    if let Some(children) = value.get("children").and_then(Value::as_array) {
        for child in children {
            collect_json_ids(child, ids);
        }
    }
}

fn dump_node(
    class: &str,
    bounds: (i32, i32, i32, i32),
    resource: Option<&str>,
    attributes: Value,
    children: Vec<DumpNode>,
) -> DumpNode {
    DumpNode {
        class_name: class.into(),
        bounds,
        visible: true,
        alpha: 1.0,
        resource_name: resource.map(str::to_owned),
        text: None,
        attributes,
        children,
    }
}

// ---------------------------------------------------------------------------
// Wire-format decoding and protocol attribute projection
// ---------------------------------------------------------------------------

#[test]
fn decodes_fixture_into_protocol_attributes() {
    let windows = parse(&fixture_zip(), PACKAGE, WindowTarget::ForegroundApp);
    assert_eq!(windows.len(), 1);
    let window = &windows[0];
    assert_eq!(window.id, "window:42177c9");
    assert_eq!(window.title, "MainActivity");
    assert_eq!(window.kind, "ACTIVITY");

    let root = &window.root;
    assert_eq!(root.class_name, "com.codemx.ui.RealRootLayout");
    assert_eq!(root.bounds, (10, 20, 1090, 2420));
    assert!(root.visible);
    assert_eq!(root.alpha, 1.0);
    assert_eq!(root.resource_name, None);
    assert_eq!(attr(root, "visibility").as_str(), Some("VISIBLE"));
    let layout_bounds = attr(root, "layoutBounds");
    assert_eq!(int_field(layout_bounds, "left"), 0);
    assert_eq!(int_field(layout_bounds, "top"), 0);
    assert_eq!(int_field(layout_bounds, "right"), 1080);
    assert_eq!(int_field(layout_bounds, "bottom"), 2400);
    assert_eq!(attr(root, "elevation").as_f64(), Some(8.0));
    assert_eq!(attr(root, "z").as_f64(), Some(10.0));
    let padding = attr(root, "padding");
    assert_eq!(int_field(padding, "left"), 16);
    assert_eq!(int_field(padding, "top"), 24);
    assert_eq!(int_field(padding, "right"), 16);
    assert_eq!(int_field(padding, "bottom"), 24);
    let margin = attr(root, "margin");
    assert_eq!(int_field(margin, "left"), 8);
    assert_eq!(int_field(margin, "top"), 12);
    assert_eq!(int_field(margin, "right"), 8);
    assert_eq!(int_field(margin, "bottom"), 12);
    assert_eq!(int_field(&root.attributes, "layoutWidth"), -1);
    assert_eq!(int_field(&root.attributes, "layoutHeight"), -2);
    assert_eq!(
        attr(root, "layoutParamsClass").as_str(),
        Some("android.widget.FrameLayout.LayoutParams")
    );
    let clip_bounds = attr(root, "clipBounds");
    assert_eq!(int_field(clip_bounds, "left"), 0);
    assert_eq!(int_field(clip_bounds, "top"), 0);
    assert_eq!(int_field(clip_bounds, "right"), 1080);
    assert_eq!(int_field(clip_bounds, "bottom"), 2300);
    assert_eq!(attr(root, "clipChildren").as_bool(), Some(true));
    assert_eq!(attr(root, "clipToPadding").as_bool(), Some(false));
    assert_eq!(attr(root, "layerType").as_str(), Some("HARDWARE"));
    assert_eq!(attr(root, "hardwareAccelerated").as_bool(), Some(true));
    assert_eq!(attr(root, "clickable").as_bool(), Some(true));
    assert_eq!(attr(root, "longClickable").as_bool(), Some(true));
    assert_eq!(
        attr(root, "contentDescription").as_str(),
        Some("Root container")
    );
    let raw = attr(root, "rawProperties").as_object().unwrap();
    assert_eq!(raw.get("layout:left").and_then(Value::as_str), Some("0"));
    assert_eq!(
        raw.get("layout:right").and_then(Value::as_str),
        Some("1080")
    );
    assert_eq!(
        raw.get("drawing:elevation").and_then(Value::as_str),
        Some("8.0")
    );
    assert_eq!(
        raw.get("layoutParams:class").and_then(Value::as_str),
        Some("android.widget.FrameLayout.LayoutParams")
    );
    assert_eq!(
        raw.get("layoutParams:width").and_then(Value::as_str),
        Some("-1")
    );
    assert_eq!(
        raw.get("layoutParams:leftMargin").and_then(Value::as_str),
        Some("8")
    );
    assert!(!raw.keys().any(|key| key.starts_with("meta:")));

    let child = root.children.first().expect("child node");
    assert_eq!(child.class_name, "com.codemx.ui.RealTitleView");
    assert_eq!(child.bounds, (50, 100, 610, 180));
    assert_eq!(
        child.resource_name.as_deref(),
        Some("com.codemx.anrdemo:id/title")
    );
    assert_eq!(child.text.as_deref(), Some("Title"));
    let child_bounds = attr(child, "layoutBounds");
    assert_eq!(int_field(child_bounds, "left"), 40);
    assert_eq!(int_field(child_bounds, "top"), 80);
    assert_eq!(int_field(child_bounds, "right"), 600);
    assert_eq!(int_field(child_bounds, "bottom"), 160);
}

#[test]
fn absolute_bounds_compose_parent_scroll_and_translation() {
    let options = Options {
        root_scroll: (30, 40),
        child_translation: (2.4, -1.5),
        ..Default::default()
    };
    let payload = hierarchy_with(&options);
    let zip = zip_bytes(&[(MAIN_ENTRY, payload.as_slice())]);
    let windows = parse(&zip, PACKAGE, WindowTarget::ForegroundApp);
    let child = windows[0].root.children.first().expect("child node");
    // left = window 10 - scroll 30 + layout 40 + round(2.4) 2 = 22
    // top  = window 20 - scroll 40 + layout 80 + round(-1.5) -1 = 59
    assert_eq!(child.bounds, (22, 59, 582, 139));
}

#[test]
fn visibility_labels_and_parent_visibility_are_projected() {
    for (visibility, label, expected_visible) in [
        (0, "VISIBLE", true),
        (4, "INVISIBLE", false),
        (8, "GONE", false),
        (3, "UNKNOWN(3)", false),
    ] {
        let options = Options {
            visibility,
            ..Default::default()
        };
        let payload = hierarchy_with(&options);
        let zip = zip_bytes(&[(MAIN_ENTRY, payload.as_slice())]);
        let windows = parse(&zip, PACKAGE, WindowTarget::ForegroundApp);
        let root = &windows[0].root;
        assert_eq!(attr(root, "visibility").as_str(), Some(label));
        assert_eq!(root.visible, expected_visible, "visibility {visibility}");
        assert_eq!(root.children[0].visible, expected_visible);
    }
}

#[test]
fn children_are_sorted_by_encoded_child_index() {
    let payload = child_order_chain();
    let zip = zip_bytes(&[(MAIN_ENTRY, payload.as_slice())]);
    let windows = parse(&zip, PACKAGE, WindowTarget::ForegroundApp);
    let classes: Vec<&str> = windows[0]
        .root
        .children
        .iter()
        .map(|child| child.class_name.as_str())
        .collect();
    assert_eq!(classes, ["C2", "C10"]);
}

// ---------------------------------------------------------------------------
// Window selection / identity
// ---------------------------------------------------------------------------

#[test]
fn filters_windows_by_package_and_system_ui_titles() {
    let app_windows = parse(&two_app_windows_zip(), PACKAGE, WindowTarget::ForegroundApp);
    assert_eq!(
        app_windows
            .iter()
            .map(|window| window.title.as_str())
            .collect::<Vec<_>>(),
        ["MainActivity", "ConfirmDialog"]
    );

    let system_ui = parse(&system_ui_zip(), SYSTEM_UI_PACKAGE, WindowTarget::SystemUi);
    assert_eq!(
        system_ui
            .iter()
            .map(|window| window.title.as_str())
            .collect::<Vec<_>>(),
        ["StatusBar", "NavigationBar"]
    );

    let launcher_taskbar = parse(
        &system_ui_launcher_taskbar_zip(),
        SYSTEM_UI_PACKAGE,
        WindowTarget::SystemUi,
    );
    assert_eq!(
        launcher_taskbar
            .iter()
            .map(|window| window.title.as_str())
            .collect::<Vec<_>>(),
        ["StatusBar", "Taskbar"]
    );

    let bare_taskbar = parse(
        &system_ui_bare_taskbar_zip(),
        SYSTEM_UI_PACKAGE,
        WindowTarget::SystemUi,
    );
    assert_eq!(
        bare_taskbar
            .iter()
            .map(|window| window.title.as_str())
            .collect::<Vec<_>>(),
        ["StatusBar", "Taskbar"]
    );

    // A foreground package search must not pick up System UI title windows.
    let app = parse(&system_ui_zip(), PACKAGE, WindowTarget::ForegroundApp);
    assert_eq!(app.len(), 1);
    assert_eq!(app[0].title, "MainActivity");
}

#[test]
fn window_identity_title_and_type_follow_kotlin_rules() {
    let windows = parse(&two_app_windows_zip(), PACKAGE, WindowTarget::ForegroundApp);
    assert_eq!(windows[0].id, "window:42177c9");
    assert_eq!(windows[0].kind, "ACTIVITY");
    assert_eq!(windows[1].id, "window:51aa71");
    assert_eq!(windows[1].title, "ConfirmDialog");
    assert_eq!(windows[1].kind, "DIALOG");

    let popup = hierarchy();
    let dialog = hierarchy();
    let activity = hierarchy();
    let other = hierarchy();
    let zip = zip_bytes(&[
        (
            "aaa111 com.codemx.anrdemo/com.codemx.anrdemo.PopupWindow",
            popup.as_slice(),
        ),
        (
            "bbb222 com.codemx.anrdemo/com.codemx.anrdemo.ConfirmDialog",
            dialog.as_slice(),
        ),
        (
            "ccc333 com.codemx.anrdemo/com.codemx.anrdemo.MainActivity",
            activity.as_slice(),
        ),
        (
            "ddd444 com.codemx.anrdemo/com.codemx.anrdemo.Thing",
            other.as_slice(),
        ),
    ]);
    let windows = parse(&zip, PACKAGE, WindowTarget::ForegroundApp);
    assert_eq!(
        windows
            .iter()
            .map(|window| (window.id.as_str(), window.kind))
            .collect::<Vec<_>>(),
        [
            ("window:aaa111", "POPUP"),
            ("window:bbb222", "DIALOG"),
            ("window:ccc333", "ACTIVITY"),
            ("window:ddd444", "OTHER"),
        ]
    );
    assert_eq!(windows[0].title, "PopupWindow");
    assert_eq!(windows[1].title, "ConfirmDialog");
    assert_eq!(windows[2].title, "MainActivity");
    assert_eq!(windows[3].title, "Thing");

    // Sanitized token and hash fallback for a blank token.
    let payload = hierarchy();
    let sanitized = zip_bytes(&[(
        "a@b#c com.codemx.anrdemo/com.codemx.anrdemo.MainActivity",
        payload.as_slice(),
    )]);
    assert_eq!(
        parse(&sanitized, PACKAGE, WindowTarget::ForegroundApp)[0].id,
        "window:a_b_c"
    );
    let blank_token = zip_bytes(&[(
        " com.codemx.anrdemo/com.codemx.anrdemo.MainActivity",
        payload.as_slice(),
    )]);
    let expected = format!(
        "window:{:x}",
        java_hash_code(" com.codemx.anrdemo/com.codemx.anrdemo.MainActivity") as u32
    );
    assert_eq!(
        parse(&blank_token, PACKAGE, WindowTarget::ForegroundApp)[0].id,
        expected
    );
}

#[test]
fn default_window_is_largest_and_first_on_ties() {
    let small_option = Options {
        child: false,
        ..Options::default()
    };
    let small = hierarchy_with(&small_option);
    let large = hierarchy();
    let zip = zip_bytes(&[
        (MAIN_ENTRY, small.as_slice()),
        (
            "51aa71 com.codemx.anrdemo/com.codemx.anrdemo.ConfirmDialog",
            large.as_slice(),
        ),
    ]);
    let windows = parse(&zip, PACKAGE, WindowTarget::ForegroundApp);
    assert_eq!(node_count(&windows), 3);
    assert_eq!(
        default_window_id(&windows).as_deref(),
        Some("window:51aa71")
    );

    let tie_zip = zip_bytes(&[
        (MAIN_ENTRY, small.as_slice()),
        (
            "51aa71 com.codemx.anrdemo/com.codemx.anrdemo.ConfirmDialog",
            small.as_slice(),
        ),
    ]);
    let tied = parse(&tie_zip, PACKAGE, WindowTarget::ForegroundApp);
    assert_eq!(default_window_id(&tied).as_deref(), Some("window:42177c9"));
    assert_eq!(default_window_id(&[]), None);
}

#[test]
fn compose_host_detection_covers_nested_classes() {
    assert!(!contains_compose_host(&parse(
        &fixture_zip(),
        PACKAGE,
        WindowTarget::ForegroundApp
    )));
    let options = Options {
        child_class: "androidx.compose.ui.platform.AndroidComposeView",
        ..Default::default()
    };
    let payload = hierarchy_with(&options);
    let zip = zip_bytes(&[(MAIN_ENTRY, payload.as_slice())]);
    assert!(contains_compose_host(&parse(
        &zip,
        PACKAGE,
        WindowTarget::ForegroundApp
    )));
}

// ---------------------------------------------------------------------------
// Snapshot JSON projection
// ---------------------------------------------------------------------------

#[test]
fn snapshot_windows_emit_unique_namespaced_protocol_nodes() {
    let windows = parse(&two_app_windows_zip(), PACKAGE, WindowTarget::ForegroundApp);
    let snapshots = snapshot_windows(&windows);
    assert_eq!(snapshots.len(), 2);

    let first = &snapshots[0];
    assert_eq!(
        first.get("id").and_then(Value::as_str),
        Some("window:42177c9")
    );
    assert_eq!(
        first.get("title").and_then(Value::as_str),
        Some("MainActivity")
    );
    assert_eq!(first.get("type").and_then(Value::as_str), Some("ACTIVITY"));
    let bounds = first.get("bounds").expect("window bounds");
    assert_eq!(int_field(bounds, "left"), 10);
    assert_eq!(int_field(bounds, "top"), 20);
    assert_eq!(int_field(bounds, "right"), 1090);
    assert_eq!(int_field(bounds, "bottom"), 2420);
    let root = first.get("root").expect("window root");
    assert_eq!(
        root.get("id").and_then(Value::as_str),
        Some("window:42177c9/root")
    );
    assert_eq!(root.get("type").and_then(Value::as_str), Some("view"));
    assert_eq!(
        root.get("className").and_then(Value::as_str),
        Some("com.codemx.ui.RealRootLayout")
    );
    assert_eq!(root.get("visible").and_then(Value::as_bool), Some(true));
    let alpha = root
        .get("alpha")
        .and_then(Value::as_f64)
        .expect("alpha number");
    assert!((0.0..=1.0).contains(&alpha));
    let children = root
        .get("children")
        .and_then(Value::as_array)
        .expect("children array");
    assert_eq!(
        children[0].get("id").and_then(Value::as_str),
        Some("window:42177c9/root/0")
    );
    assert_eq!(
        children[0].get("resourceName").and_then(Value::as_str),
        Some("com.codemx.anrdemo:id/title")
    );
    assert_eq!(
        children[0].get("text").and_then(Value::as_str),
        Some("Title")
    );
    assert!(children[0].get("attributes").is_some());
    serde_json::to_string(&snapshots).expect("snapshot JSON is serializable");

    let mut ids = BTreeSet::new();
    for snapshot in &snapshots {
        collect_json_ids(snapshot.get("root").expect("root"), &mut ids);
    }
    assert_eq!(ids.len(), node_count(&windows));
}

#[test]
fn snapshot_windows_ingest_into_the_layout_archive() {
    let windows = parse(&two_app_windows_zip(), PACKAGE, WindowTarget::ForegroundApp);
    let snapshots = snapshot_windows(&windows);
    let root = snapshots[0].get("root").expect("root").clone();
    let snapshot = json!({
        "protocolVersion": {"major": 1, "minor": 0},
        "packageName": PACKAGE,
        "capturedAtEpochMillis": 42,
        "display": {"widthPx": 1080, "heightPx": 2400, "density": 3.0},
        "capabilities": {
            "viewHierarchy": true,
            "composeSemantics": false,
            "screenshots": false,
            "timeline": false
        },
        "root": root,
        "windows": snapshots,
    });
    let frame = FrameIdentity {
        source_digest: SourceDigest::from_bytes([0u8; 32]),
        package_name: PACKAGE.into(),
        captured_at_epoch_millis: 42,
        protocol_major: 1,
        protocol_minor: 0,
    };
    let archive = parse_snapshot(&snapshot, frame, 1).expect("archive ingests windows");
    assert_eq!(archive.windows.len(), 2);
    assert_eq!(archive.windows[0].id, "window:42177c9");
    assert_eq!(
        archive.windows[0].title.as_deref(),
        Some(windows[0].title.as_str())
    );
    assert_eq!(
        archive.windows[1].title.as_deref(),
        Some(windows[1].title.as_str())
    );
    let nodes = archive
        .hierarchy(&archive.windows[0].id, 0, 10)
        .expect("window hierarchy");
    assert_eq!(nodes.len(), 2);
    assert_eq!(nodes[0].class_name, "com.codemx.ui.RealRootLayout");
    assert_eq!(nodes[1].class_name, "com.codemx.ui.RealTitleView");
}

#[test]
fn system_ui_snapshot_keeps_taskbar_and_statusbar_names_through_archive_projection() {
    let windows = parse(
        &system_ui_bare_taskbar_zip(),
        "com.android.systemui",
        WindowTarget::SystemUi,
    );
    let projected = snapshot_windows(&windows);
    let snapshot = json!({
        "protocolVersion": {"major": 1, "minor": 0},
        "packageName": "com.android.systemui",
        "capturedAtEpochMillis": 42,
        "display": {"widthPx": 1080, "heightPx": 2400, "density": 3.0},
        "capabilities": {"viewHierarchy": true},
        "root": projected[0]["root"].clone(),
        "windows": projected,
    });
    let frame = FrameIdentity {
        source_digest: SourceDigest::from_bytes([0; 32]),
        package_name: "com.android.systemui".into(),
        captured_at_epoch_millis: 42,
        protocol_major: 1,
        protocol_minor: 0,
    };
    let archive = parse_snapshot(&snapshot, frame, 1).unwrap();
    assert_eq!(
        archive
            .windows
            .iter()
            .map(|window| window.title.as_deref())
            .collect::<Vec<_>>(),
        [Some("StatusBar"), Some("Taskbar")]
    );
}

#[test]
fn count_json_nodes_walks_children() {
    let root = json!({
        "id": "root",
        "children": [
            {"id": "a"},
            {"id": "b", "children": [{"id": "c"}]}
        ]
    });
    assert_eq!(count_json_nodes(&root), 4);
    assert_eq!(count_json_nodes(&Value::Null), 0);
}

// ---------------------------------------------------------------------------
// Compose semantics promotion
// ---------------------------------------------------------------------------

#[test]
fn promotion_rewrites_nodes_strictly_inside_compose_hosts() {
    let button = dump_node(
        "android.widget.Button",
        (10, 20, 110, 60),
        Some("com.example:id/action"),
        json!({
            "contentDescription": "Tap",
            "enabled": true,
            "rawProperties": {"CustomFlag": "x", "layout:left": "10"}
        }),
        vec![],
    );
    let unknown = dump_node(
        "com.example.Widget",
        (10, 60, 110, 100),
        None,
        json!({}),
        vec![],
    );
    let host = dump_node(
        "androidx.compose.ui.platform.ComposeView",
        (0, 0, 1080, 2400),
        None,
        json!({}),
        vec![button, unknown],
    );
    let outside = dump_node(
        "android.widget.TextView",
        (0, 0, 100, 40),
        None,
        json!({}),
        vec![],
    );
    let root = dump_node(
        "android.widget.FrameLayout",
        (0, 0, 1080, 2400),
        None,
        json!({}),
        vec![outside, host],
    );
    let window = WindowDump {
        id: "window:42177c9".into(),
        title: "MainActivity".into(),
        kind: "ACTIVITY",
        root,
    };
    let mut snapshot = snapshot_windows(&[window])[0]
        .get("root")
        .expect("root")
        .clone();
    let before = count_json_nodes(&snapshot);
    let promotion = promote_compose_semantics(&mut snapshot);
    assert!(promotion.compose_host);
    assert_eq!(count_json_nodes(&snapshot), before);

    // Nodes outside a host and the host itself stay view nodes.
    let outside = &snapshot["children"][0];
    assert_eq!(outside.get("type").and_then(Value::as_str), Some("view"));
    assert_eq!(
        outside.get("className").and_then(Value::as_str),
        Some("android.widget.TextView")
    );
    let host = &snapshot["children"][1];
    assert_eq!(host.get("type").and_then(Value::as_str), Some("view"));
    assert_eq!(
        host.get("className").and_then(Value::as_str),
        Some("androidx.compose.ui.platform.ComposeView")
    );

    let button = &host["children"][0];
    assert_eq!(button.get("type").and_then(Value::as_str), Some("compose"));
    assert_eq!(
        button.get("className").and_then(Value::as_str),
        Some("Button")
    );
    assert_eq!(
        button.get("semanticsRole").and_then(Value::as_str),
        Some("Button")
    );
    assert!(button.get("attributes").is_none());
    assert!(button.get("resourceName").is_none());
    let semantics = button
        .get("semanticProperties")
        .expect("semanticProperties");
    assert_eq!(
        semantics
            .get("AccessibilityClassName")
            .and_then(Value::as_str),
        Some("android.widget.Button")
    );
    assert_eq!(
        semantics.get("ResourceId").and_then(Value::as_str),
        Some("com.example:id/action")
    );
    assert_eq!(
        semantics.get("ContentDescription").and_then(Value::as_str),
        Some("Tap")
    );
    assert_eq!(
        semantics.get("Enabled").and_then(Value::as_str),
        Some("true")
    );
    assert_eq!(
        semantics.get("CustomFlag").and_then(Value::as_str),
        Some("x")
    );
    assert_eq!(
        semantics.get("Layout:left").and_then(Value::as_str),
        Some("10")
    );

    let unknown = &host["children"][1];
    assert_eq!(
        unknown.get("className").and_then(Value::as_str),
        Some("SemanticsNode")
    );
    assert!(unknown.get("semanticsRole").is_none());
}

#[test]
fn promotion_without_compose_host_leaves_tree_unchanged() {
    let windows = parse(&fixture_zip(), PACKAGE, WindowTarget::ForegroundApp);
    let mut snapshot = snapshot_windows(&windows)[0]
        .get("root")
        .expect("root")
        .clone();
    let original = snapshot.clone();
    let promotion = promote_compose_semantics(&mut snapshot);
    assert!(!promotion.compose_host);
    assert_eq!(snapshot, original);
}

#[test]
fn raw_property_scalars_match_kotlin_to_string() {
    assert_eq!(java_float_string(8.0), "8.0");
    assert_eq!(java_float_string(0.0), "0.0");
    assert_eq!(java_float_string(-0.0), "-0.0");
    assert_eq!(java_float_string(0.5), "0.5");
    assert_eq!(java_float_string(-1.5), "-1.5");
    assert_eq!(java_float_string(0.001), "0.001");
    assert_eq!(java_float_string(1234567.0), "1234567.0");
    assert_eq!(java_float_string(1.0e7), "1.0E7");
    assert_eq!(java_float_string(1.0e-4), "1.0E-4");
    assert_eq!(java_float_string(f32::NAN), "NaN");
    assert_eq!(java_float_string(f32::INFINITY), "Infinity");
    assert_eq!(java_double_string(8.0), "8.0");
    assert_eq!(java_double_string(-0.25), "-0.25");
    assert_eq!(java_double_string(1.25e10), "1.25E10");
}

// ---------------------------------------------------------------------------
// Bounded / malformed input
// ---------------------------------------------------------------------------

#[test]
fn placeholder_rejects_non_zip_input() {
    assert!(
        parse_windows(
            b"not a zip",
            "example.app",
            WindowTarget::ForegroundApp,
            &CancellationToken::new()
        )
        .is_err()
    );
}

#[test]
fn rejects_empty_malformed_oversized_and_deep_wire_input() {
    let cancel = CancellationToken::new();
    assert!(parse_windows(b"", PACKAGE, WindowTarget::ForegroundApp, &cancel).is_err());

    let broken = zip_bytes(&[(MAIN_ENTRY, b"broken hierarchy")]);
    assert!(parse_windows(&broken, PACKAGE, WindowTarget::ForegroundApp, &cancel).is_err());

    // No entry matches the foreground package.
    let other_package = hierarchy();
    let non_matching = zip_bytes(&[("a3db2af StatusBar", other_package.as_slice())]);
    assert!(parse_windows(&non_matching, PACKAGE, WindowTarget::ForegroundApp, &cancel).is_err());

    // Unsupported encoded value signature is skipped, leaving no window.
    let unsupported = zip_bytes(&[(MAIN_ENTRY, b"X")]);
    assert!(parse_windows(&unsupported, PACKAGE, WindowTarget::ForegroundApp, &cancel).is_err());

    // Wire nesting past MAX_WIRE_DEPTH is malformed and skipped.
    let deep_wire = vec![b'M'; MAX_WIRE_DEPTH + 64];
    let deep_zip = zip_bytes(&[(MAIN_ENTRY, deep_wire.as_slice())]);
    assert!(parse_windows(&deep_zip, PACKAGE, WindowTarget::ForegroundApp, &cancel).is_err());

    // A matching entry above the per-entry budget is rejected.
    let oversized_entry = vec![0u8; MAX_ENTRY_BYTES + 1];
    let oversized_zip = zip_bytes(&[(MAIN_ENTRY, oversized_entry.as_slice())]);
    assert!(
        parse_windows(
            &oversized_zip,
            PACKAGE,
            WindowTarget::ForegroundApp,
            &cancel
        )
        .is_err()
    );

    // Input above the dump budget is rejected before ZIP parsing.
    let oversized_input = vec![0u8; MAX_VIEW_DUMP_BYTES + 1];
    assert!(
        parse_windows(
            &oversized_input,
            PACKAGE,
            WindowTarget::ForegroundApp,
            &cancel
        )
        .is_err()
    );

    // Cancellation is observed even for a valid dump.
    let cancelled = CancellationToken::new();
    cancelled.cancel();
    assert!(
        parse_windows(
            &fixture_zip(),
            PACKAGE,
            WindowTarget::ForegroundApp,
            &cancelled
        )
        .is_err()
    );
}

#[test]
fn filters_windows_and_rejects_when_only_undecodable_entries_match() {
    // Matching entries with an undecodable payload are skipped like Kotlin's
    // runCatching; when nothing decodes the capture fails.
    let broken = zip_bytes(&[
        (MAIN_ENTRY, b"broken hierarchy"),
        (
            "51aa71 com.codemx.anrdemo/com.codemx.anrdemo.ConfirmDialog",
            b"",
        ),
    ]);
    assert!(
        parse_windows(
            &broken,
            PACKAGE,
            WindowTarget::ForegroundApp,
            &CancellationToken::new()
        )
        .is_err()
    );
    // One decodable sibling keeps the capture alive.
    let payload = hierarchy();
    let partial = zip_bytes(&[
        (MAIN_ENTRY, b"broken hierarchy"),
        (
            "51aa71 com.codemx.anrdemo/com.codemx.anrdemo.ConfirmDialog",
            payload.as_slice(),
        ),
    ]);
    let windows = parse(&partial, PACKAGE, WindowTarget::ForegroundApp);
    assert_eq!(windows.len(), 1);
    assert_eq!(windows[0].title, "ConfirmDialog");
}

#[test]
fn deep_view_trees_are_capped_at_the_archive_ingestion_depth() {
    let payload = deep_chain(70);
    let zip = zip_bytes(&[(MAIN_ENTRY, payload.as_slice())]);
    let windows = parse(&zip, PACKAGE, WindowTarget::ForegroundApp);
    assert_eq!(windows.len(), 1);
    assert_eq!(max_depth(&windows[0].root), MAX_NODE_DEPTH);
    assert_eq!(node_count(&windows), MAX_NODE_DEPTH + 1);
}
