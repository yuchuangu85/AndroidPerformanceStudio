//! Embed platform application metadata and icons into the executable.

fn main() {
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
