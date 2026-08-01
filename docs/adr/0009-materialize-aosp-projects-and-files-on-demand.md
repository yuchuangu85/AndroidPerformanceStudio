# Materialize AOSP projects and files on demand

An AOSP source workspace presents a unified virtual tree while materializing only projects and files needed by search, navigation, or explicit user selection at a fixed revision. Cached content remains available offline and users may cache an entire related Git project, but the product does not default to a full `repo sync`. This keeps AOSP usable without imposing repository-scale storage and download costs.
