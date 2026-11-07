use std::ffi::OsString;
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

const APP_DIRECTORY: &str = "AndroidPerformanceStudio-Rust";
static NEXT_ID: AtomicU64 = AtomicU64::new(0);

/// New Rust-owned directories, never legacy Compose preferences or provider state.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppDirectories {
    pub data: PathBuf,
    pub cache: PathBuf,
    pub temporary: PathBuf,
}

impl AppDirectories {
    /// Discover paths without creating directories or reading preference files.
    pub fn discover() -> io::Result<Self> {
        let home = home_directory()?;
        #[cfg(target_os = "macos")]
        let (data, cache) = (
            home.join("Library/Application Support").join(APP_DIRECTORY),
            home.join("Library/Caches").join(APP_DIRECTORY),
        );
        #[cfg(target_os = "windows")]
        let (data, cache) = {
            let local =
                absolute_environment("LOCALAPPDATA").unwrap_or_else(|| home.join("AppData/Local"));
            (
                local.join(APP_DIRECTORY).join("data"),
                local.join(APP_DIRECTORY).join("cache"),
            )
        };
        #[cfg(not(any(target_os = "macos", target_os = "windows")))]
        let (data, cache) = (
            absolute_environment("XDG_DATA_HOME")
                .unwrap_or_else(|| home.join(".local/share"))
                .join(APP_DIRECTORY),
            absolute_environment("XDG_CACHE_HOME")
                .unwrap_or_else(|| home.join(".cache"))
                .join(APP_DIRECTORY),
        );
        Ok(Self {
            data,
            cache,
            temporary: std::env::temp_dir().join(APP_DIRECTORY),
        })
    }

    pub fn create(&self) -> io::Result<()> {
        for path in [&self.data, &self.cache, &self.temporary] {
            create_private_directory(path)?;
        }
        Ok(())
    }

    pub fn settings_file(&self) -> PathBuf {
        self.data.join("preferences-v1.settings")
    }
}

fn home_directory() -> io::Result<PathBuf> {
    absolute_environment("HOME")
        .or_else(|| absolute_environment("USERPROFILE"))
        .ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::NotFound,
                "absolute home directory unavailable",
            )
        })
}

#[cfg_attr(target_os = "macos", allow(dead_code))]
fn absolute_environment(key: &str) -> Option<PathBuf> {
    std::env::var_os(key)
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
}

fn create_private_directory(path: &Path) -> io::Result<()> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.is_dir() && !metadata.is_symlink() => return Ok(()),
        Ok(_) => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "application directory is not a real directory",
            ));
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(error),
    }
    let mut builder = fs::DirBuilder::new();
    builder.recursive(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder.create(path)
}

/// Same-directory replace without a truncate/delete fallback. A rename failure
/// preserves the existing destination. This is not a multi-file transaction.
pub fn atomic_write(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let parent = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    let name = path.file_name().ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            "destination file name required",
        )
    })?;
    create_private_directory(parent)?;
    for _ in 0..32 {
        let mut temporary_name = OsString::from(name);
        temporary_name.push(format!(
            ".{}-{}.tmp",
            std::process::id(),
            NEXT_ID.fetch_add(1, Ordering::Relaxed)
        ));
        let temporary = parent.join(temporary_name);
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = match options.open(&temporary) {
            Ok(file) => file,
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error),
        };
        let result = (|| {
            file.write_all(bytes)?;
            file.sync_all()?;
            drop(file);
            fs::rename(&temporary, path)
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temporary);
        }
        return result;
    }
    Err(io::Error::new(
        io::ErrorKind::AlreadyExists,
        "temporary file allocation exhausted",
    ))
}

/// An exclusively created owned scratch directory, removed on Drop.
/// Callers must not move user files into it or create links to external trees.
pub struct TempWorkspace {
    path: PathBuf,
}

impl TempWorkspace {
    pub fn create(root: &Path) -> io::Result<Self> {
        create_private_directory(root)?;
        for _ in 0..32 {
            let path = root.join(format!(
                "job-{}-{}",
                std::process::id(),
                NEXT_ID.fetch_add(1, Ordering::Relaxed)
            ));
            let mut builder = fs::DirBuilder::new();
            #[cfg(unix)]
            {
                use std::os::unix::fs::DirBuilderExt;
                builder.mode(0o700);
            }
            match builder.create(&path) {
                Ok(()) => return Ok(Self { path }),
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(error),
            }
        }
        Err(io::Error::new(
            io::ErrorKind::AlreadyExists,
            "temporary directory allocation exhausted",
        ))
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for TempWorkspace {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}
