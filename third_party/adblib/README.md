# adblib

Source: https://github.com/tananaev/adblib
Pinned commit: `4b3d25641933b92b729ee67d17791992158d20be`
Copyright Cameron Gutman; BSD 3-clause license, bundled in assets/licenses/adblib.txt.

Used only for consented, on-device loopback Android TV remote keys. The adapter exposes
a fixed key allowlist; Gemini cannot issue shell commands or select a host. Keys are stored
in private app storage. No companion app or remote host. An explicitly consented connection expires after ten idle minutes.

Local changes: bounded packet lengths, stream-open wakeup race fix, bounded open wait,
and socket shutdown without an unbounded thread join.
