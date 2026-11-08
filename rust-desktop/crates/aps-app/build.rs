//! Embed the macOS bundle metadata into the executable. Unbundled binaries read
//! their application name from this `__TEXT,__info_plist` section, so the menu
//! bar and Dock show "Android Performance Studio" instead of the cargo binary
//! name without requiring a .app wrapper.

fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("macos") {
        return;
    }
    let manifest = std::path::Path::new(env!("CARGO_MANIFEST_DIR"));
    let plist = manifest.join("Info.plist");
    println!("cargo:rerun-if-changed={}", plist.display());
    println!(
        "cargo:rustc-link-arg-bins=-Wl,-sectcreate,__TEXT,__info_plist,{}",
        plist.display()
    );
}
