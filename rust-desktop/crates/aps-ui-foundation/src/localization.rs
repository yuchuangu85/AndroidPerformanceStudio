use crate::Language;
use std::fmt::Display;

include!(concat!(env!("OUT_DIR"), "/strings.rs"));

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FormatError {
    ArgumentCount,
    ArgumentNames,
    InvalidTemplate,
    ArgumentFormatting,
}

impl Language {
    pub fn locale(self) -> &'static str {
        match self.resolved() {
            Self::System | Self::English => "en",
            Self::SimplifiedChinese => "zh-CN",
        }
    }

    /// Formats application-owned templates. Invalid calls retain the raw template.
    pub fn format(self, key: TextKey, args: &[(&str, &dyn Display)]) -> String {
        self.try_format(key, args).unwrap_or_else(|error| {
            eprintln!("Invalid localized message {}: {error:?}", key.as_str());
            self.text(key).to_owned()
        })
    }

    pub fn try_format(
        self,
        key: TextKey,
        args: &[(&str, &dyn Display)],
    ) -> Result<String, FormatError> {
        let parameters = key.parameters();
        if parameters.len() != args.len() {
            return Err(FormatError::ArgumentCount);
        }
        for name in parameters {
            if args.iter().filter(|(arg, _)| arg == name).count() != 1 {
                return Err(FormatError::ArgumentNames);
            }
        }
        let mut output = String::new();
        let mut remaining = self.text(key);
        while let Some(start) = remaining.find('{') {
            output.push_str(&remaining[..start]);
            let end = remaining[start..]
                .find('}')
                .ok_or(FormatError::InvalidTemplate)?
                + start;
            let name = &remaining[start + 1..end];
            let value = args
                .iter()
                .find(|(arg, _)| *arg == name)
                .ok_or(FormatError::ArgumentNames)?
                .1;
            use std::fmt::Write;
            write!(output, "{value}").map_err(|_| FormatError::ArgumentFormatting)?;
            remaining = &remaining[end + 1..];
        }
        output.push_str(remaining);
        Ok(output)
    }
}
