mod build_support;

use build_support::{load_catalog, placeholders, validate_catalogs, variant};
use std::{fmt::Write, path::PathBuf};

fn main() {
    println!("cargo:rerun-if-changed=resources");
    println!("cargo:rerun-if-changed=build_support.rs");
    let english =
        load_catalog(std::path::Path::new("resources/values")).expect("invalid English resources");
    let chinese = load_catalog(std::path::Path::new("resources/values-zh"))
        .expect("invalid Simplified Chinese resources");
    validate_catalogs(&english, &chinese).expect("invalid locale resource contract");

    let mut generated =
        String::from("#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]\npub enum TextKey {\n");
    for key in english.keys() {
        writeln!(generated, "    {},", variant(key)).unwrap();
    }
    generated.push_str("}\nimpl TextKey {\n    pub const ALL: &'static [Self] = &[\n");
    for key in english.keys() {
        writeln!(generated, "        Self::{},", variant(key)).unwrap();
    }
    generated.push_str(
        "    ];\n    pub const fn as_str(self) -> &'static str {\n        match self {\n",
    );
    for key in english.keys() {
        writeln!(generated, "            Self::{} => {key:?},", variant(key)).unwrap();
    }
    generated.push_str("        }\n    }\n    pub fn parameters(self) -> &'static [&'static str] {\n        match self {\n");
    for (key, text) in &english {
        let names: Vec<_> = placeholders(text).unwrap().into_iter().collect();
        writeln!(
            generated,
            "            Self::{} => &{names:?},",
            variant(key)
        )
        .unwrap();
    }
    generated.push_str("        }\n    }\n}\nimpl Language {\n    pub fn text(self, key: TextKey) -> &'static str {\n        match key {\n");
    for (key, text) in &english {
        writeln!(
            generated,
            "            TextKey::{} => match self.resolved() {{ Self::System | Self::English => {text:?}, Self::SimplifiedChinese => {:?} }},",
            variant(key), chinese[key],
        )
        .unwrap();
    }
    generated.push_str("        }\n    }\n}\n");
    let output = PathBuf::from(std::env::var_os("OUT_DIR").expect("Cargo OUT_DIR"));
    std::fs::write(output.join("strings.rs"), generated).expect("write generated resources");
}
