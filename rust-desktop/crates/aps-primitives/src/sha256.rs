//! Compile-time SHA-256 selection; both backends retain owned, cloneable state.

#[cfg(any(not(target_os = "macos"), test))]
#[path = "sha256_portable.rs"]
mod portable;

#[cfg(not(target_os = "macos"))]
pub use portable::Sha256;

#[cfg(target_os = "macos")]
pub use commoncrypto::Sha256;

#[cfg(target_os = "macos")]
mod commoncrypto {
    use std::ffi::{c_int, c_void};

    // CommonCrypto/CommonDigest.h: CC_LONG is uint32_t. The public context
    // contains only these arrays, so cloning copies all incremental state.
    #[derive(Clone, Default)]
    #[repr(C)]
    struct Context {
        count: [u32; 2],
        hash: [u32; 8],
        wbuf: [u32; 16],
    }

    const _: () = {
        assert!(std::mem::size_of::<Context>() == 104);
        assert!(std::mem::align_of::<Context>() == 4);
        assert!(std::mem::offset_of!(Context, count) == 0);
        assert!(std::mem::offset_of!(Context, hash) == 8);
        assert!(std::mem::offset_of!(Context, wbuf) == 40);
    };

    // These APIs are available since macOS 10.4 and exported by libSystem.
    // CommonDigest.h guarantees Init/Update/Final always return 1; no runtime
    // fallback or partial-success path is needed.
    #[link(name = "System")]
    unsafe extern "C" {
        fn CC_SHA256_Init(context: *mut Context) -> c_int;
        fn CC_SHA256_Update(context: *mut Context, data: *const c_void, length: u32) -> c_int;
        fn CC_SHA256_Final(digest: *mut u8, context: *mut Context) -> c_int;
    }

    #[derive(Clone)]
    pub struct Sha256 {
        context: Context,
        length: u64,
    }

    impl Sha256 {
        pub fn new() -> Self {
            let mut context = Context::default();
            // SAFETY: context is initialized, aligned, writable storage with
            // the SDK's CC_SHA256_CTX layout; Init does not retain its pointer.
            unsafe { CC_SHA256_Init(&mut context) };
            Self { context, length: 0 }
        }

        pub fn update(&mut self, bytes: &[u8]) {
            self.length = self
                .length
                .checked_add(bytes.len() as u64)
                .expect("SHA-256 input length overflow");
            // CC_LONG is 32-bit even on 64-bit targets. Split before casting
            // so a large caller slice cannot silently hash only a prefix.
            for chunk in bytes.chunks(u32::MAX as usize) {
                // SAFETY: self owns the initialized context exclusively;
                // chunk is readable for this exact u32-sized length. The API
                // consumes the bytes synchronously and retains no pointers.
                unsafe {
                    CC_SHA256_Update(&mut self.context, chunk.as_ptr().cast(), chunk.len() as u32)
                };
            }
        }

        pub fn finish(mut self) -> [u8; 32] {
            let _bit_length = self
                .length
                .checked_mul(8)
                .expect("SHA-256 input bit-length overflow");
            let mut digest = [0; 32];
            // SAFETY: digest has CC_SHA256_DIGEST_LENGTH writable bytes;
            // the initialized context is owned and consumed by this call.
            unsafe { CC_SHA256_Final(digest.as_mut_ptr(), &mut self.context) };
            digest
        }
    }
}

impl Default for Sha256 {
    fn default() -> Self {
        Self::new()
    }
}

/// Hashes an arbitrary reader with a hard
/// content-byte budget. On reaching the cap, reads at most one additional byte
/// to distinguish exact EOF from an oversized stream; never returns a prefix
/// digest. Reader errors and interruptions are handled without partial success.
/// This bounds input consumed, not wall time or a custom reader's internal work.
pub fn sha256_reader(mut reader: impl std::io::Read, max_bytes: u64) -> Result<String, String> {
    if max_bytes > u64::MAX / 8 {
        return Err("SHA-256 input limit exceeds supported bit-length range".into());
    }
    let mut hash = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    let mut remaining = max_bytes;
    loop {
        if remaining == 0 {
            loop {
                match reader.read(&mut buffer[..1]) {
                    Ok(0) => return Ok(hex(&hash.finish())),
                    Ok(_) => return Err("SHA-256 source exceeds input byte limit".into()),
                    Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
                    Err(_) => return Err("Cannot read SHA-256 source EOF".into()),
                }
            }
        }
        let limit = remaining.min(buffer.len() as u64) as usize;
        match reader.read(&mut buffer[..limit]) {
            Ok(0) => return Ok(hex(&hash.finish())),
            Ok(count) => {
                hash.update(&buffer[..count]);
                remaining -= count as u64;
            }
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => return Err("Cannot read SHA-256 source".into()),
        }
    }
}

