//! Android graphics context observed through explicit ADB reads.
//!
//! Every reported field keeps its evidence source. Renderer, driver and API
//! versions are never inferred from driver package names or module names.

use crate::GraphicsApi;
use aps_adb::{AdbClient, AdbLocation};
use aps_platform::CancellationToken;
use std::collections::BTreeMap;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct GpuDeviceContext {
    pub serial: String,
    pub model: Option<String>,
    pub android_release: Option<String>,
    pub sdk_level: Option<u32>,
    pub gpu_vendor: Option<String>,
    pub gpu_renderer: Option<String>,
    pub driver: Option<String>,
    pub open_gl_es_version: Option<String>,
    pub vulkan_version: Option<String>,
    pub graphics_apis: Vec<GraphicsApi>,
    pub graphics_implementation: Option<String>,
    pub evidence: Vec<String>,
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

    /// Two sequential read-only observations (`getprop`, then
    /// `dumpsys SurfaceFlinger`); neither is an atomic snapshot.
    pub fn read(
        &self,
        serial: &str,
        cancel: &CancellationToken,
    ) -> Result<GpuDeviceContext, String> {
        let properties = self
            .adb
            .getprop_output(serial, cancel)
            .map_err(|error| error.to_string())?;
        let surface = self
            .adb
            .surfaceflinger_dump(serial, cancel)
            .map_err(|error| error.to_string())?;
        Ok(parse(serial, &properties, &surface))
    }
}

/// Pure parser for the two captured command outputs.
pub fn parse(serial: &str, properties_output: &str, surface_output: &str) -> GpuDeviceContext {
    let properties = parse_properties(properties_output);
    let gles = parse_gles(surface_output);
    let open_gl_es_version = properties
        .get("ro.opengles.version")
        .and_then(|value| decode_open_gl_es(value));
    let vulkan_version = properties
        .get("ro.vulkan.version")
        .and_then(|value| decode_vulkan(value));
    let mut graphics_apis = Vec::new();
    if open_gl_es_version.is_some() || gles.version.is_some() {
        graphics_apis.push(GraphicsApi::OpenGlEs);
    }
    if vulkan_version.is_some() {
        graphics_apis.push(GraphicsApi::Vulkan);
    }
    let context = GpuDeviceContext {
        serial: serial.to_owned(),
        model: properties
            .get("ro.product.model")
            .and_then(|value| usable(value)),
        android_release: properties
            .get("ro.build.version.release")
            .and_then(|value| usable(value)),
        sdk_level: properties
            .get("ro.build.version.sdk")
            .and_then(|value| value.trim().parse::<u32>().ok())
            .filter(|level| *level > 0),
        gpu_vendor: gles.vendor.clone(),
        gpu_renderer: gles.renderer.clone(),
        driver: gles.driver.clone(),
        graphics_implementation: graphics_implementation(&properties),
        graphics_apis,
        evidence: Vec::new(),
        open_gl_es_version,
        vulkan_version,
    };
    GpuDeviceContext {
        evidence: evidence(&context, &properties, &gles),
        ..context
    }
}

fn parse_properties(output: &str) -> BTreeMap<String, String> {
    output
        .lines()
        .filter_map(|line| {
            let line = line.trim();
            let (key, value) = line.strip_prefix('[')?.split_once("]: [")?;
            // A malformed or multiline value is not a usable property observation.
            let value = value.strip_suffix(']')?;
            Some((key.to_owned(), value.trim().to_owned()))
        })
        .collect()
}

#[derive(Default)]
struct GlesObservation {
    vendor: Option<String>,
    renderer: Option<String>,
    version: Option<String>,
    driver: Option<String>,
}

fn usable(value: &str) -> Option<String> {
    let value = value.trim();
    (!value.is_empty()
        && !["unknown", "null", "[null]", "(null)", "n/a"]
            .iter()
            .any(|invalid| value.eq_ignore_ascii_case(invalid)))
    .then(|| value.to_owned())
}

fn parse_gles(text: &str) -> GlesObservation {
    let Some(gles) = text.lines().find_map(|line| {
        let line = line.trim_start();
        let (label, value) = line.split_once(':')?;
        (label.eq_ignore_ascii_case("GLES") && !value.trim().is_empty()).then_some(value.trim())
    }) else {
        return GlesObservation::default();
    };
    let mut parts = gles.splitn(2, ',');
    let vendor = parts.next().and_then(usable);
    let rest = parts.next().unwrap_or("");
    // ANGLE renderer names contain commas; the GL_VERSION prefix is the only
    // reliable delimiter for the three reported fields.
    let version_delimiter = rest.match_indices(',').find_map(|(index, _)| {
        let candidate = rest[index + 1..].trim_start();
        candidate
            .get(..9)
            .filter(|prefix| prefix.eq_ignore_ascii_case("OpenGL ES"))
            .map(|_| index)
    });
    let (renderer, version) = match version_delimiter {
        Some(index) => (&rest[..index], &rest[index + 1..]),
        None => rest.split_once(',').unwrap_or((rest, "")),
    };
    let version = usable(version);
    let driver = version.as_deref().and_then(driver_suffix);
    GlesObservation {
        vendor,
        renderer: usable(renderer),
        version,
        driver,
    }
}

