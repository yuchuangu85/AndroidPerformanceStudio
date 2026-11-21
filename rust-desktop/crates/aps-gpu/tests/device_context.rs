#![cfg(unix)]

use aps_adb::{AdbLocation, AdbLocationSource};
use aps_gpu::{GpuDeviceContext, GpuDeviceContextReader, GraphicsApi};
use aps_platform::{CancellationToken, TempWorkspace};
use std::fs;
use std::os::unix::fs::PermissionsExt;

fn inspect(properties: &str, surface: &str) -> GpuDeviceContext {
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    fs::write(temp.path().join("properties"), properties).unwrap();
    fs::write(temp.path().join("surface"), surface).unwrap();
    let adb = temp.path().join("adb");
    fs::write(&adb, format!("#!/bin/sh\ncase \"$4\" in\n getprop) cat '{}' ;;\n dumpsys) cat '{}' ;;\n *) exit 1 ;;\nesac\n", temp.path().join("properties").display(), temp.path().join("surface").display())).unwrap();
    fs::set_permissions(&adb, fs::Permissions::from_mode(0o700)).unwrap();
    GpuDeviceContextReader::new(AdbLocation {
        executable: adb,
        source: AdbLocationSource::ExplicitExecutable,
    })
    .read("serial-1", &CancellationToken::new())
    .unwrap()
}

#[test]
fn graphics_properties_report_apis_modules_and_evidence_without_inventing_driver() {
    let context = inspect(
        "[ro.product.model]: [Pixel 9]\n[ro.build.version.sdk]: [36]\n[ro.opengles.version]: [196610]\n[ro.vulkan.version]: [0x00401000]\n[ro.hardware.egl]: [angle]\n[ro.hardware.vulkan]: [vulkan.tok]\n[ro.gfx.driver.0]: [com.vendor.graphicsdriver]\n",
        "",
    );
    assert_eq!(context.model.as_deref(), Some("Pixel 9"));
    assert_eq!(context.sdk_level, Some(36));
    assert_eq!(
        context.graphics_apis,
        vec![GraphicsApi::OpenGlEs, GraphicsApi::Vulkan]
    );
    assert_eq!(
        context.graphics_implementation.as_deref(),
        Some("EGL: angle · Vulkan: vulkan.tok")
    );
    assert_eq!(context.driver, None);
    assert!(
        context
            .evidence
            .iter()
            .any(|value| value.contains("ro.opengles.version") && value.contains("3.2"))
    );
    assert!(
        context
            .evidence
            .iter()
            .any(|value| value.contains("ro.vulkan.version") && value.contains("1.1.0"))
    );
    assert!(
        context
            .evidence
            .iter()
            .any(|value| value.contains("ro.gfx.driver.0")
                && value.contains("com.vendor.graphicsdriver"))
    );
}

#[test]
fn surfaceflinger_gles_reports_separate_vendor_renderer_and_driver() {
    let context = inspect(
        "[ro.product.model]: [Pixel 9]\n",
        "GLES: Google, Mali-G715, OpenGL ES 3.2 v1.r42p0\n",
    );
    assert_eq!(context.gpu_vendor.as_deref(), Some("Google"));
    assert_eq!(context.gpu_renderer.as_deref(), Some("Mali-G715"));
    assert_eq!(context.driver.as_deref(), Some("v1.r42p0"));
    assert_eq!(context.graphics_apis, vec![GraphicsApi::OpenGlEs]);
    assert!(
        context
            .evidence
            .iter()
            .any(|value| value.contains("surfaceFlingerGlVersion"))
    );
}

#[test]
fn a_failed_shell_read_is_an_error_not_an_empty_graphics_context() {
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let adb = temp.path().join("adb");
    fs::write(&adb, "#!/bin/sh\nexit 1\n").unwrap();
    fs::set_permissions(&adb, fs::Permissions::from_mode(0o700)).unwrap();
    let result = GpuDeviceContextReader::new(AdbLocation {
        executable: adb,
        source: AdbLocationSource::ExplicitExecutable,
    })
    .read("serial-1", &CancellationToken::new());
    assert!(result.is_err());
}