pub fn hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut result = String::with_capacity(bytes.len() * 2);
    for &byte in bytes {
        result.push(HEX[(byte >> 4) as usize] as char);
        result.push(HEX[(byte & 0x0f) as usize] as char);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::{portable::Sha256 as PortableSha256, Sha256};

    #[test]
    fn portable_known_vectors_include_exact_and_multiple_blocks() {
        for (input, expected) in [
            (
                b"".as_slice(),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ),
            (
                b"abc".as_slice(),
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ),
            (
                &[b'a'; 64],
                "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb",
            ),
            (
                b"abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno\
                  ijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu",
                "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
            ),
        ] {
            for chunk_size in [1, 3, 55, 56, 63, 64, 65, 128] {
                let mut portable = PortableSha256::new();
                let mut selected = Sha256::new();
                portable.update(&[]);
                selected.update(&[]);
                for chunk in input.chunks(chunk_size) {
                    portable.update(chunk);
                    selected.update(chunk);
                }
                let digest = portable.finish();
                assert_eq!(super::hex(&digest), expected);
                assert_eq!(selected.finish(), digest);
            }
        }
        let mut portable = PortableSha256::new();
        for _ in 0..1000 {
            portable.update(&[b'a'; 1000]);
        }
        assert_eq!(
            super::hex(&portable.finish()),
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
        );
    }

    #[test]
    fn selected_and_portable_backends_match_chunked_data_and_cloned_contexts() {
        let data: Vec<_> = (0..131_201).map(|index| (index % 251) as u8).collect();
        for length in [0, 1, 55, 56, 63, 64, 65, 127, 128, 129, 65_535, 131_201] {
            for chunk_size in [1, 3, 55, 56, 63, 64, 65, 4096, 65_536, 131_072] {
                let mut selected = Sha256::new();
                let mut portable = PortableSha256::new();
                for chunk in data[..length].chunks(chunk_size) {
                    selected.update(chunk);
                    portable.update(chunk);
                }
                selected.update(&[]);
                portable.update(&[]);
                assert_eq!(selected.clone().finish(), portable.clone().finish());

                let mut branch = selected.clone();
                let mut portable_branch = portable.clone();
                branch.update(b"independent suffix");
                portable_branch.update(b"independent suffix");
                assert_eq!(branch.finish(), portable_branch.finish());

                selected.update(&data[length..]);
                portable.update(&data[length..]);
                assert_eq!(selected.finish(), portable.finish());
            }
        }
    }

    #[test]
    #[ignore = "set APS_HPROF_SHA256_BENCHMARK to a local file and run in release mode"]
    fn benchmark_file_hash_backends() {
        use std::hint::black_box;
        use std::time::Instant;

        let path = std::env::var_os("APS_HPROF_SHA256_BENCHMARK")
            .expect("set APS_HPROF_SHA256_BENCHMARK to the input path");
        let data = std::fs::read(path).unwrap();
        let mut selected_samples = Vec::new();
        let mut portable_samples = Vec::new();
        for iteration in 0..9 {
            let mut run = |portable: bool| {
                let started = Instant::now();
                let digest = if portable {
                    let mut hash = PortableSha256::new();
                    for chunk in black_box(&data).chunks(128 * 1024) {
                        hash.update(chunk);
                    }
                    hash.finish()
                } else {
                    let mut hash = Sha256::new();
                    for chunk in black_box(&data).chunks(128 * 1024) {
                        hash.update(chunk);
                    }
                    hash.finish()
                };
                let elapsed = started.elapsed().as_micros();
                if iteration >= 2 {
                    if portable {
                        portable_samples.push(elapsed);
                    } else {
                        selected_samples.push(elapsed);
                    }
                }
                black_box(digest)
            };
            let first = run(iteration % 2 == 0);
            let second = run(iteration % 2 != 0);
            assert_eq!(first, second);
        }
        selected_samples.sort_unstable();
        portable_samples.sort_unstable();
        println!(
            "hash-only benchmark: bytes={} selected_us={selected_samples:?} portable_us={portable_samples:?}",
            data.len()
        );
    }
}
