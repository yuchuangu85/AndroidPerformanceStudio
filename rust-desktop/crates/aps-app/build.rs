//! Embed platform metadata and the offline user guide into the executable.

use std::{
    collections::HashMap,
    fmt::Write as _,
    fs, io,
    path::{Path, PathBuf},
};

fn main() {
    embed_user_guide().expect("embed the bilingual user guide");
    match std::env::var("CARGO_CFG_TARGET_OS").as_deref() {
        Ok("macos") => {
            let manifest = std::path::Path::new(env!("CARGO_MANIFEST_DIR"));
            let plist = manifest.join("Info.plist");
            println!("cargo:rerun-if-changed={}", plist.display());
            println!(
                "cargo:rustc-link-arg-bins=-Wl,-sectcreate,__TEXT,__info_plist,{}",
                plist.display()
            );
        }
        Ok("windows") => {
            println!("cargo:rerun-if-changed=app-icon.rc");
            println!("cargo:rerun-if-changed=assets/icons/windows/app-icon.ico");
            embed_resource::compile("app-icon.rc", embed_resource::NONE)
                .manifest_required()
                .unwrap();
        }
        _ => {}
    }
}

fn embed_user_guide() -> io::Result<()> {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
    let out = PathBuf::from(std::env::var_os("OUT_DIR").expect("Cargo sets OUT_DIR"));
    let mut files = Vec::new();
    for site in ["docs-user", "docs-user-zh"] {
        let directory = root.join(site);
        println!("cargo:rerun-if-changed={}", directory.display());
        for required in ["index.html", "README.md", "_sidebar.md", "js/init.js"] {
            if !directory.join(required).is_file() {
                return Err(io::Error::other(format!("missing {site}/{required}")));
            }
        }
        guide_files(&root, &directory, &mut files)?;
    }
    files.sort_unstable();
    let mut source = String::from("&[\n");
    let mut by_content = HashMap::new();
    for (url, path) in files {
        // The translations share screenshots and videos.
        let path = by_content.entry(fs::read(&path)?).or_insert(path);
        writeln!(source, "    ({url:?}, include_bytes!({path:?})),").unwrap();
    }
    source.push_str("]\n");
    fs::write(out.join("user_guide_assets.rs"), source)
}

fn guide_files(
    root: &Path,
    directory: &Path,
    files: &mut Vec<(String, PathBuf)>,
) -> io::Result<()> {
    for entry in fs::read_dir(directory)? {
        let entry = entry?;
        if entry.file_name().to_string_lossy().starts_with('.') {
            continue;
        }
        let path = entry.path();
        let kind = entry.file_type()?;
        if kind.is_dir() {
            guide_files(root, &path, files)?;
        } else if kind.is_file()
            && matches!(
                path.extension().and_then(|ext| ext.to_str()),
                Some("html" | "md" | "js" | "css" | "svg" | "png" | "gif" | "webm")
            )
        {
            let relative = path.strip_prefix(root).expect("guide path is under root");
            let url = relative
                .to_str()
                .ok_or_else(|| io::Error::other("guide path is not UTF-8"))?
                .replace(std::path::MAIN_SEPARATOR, "/");
            files.push((format!("/{url}"), path));
        }
    }
    Ok(())
}
