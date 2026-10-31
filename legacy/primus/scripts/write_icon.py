"""Write a small Primus .ico without third-party imaging libraries."""

from __future__ import annotations

import struct
from pathlib import Path


def write_primus_icon(path: Path, size: int = 32) -> None:
    """Navy field with a cyan hexagon. Standard Windows ICO."""
    pixels = bytearray(size * size * 4)
    cx = cy = (size - 1) / 2.0
    radius = size * 0.36
    for y in range(size):
        for x in range(size):
            dx = x - cx
            dy = y - cy
            px = dx / radius
            py = dy / radius
            hex_dist = max(abs(px), abs(px * 0.5 + py * 0.866), abs(-px * 0.5 + py * 0.866))
            idx = (y * size + x) * 4
            if hex_dist < 1.0:
                glow = max(0.0, 1.0 - hex_dist)
                pixels[idx] = 0
                pixels[idx + 1] = int(140 + 80 * glow)
                pixels[idx + 2] = 255
                pixels[idx + 3] = 255
            else:
                pixels[idx] = 10
                pixels[idx + 1] = 14
                pixels[idx + 2] = 26
                pixels[idx + 3] = 255
    xor = bytearray()
    for y in range(size - 1, -1, -1):
        xor.extend(pixels[y * size * 4 : (y + 1) * size * 4])
    and_row = ((size + 31) // 32) * 4
    and_mask = bytes(and_row * size)
    dib = struct.pack(
        "<IiiHHIIiiII",
        40,
        size,
        size * 2,
        1,
        32,
        0,
        len(xor),
        0,
        0,
        0,
        0,
    )
    image = dib + xor + and_mask
    header = struct.pack("<HHH", 0, 1, 1)
    entry = struct.pack(
        "<BBBBHHII",
        size if size < 256 else 0,
        size if size < 256 else 0,
        0,
        0,
        1,
        32,
        len(image),
        6 + 16,
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(header + entry + image)


if __name__ == "__main__":
    write_primus_icon(Path(__file__).resolve().parents[1] / "assets" / "primus.ico")
