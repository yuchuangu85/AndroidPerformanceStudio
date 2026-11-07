use aps_adb::{AdbClient, AdbLocation};
use aps_platform::CancellationToken;

#[derive(Clone, Debug, Default)]
pub struct GpuDeviceContext {
    pub serial: String,
    pub model: Option<String>,
    pub android_release: Option<String>,
    pub sdk_level: Option<u32>,
    pub gpu_vendor: Option<String>,
    pub gpu_renderer: Option<String>,
    pub driver: Option<String>,
    pub graphics_api: Option<String>,
    pub graphics_implementation: Option<String>,
    pub evidence: Vec<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GpuDeviceEvidence {
    Getprop,
    SurfaceFlinger,
}

pub struct GpuDeviceContextReader {
    adb: AdbClient,
}
impl GpuDeviceContextReader {
    pub fn new(location: AdbLocation) -> Self {
        Self {
            adb: AdbClient::new(location),
        }
    }
    pub fn read(
        &self,
        serial: &str,
        cancel: &CancellationToken,
    ) -> Result<GpuDeviceContext, String> {
        let properties = self
            .adb
            .device_properties(serial, cancel)
            .map_err(|e| e.to_string())?;
        let surface = self
            .adb
            .surfaceflinger_dump(serial, cancel)
            .map_err(|e| e.to_string())?;
        Ok(GpuDeviceContext {
            serial: serial.into(),
            model: properties.model,
            android_release: properties.android_release,
            sdk_level: properties.sdk_level,
            gpu_vendor: find_value(&surface, &["GLES: Vendor", "GPU Vendor", "vendor"]),
            gpu_renderer: find_value(&surface, &["GLES: Renderer", "GPU Renderer", "renderer"]),
            driver: find_value(&surface, &["Driver", "driver"]),
            graphics_api: find_value(&surface, &["GLES", "Vulkan", "Graphics API"]),
            graphics_implementation: find_value(
                &surface,
                &["Graphics implementation", "implementation"],
            ),
            evidence: vec!["adb:getprop".into(), "adb:dumpsys SurfaceFlinger".into()],
        })
    }
}
fn find_value(text: &str, keys: &[&str]) -> Option<String> {
    text.lines().find_map(|line| {
        keys.iter()
            .find(|key| {
                line.to_ascii_lowercase()
                    .contains(&key.to_ascii_lowercase())
            })
            .and_then(|_| {
                line.split_once(':')
                    .map(|(_, value)| value.trim().to_owned())
            })
            .filter(|value| !value.is_empty())
    })
}
