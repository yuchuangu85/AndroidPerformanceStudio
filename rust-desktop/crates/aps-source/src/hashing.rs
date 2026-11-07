use aps_contracts::SourceDigest;

/// SHA-256 over arbitrary bytes. The input is already in memory, so the digest
/// call cannot fail for the supported size.
pub fn digest_bytes(bytes: &[u8]) -> SourceDigest {
    SourceDigest::hash_reader(bytes, bytes.len() as u64)
        .expect("in-memory SHA-256 digest cannot fail")
}

/// SHA-256 over the UTF-8 bytes of the text (Compose String.sha256()).
pub fn digest_text(text: &str) -> SourceDigest {
    digest_bytes(text.as_bytes())
}
