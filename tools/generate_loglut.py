#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

WDR_GAMMA = 0.35
DISPLAY_GAMMA = 2.2
DEFAULT_SIZE = 33
DEFAULT_OUTPUT = Path("app/src/main/assets/luts/loglut.cube")


def restore_wdr_to_display_channel(value: float) -> float:
    value = max(0.0, min(1.0, value))
    linear = value ** (1.0 / WDR_GAMMA)
    return linear ** (1.0 / DISPLAY_GAMMA)


def generate_cube(size: int) -> str:
    lines: list[str] = [
        'TITLE "loglut"',
        '# Restore LUT for CameraManager WDR tonemap curve',
        f'# WDR curve in app: wdr = linear^{WDR_GAMMA}',
        f'# This LUT maps WDR output back to standard display gamma ({DISPLAY_GAMMA})',
        f'# Effective output: display = wdr^{(1.0 / DISPLAY_GAMMA) / WDR_GAMMA}',
        'DOMAIN_MIN 0.0 0.0 0.0',
        'DOMAIN_MAX 1.0 1.0 1.0',
        f'LUT_3D_SIZE {size}',
    ]

    scale = size - 1
    for b in range(size):
        bf = b / scale
        bout = restore_wdr_to_display_channel(bf)
        for g in range(size):
            gf = g / scale
            gout = restore_wdr_to_display_channel(gf)
            for r in range(size):
                rf = r / scale
                rout = restore_wdr_to_display_channel(rf)
                lines.append(f"{rout:.8f} {gout:.8f} {bout:.8f}")

    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate restore LUT for the app WDR tonemap curve.")
    parser.add_argument("--size", type=int, default=DEFAULT_SIZE, help="Cube edge size (default: 33)")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="Output .cube path")
    args = parser.parse_args()

    if args.size < 2:
        raise SystemExit("LUT size must be >= 2")

    cube_text = generate_cube(args.size)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(cube_text, encoding="utf-8")
    print(f"Wrote {args.output} ({args.size}^3 entries)")


if __name__ == "__main__":
    main()
