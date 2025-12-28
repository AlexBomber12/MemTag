# Deep Links (PR-14)

## Find screen
- `memtag://find?epc=<EPC>`
- `memtag://find?epc=<EPC>&autoStart=true`
- `memtag://find?epc=<EPC>&autoStart=false`

Notes:
- `epc` should be hex only, 8-64 characters, no spaces.
- If `autoStart` is omitted, MemTag defaults to starting Find when a valid EPC is provided.
