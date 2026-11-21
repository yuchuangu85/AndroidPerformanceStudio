use aps_platform::ProcessRequest;
use std::path::{Path, PathBuf};

const MAIN_CLASS: &str =
    "com.androidperformancestudio.compose.inspection.host.ComposeHeadlessMainKt";

pub(super) struct Runtime {
    pub helper: PathBuf,
    pub bundle: PathBuf,
    pub java_home: Option<PathBuf>,
    classpath: Option<PathBuf>,
}

impl Runtime {
    pub fn discover() -> Result<Self, String> {
        let executable = std::env::current_exe()
            .map_err(|_| "Cannot locate the Compose inspection application".to_owned())?;
        let resources = executable
            .parent()
            .ok_or("Cannot locate the Compose inspection resources")?
            .join("resources");
        #[cfg(debug_assertions)]
        let checkout = Some(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../.."));
        #[cfg(not(debug_assertions))]
        let checkout: Option<PathBuf> = None;
        Self::resolve(
            &resources,
            checkout.as_deref(),
            std::env::var_os("APS_COMPOSE_HELPER").map(PathBuf::from),
            std::env::var_os("APS_COMPOSE_AGENT_BUNDLE").map(PathBuf::from),
        )
    }

    fn resolve(
        resources: &Path,
        checkout: Option<&Path>,
        helper_override: Option<PathBuf>,
        bundle_override: Option<PathBuf>,
    ) -> Result<Self, String> {
        let packaged_helper = resources.join(helper_relative_path());
        let packaged = helper_override.is_none() && packaged_helper.is_file();
        let helper = helper_override.unwrap_or_else(|| {
            let packaged = packaged_helper;
            if packaged.is_file() {
                packaged
            } else if let Some(checkout) = checkout {
                checkout.join("desktop-viewer/layout-inspector/compose-inspection-host/build/install/aps-compose-inspection/bin/aps-compose-inspection")
            } else {
                packaged
            }
        });
        if !executable_file(&helper) {
            return Err("Compose helper missing or not executable. Rebuild the desktop package with the Compose inspection runtime, or set APS_COMPOSE_HELPER to its prepared distribution launcher (Java 17+).".into());
        }
        let bundle = bundle_override.unwrap_or_else(|| {
            let packaged = resources.join("compose-agent-bundle");
            if packaged.join("manifest.properties").is_file() {
                packaged
            } else if let Some(checkout) = checkout {
                checkout.join("desktop-viewer/layout-inspector/compose-agent-build/build/compose-agent-bundle")
            } else {
                packaged
            }
        });
        if !bundle.join("manifest.properties").is_file() {
            return Err("Compose agent bundle missing. Rebuild the desktop package with its verified Compose agent bundle, or set APS_COMPOSE_AGENT_BUNDLE to a verified bundle.".into());
        }
        let java_root = resources.join("java-runtime");
        let java_home = if java_root.exists() {
            let home = if java_root.join("Contents/Home").is_dir() {
                java_root.join("Contents/Home")
            } else {
                java_root
            };
            let java = home.join(java_relative_path());
            if !executable_file(&java) {
                return Err("Bundled Compose Java runtime is incomplete. Rebuild the desktop package with Java 17+.".into());
            }
            Some(home)
        } else {
            // Explicit/development launchers retain their own JAVA_HOME/PATH support.
            None
        };
        let classpath = if packaged {
            if java_home.is_none() {
                return Err("Bundled Compose Java runtime is missing. Rebuild the desktop package with Java 17+.".into());
            }
            let libraries = resources.join("compose-inspection-host/lib");
            if !libraries.is_dir() {
                return Err("Bundled Compose helper libraries are missing. Rebuild the complete helper distribution.".into());
            }
            Some(libraries.join("*"))
        } else {
            None
        };
        Ok(Self {
            helper,
            bundle,
            java_home,
            classpath,
        })
    }

    pub fn command(&self) -> ProcessRequest {
        // Invoke Java directly for packaged distributions: Gradle's launchers
        // depend on shell utilities (or cmd.exe) that GUI launches may not have.
        let mut request = if let Some(classpath) = &self.classpath {
            let home = self
                .java_home
                .as_ref()
                .expect("packaged Java was validated");
            ProcessRequest::new(home.join(java_relative_path()))
                .arg("-cp")
                .arg(classpath)
                .arg(MAIN_CLASS)
        } else {
            ProcessRequest::new(&self.helper)
        };
        if let Some(home) = &self.java_home {
            request.environment.insert("JAVA_HOME".into(), home.into());
        }
        request
    }
}

fn helper_relative_path() -> &'static str {
    if cfg!(windows) {
        "compose-inspection-host/bin/aps-compose-inspection.bat"
    } else {
        "compose-inspection-host/bin/aps-compose-inspection"
    }
}

fn java_relative_path() -> &'static str {
    if cfg!(windows) {
        "bin/java.exe"
    } else {
        "bin/java"
    }
}

fn executable_file(path: &Path) -> bool {
    let Ok(metadata) = path.metadata() else {
        return false;
    };
    if !metadata.is_file() {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o111 == 0 {
            return false;
        }
    }
    true
}

#[cfg(test)]
mod tests;
