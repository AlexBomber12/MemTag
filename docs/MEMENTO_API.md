# Memento Cloud API (PR-03)

## Required settings
- Base URL: v1 endpoint without a trailing slash. Example: `https://mementoserver-hrd.appspot.com/v1`
- Token: Memento API token.
- Library ID: Memento library identifier.

## Sync behavior
- Manual only (Lookup screen -> "Sync now").
- Fetches the library schema first and builds a field-id map from field names (EPC required).
- Pages entries with `fields=all` and upserts into the local Room cache in batches.
- EPC values are normalized (trim, remove whitespace, uppercase) and validated as hex.
- Entries with missing/invalid EPC are skipped and included in the sync summary.
- Token is sent as the `token` query parameter for all API requests.

## Lookup behavior
- Lookup checks the local Room cache first.
- Offline lookup works after a successful sync.
- Invalid EPC input is rejected with a validation message.

## Error handling
- Missing base URL/token/library ID -> user-friendly message.
- 401/403 -> "Unauthorized. Check the Memento token."
- Network timeout -> "Network timeout while contacting Memento."
- Unexpected response format -> "Unexpected response format from Memento."
