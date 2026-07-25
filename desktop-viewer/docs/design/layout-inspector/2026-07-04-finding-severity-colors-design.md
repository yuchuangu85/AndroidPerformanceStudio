# Finding Severity Colors Design

Each FINDINGS row inherits the same severity color used by its summary badge:

- INFO: blue `#4BA3FF`
- WARNING: orange `#F5A524`
- ERROR: red `#EF5350`

The presenter exposes a UI-specific finding tone, keeping Compose independent
from the analysis engine enum. Typography and spacing remain unchanged.