/// Only a `GL_VERSION` suffix that names a driver is reported; an ANGLE build
/// version is not the native graphics driver version.
fn driver_suffix(version: &str) -> Option<String> {
    let lower = version.to_ascii_lowercase();
    let rest = lower.strip_prefix("opengl es")?;
    let rest = rest
        .strip_prefix("-cm")
        .or_else(|| rest.strip_prefix("-cl"))
        .unwrap_or(rest);
    if !rest.starts_with(char::is_whitespace) {
        return None;
    }
    let offset = version.len() - rest.len();
    let original = version[offset..].trim_start();
    let (number, suffix) = original.split_once(char::is_whitespace)?;
    let pieces: Vec<_> = number.split('.').collect();
    if !(2..=3).contains(&pieces.len())
        || pieces
            .iter()
            .any(|part| part.is_empty() || !part.bytes().all(|byte| byte.is_ascii_digit()))
    {
        return None;
    }
    let suffix = usable(suffix)?;
    let lower = suffix.to_ascii_lowercase();
    let digit_after = |prefix: &str| {
        lower
            .strip_prefix(prefix)
            .and_then(|rest| rest.bytes().next())
            .is_some_and(|byte| byte.is_ascii_digit())
    };
    let mesa = lower
        .strip_prefix("mesa")
        .and_then(|rest| rest.strip_prefix(char::is_whitespace))
        .is_some_and(|rest| {
            rest.trim_start()
                .bytes()
                .next()
                .is_some_and(|byte| byte.is_ascii_digit())
        });
    (digit_after("v") || digit_after("v@") || mesa).then_some(suffix)
}

fn parse_version_value(raw: &str) -> Option<u32> {
    let value = raw.trim();
    let parsed = match value
        .strip_prefix("0x")
        .or_else(|| value.strip_prefix("0X"))
    {
        Some(hex) => u32::from_str_radix(hex, 16).ok()?,
        None => value.parse::<u32>().ok()?,
    };
    (parsed > 0).then_some(parsed)
}

fn decode_open_gl_es(raw: &str) -> Option<String> {
    let value = parse_version_value(raw)?;
    let major = value >> 16;
    (major > 0).then(|| format!("{major}.{}", value & 0xffff))
}

fn decode_vulkan(raw: &str) -> Option<String> {
    let value = parse_version_value(raw)?;
    let major = (value >> 22) & 0x7f;
    let minor = (value >> 12) & 0x3ff;
    let patch = value & 0xfff;
    let variant = value >> 29;
    // Variant bits describe a non-standard variant; the raw value stays in the
    // evidence instead of being mislabelled as a Vulkan API version.
    (major > 0 && variant == 0).then(|| format!("{major}.{minor}.{patch}"))
}

fn graphics_implementation(properties: &BTreeMap<String, String>) -> Option<String> {
    let mut names = Vec::new();
    if let Some(egl) = properties
        .get("ro.hardware.egl")
        .and_then(|value| usable(value))
    {
        names.push(format!("EGL: {egl}"));
    }
    if let Some(vulkan) = properties
        .get("ro.hardware.vulkan")
        .and_then(|value| usable(value))
    {
        names.push(format!("Vulkan: {vulkan}"));
    }
    (!names.is_empty()).then(|| names.join(" · "))
}

