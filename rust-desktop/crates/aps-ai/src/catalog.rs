use crate::{AiError, bounded_text, identifier};
use http_client::Url;
use std::collections::BTreeMap;

#[derive(Clone, PartialEq, Eq)]
pub struct CredentialReference(String);

impl CredentialReference {
    pub fn new(reference: impl Into<String>) -> Result<Self, AiError> {
        let reference = reference.into();
        identifier(&reference)?;
        if reference.starts_with("sk-") || reference.starts_with("Bearer") {
            return Err(AiError::InvalidConfiguration);
        }
        Ok(Self(reference))
    }
    /// Opaque lookup ID, not a credential value or authorization.
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Metadata only. Secret values cannot be represented by this configuration type.
#[derive(Clone)]
pub struct ProviderConfig {
    id: String,
    endpoint: Url,
    model: String,
    credential: CredentialReference,
}

impl ProviderConfig {
    pub fn new(
        id: &str,
        base_url: &str,
        model: &str,
        credential: CredentialReference,
    ) -> Result<Self, AiError> {
        identifier(id)?;
        bounded_text(model, 256)?;
        if base_url.len() > 2048 {
            return Err(AiError::InvalidConfiguration);
        }
        let mut endpoint = Url::parse(base_url).map_err(|_| AiError::InvalidConfiguration)?;
        if !endpoint.username().is_empty()
            || endpoint.password().is_some()
            || endpoint.query().is_some()
            || endpoint.fragment().is_some()
        {
            return Err(AiError::InvalidConfiguration);
        }
        let local = matches!(
            endpoint.host_str(),
            Some("localhost" | "127.0.0.1" | "[::1]" | "::1")
        );
        if endpoint.scheme() != "https" && !(endpoint.scheme() == "http" && local) {
            return Err(AiError::InvalidConfiguration);
        }
        if endpoint.host_str().is_none() {
            return Err(AiError::InvalidConfiguration);
        }
        let path = endpoint.path().trim_end_matches('/');
        if !path.ends_with("/chat/completions") {
            endpoint.set_path(&format!("{path}/chat/completions"));
        }
        Ok(Self {
            id: id.into(),
            endpoint,
            model: model.into(),
            credential,
        })
    }
    pub fn id(&self) -> &str {
        &self.id
    }
    pub fn model(&self) -> &str {
        &self.model
    }
    pub fn endpoint(&self) -> &str {
        self.endpoint.as_str()
    }
    pub fn credential_reference(&self) -> &CredentialReference {
        &self.credential
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModelDescriptor {
    pub id: String,
    pub supports_streaming: bool,
    pub supports_json_schema: bool,
}

#[derive(Default)]
pub struct ProviderCatalog {
    providers: BTreeMap<String, ProviderConfig>,
    models: BTreeMap<String, Vec<ModelDescriptor>>,
}

impl ProviderCatalog {
    pub fn register(
        &mut self,
        provider: ProviderConfig,
        models: Vec<ModelDescriptor>,
    ) -> Result<(), AiError> {
        if self.providers.len() >= 64 || models.len() > 512 {
            return Err(AiError::InputLimit);
        }
        let mut seen = std::collections::BTreeSet::new();
        for model in &models {
            bounded_text(&model.id, 256)?;
            if !seen.insert(&model.id) {
                return Err(AiError::InvalidConfiguration);
            }
        }
        if self.providers.contains_key(provider.id()) {
            return Err(AiError::InvalidConfiguration);
        }
        self.models.insert(provider.id().into(), models);
        self.providers.insert(provider.id().into(), provider);
        Ok(())
    }
    pub fn provider(&self, id: &str) -> Option<&ProviderConfig> {
        self.providers.get(id)
    }
    pub fn models(&self, id: &str) -> &[ModelDescriptor] {
        self.models.get(id).map_or(&[], Vec::as_slice)
    }

    pub fn accepts(&self, provider_id: &str, model_id: &str, streaming: bool) -> bool {
        self.provider(provider_id).is_some()
            && self
                .models(provider_id)
                .iter()
                .any(|model| model.id == model_id && (!streaming || model.supports_streaming))
    }

    pub fn accepts_config(&self, config: &ProviderConfig, streaming: bool) -> bool {
        self.provider(config.id()).is_some_and(|registered| {
            registered.endpoint() == config.endpoint()
                && registered.credential_reference() == config.credential_reference()
                && self.accepts(config.id(), config.model(), streaming)
        })
    }
    /// Parse only sanitized OpenAI /models IDs; never keep raw provider metadata.
    pub fn parse_models(bytes: &[u8]) -> Result<Vec<ModelDescriptor>, AiError> {
        let root = aps_json::parse_slice(
            bytes,
            aps_json::Limits {
                max_bytes: 1024 * 1024,
                ..aps_json::Limits::default()
            },
        )
        .map_err(|_| AiError::InvalidResponse)?;
        let data = root
            .get("data")
            .and_then(aps_json::Value::as_array)
            .ok_or(AiError::InvalidResponse)?;
        if data.len() > 512 {
            return Err(AiError::ResponseLimit);
        }
        let mut models = Vec::new();
        let mut seen = std::collections::BTreeSet::new();
        for value in data {
            let id = value
                .get("id")
                .and_then(aps_json::Value::as_str)
                .ok_or(AiError::InvalidResponse)?;
            bounded_text(id, 256).map_err(|_| AiError::InvalidResponse)?;
            if !seen.insert(id) {
                return Err(AiError::InvalidResponse);
            }
            models.push(ModelDescriptor {
                id: id.into(),
                supports_streaming: false,
                supports_json_schema: false,
            });
        }
        // /models does not prove streaming or schema support. Host qualification
        // must set capabilities separately, never infer them from the model ID.
        Ok(models)
    }
}
