# Deep Links

MemTag supports an external Find entry point for Memento buttons.

Supported formats:
- `memtag://find?epc=<EPC>`
- `memtag://find?epc=<EPC>&autoStart=true`

Notes:
- EPC must be hex with no spaces.
- `autoStart` defaults to true when an EPC is provided. Set `autoStart=false` to open Find without auto-starting.