fn evidence(
    context: &GpuDeviceContext,
    properties: &BTreeMap<String, String>,
    gles: &GlesObservation,
) -> Vec<String> {
    let mut evidence = Vec::new();
    evidence.push(if properties.is_empty() {
        "getprop: No parseable Android system properties reported".into()
    } else {
        "getprop: adb shell getprop".into()
    });
    evidence.push(if gles.version.is_some() {
        "surfaceFlinger: dumpsys SurfaceFlinger GLES".into()
    } else {
        "surfaceFlinger: GLES details not reported by dumpsys SurfaceFlinger".into()
    });
    if context.model.is_some() {
        evidence.push("model: getprop ro.product.model".into());
    }
    if context.sdk_level.is_some() {
        evidence.push("apiLevel: getprop ro.build.version.sdk".into());
    }
    if gles.renderer.is_some() {
        evidence.push("gpuRenderer: dumpsys SurfaceFlinger GLES".into());
    }
    if gles.vendor.is_some() {
        evidence.push("gpuVendor: dumpsys SurfaceFlinger GLES".into());
    }
    if let Some(version) = &gles.version {
        evidence.push(format!(
            "surfaceFlingerGlVersion: dumpsys SurfaceFlinger GLES GL_VERSION: {version}"
        ));
    }
    if gles.driver.is_some() {
        evidence.push(
            "driverVersion: dumpsys SurfaceFlinger GLES GL_VERSION driver suffix (reported text)"
                .into(),
        );
    }
    if let Some(package) = properties
        .get("ro.gfx.driver.0")
        .and_then(|value| usable(value))
    {
        evidence.push(format!(
            "graphicsDriverPackage: getprop ro.gfx.driver.0 (driver package, not version): {package}"
        ));
    }
    if let (Some(version), Some(raw)) = (
        &context.open_gl_es_version,
        properties
            .get("ro.opengles.version")
            .and_then(|v| usable(v)),
    ) {
        evidence.push(format!(
            "openGlEsVersion: getprop ro.opengles.version: {version} (raw: {raw})"
        ));
    }
    if let (Some(version), Some(raw)) = (
        &context.vulkan_version,
        properties.get("ro.vulkan.version").and_then(|v| usable(v)),
    ) {
        evidence.push(format!(
            "vulkanVersion: getprop ro.vulkan.version: {version} (raw: {raw})"
        ));
    }
    if !context.graphics_apis.is_empty() {
        evidence.push(
            "graphicsApis: Android system graphics version evidence; not an application's API or AGI compatibility validation".into(),
        );
    }
    if gles.vendor.is_none()
        && gles.renderer.is_none()
        && gles.driver.is_none()
        && context.graphics_apis.is_empty()
    {
        evidence.push(
            "deviceGraphics: GPU renderer, vendor, driver version and graphics API evidence not reported"
                .into(),
        );
    }
    evidence
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn malformed_properties_are_ignored_and_zero_versions_are_not_apis() {
        let context = parse(
            "serial-1",
            "not a property\n[ro.build.version.sdk]: [0]\n[ro.opengles.version]: [0]\n[ro.vulkan.version]: [4294967296]\n",
            "GLES: unknown, [null], unknown\n",
        );
        assert_eq!(context.sdk_level, None);
        assert_eq!(context.gpu_vendor, None);
        assert_eq!(context.gpu_renderer, None);
        assert_eq!(context.driver, None);
        assert!(context.graphics_apis.is_empty());
        assert!(
            context
                .evidence
                .iter()
                .any(|value| value == "getprop: adb shell getprop")
        );
        assert!(
            !context
                .evidence
                .iter()
                .any(|value| value.contains("apiLevel:"))
        );
    }

    #[test]
    fn empty_property_output_is_labelled_as_unreported_evidence() {
        let context = parse("serial-1", "", "");
        assert!(
            context
                .evidence
                .iter()
                .any(|value| value.contains("No parseable"))
        );
        assert!(
            context
                .evidence
                .iter()
                .any(|value| value.contains("deviceGraphics"))
        );
    }

    #[test]
    fn driver_packages_are_not_driver_versions_or_api_support() {
        let context = parse(
            "serial-1",
            "[ro.hardware.egl]: [angle]\n[ro.hardware.vulkan]: [adreno]\n[ro.gfx.driver.0]: [com.qualcomm.qti.gpudrivers.kalama.api33]\n",
            "SurfaceFlinger state without GLES details\n",
        );
        assert_eq!(context.gpu_renderer, None);
        assert_eq!(context.driver, None);
        assert!(context.graphics_apis.is_empty());
        assert_eq!(
            context.graphics_implementation.as_deref(),
            Some("EGL: angle · Vulkan: adreno")
        );
    }

    #[test]
    fn angle_build_versions_are_not_native_driver_versions() {
        let context = parse(
            "serial-1",
            "",
            "GLES: Google, ANGLE (Qualcomm, Adreno Vulkan 1.3.0), OpenGL ES 3.2 (ANGLE 2.1.0 git hash)\n",
        );
        assert_eq!(
            context.gpu_renderer.as_deref(),
            Some("ANGLE (Qualcomm, Adreno Vulkan 1.3.0)")
        );
        assert_eq!(context.graphics_apis, vec![GraphicsApi::OpenGlEs]);
        assert_eq!(context.driver, None);
        assert!(
            context
                .evidence
                .iter()
                .any(|value| value.contains("ANGLE 2.1.0"))
        );
    }

    #[test]
    fn hex_versions_decode_without_losing_patch_evidence() {
        let context = parse(
            "serial-1",
            "[ro.opengles.version]: [0X00030002]\n[ro.vulkan.version]: [0X00403007]\n",
            "",
        );
        assert_eq!(
            context.graphics_apis,
            vec![GraphicsApi::OpenGlEs, GraphicsApi::Vulkan]
        );
        assert!(context.evidence.iter().any(|value| value.contains("3.2")));
        assert!(context.evidence.iter().any(|value| value.contains("1.3.7")));
    }
}
