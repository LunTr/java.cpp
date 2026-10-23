"""Typed fixture encoding shared by the pinned Python contract generators."""

import struct


def wire(value):
    if value is None:
        return b"n"
    if isinstance(value, bool):
        return b"t" if value else b"f"
    if isinstance(value, str):
        data = value.encode("utf-16-be", errors="surrogatepass")
        return b"s" + struct.pack(">i", len(data) // 2) + data
    if isinstance(value, int):
        return b"i" + struct.pack(">q", value)
    if isinstance(value, float):
        return b"d" + struct.pack(">d", value)
    if isinstance(value, (list, tuple)):
        return b"l" + struct.pack(">i", len(value)) + b"".join(wire(x) for x in value)
    if isinstance(value, dict):
        return b"m" + struct.pack(">i", len(value)) + b"".join(wire(k) + wire(v) for k, v in value.items())
    raise TypeError(type(value))

