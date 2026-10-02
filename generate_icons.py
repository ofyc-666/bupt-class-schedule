#!/usr/bin/env python3
"""Generate Android launcher icons and adaptive icons from a source logo image.

Usage:
    python generate_icons.py [source_image]
    python generate_icons.py --source assets/logo_source.jpg
"""

import argparse
from pathlib import Path
from PIL import Image, ImageDraw

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_SOURCE = BASE_DIR / "assets" / "logo_source.jpg"
DEFAULT_RES_DIR = BASE_DIR / "app" / "src" / "main" / "res"
DEFAULT_ASSETS_DIR = BASE_DIR / "assets"

def parse_args():
    parser = argparse.ArgumentParser(description="Generate Android launcher icons from source image.")
    parser.add_argument(
        "source_pos",
        nargs="?",
        type=Path,
        help="Path to source logo image (optional positional argument)",
    )
    parser.add_argument(
        "-s", "--source",
        type=Path,
        default=None,
        help="Path to source logo image (default: assets/logo_source.jpg)",
    )
    parser.add_argument(
        "--res-dir",
        type=Path,
        default=DEFAULT_RES_DIR,
        help="Path to Android app res directory",
    )
    parser.add_argument(
        "--assets-dir",
        type=Path,
        default=DEFAULT_ASSETS_DIR,
        help="Path to project assets directory",
    )
    args = parser.parse_args()

    # Priority: explicit --source > positional source > default
    source_path = args.source or args.source_pos or DEFAULT_SOURCE
    return source_path, args.res_dir, args.assets_dir

def main():
    source_path, res_dir, assets_dir = parse_args()

    if not source_path.exists():
        raise FileNotFoundError(f"Source image not found at: {source_path}")

    print(f"Loading source image from: {source_path}")
    src = Image.open(source_path).convert("RGBA")

    # Extract tight 550x550 region centered in square:
    # White card is [261..763], crane is [240..780].
    # Leaves a sleek, narrow blue border around the white card (~24px in 550px).
    crop_size = int(min(src.width, src.height) * (550 / 1024))
    left = (src.width - crop_size) // 2
    top = (src.height - crop_size) // 2
    badge_crop = src.crop((left, top, left + crop_size, top + crop_size))
    bw, bh = badge_crop.size

    # Render antialiased rounded rectangle mask at 4x resolution
    scale = 4
    mask_hi = Image.new("L", (bw * scale, bh * scale), 0)
    draw_hi = ImageDraw.Draw(mask_hi)
    r_hi = int(bw * 0.193 * scale)  # ~106px for 550px width
    draw_hi.rounded_rectangle([0, 0, bw * scale - 1, bh * scale - 1], radius=r_hi, fill=255)
    badge_mask = mask_hi.resize((bw, bh), Image.Resampling.LANCZOS)

    squircle_badge = badge_crop.copy()
    squircle_badge.putalpha(badge_mask)

    # Circular mask for legacy round launcher
    round_mask_hi = Image.new("L", (bw * scale, bh * scale), 0)
    draw_round_hi = ImageDraw.Draw(round_mask_hi)
    draw_round_hi.ellipse([0, 0, bw * scale - 1, bh * scale - 1], fill=255)
    round_mask = round_mask_hi.resize((bw, bh), Image.Resampling.LANCZOS)

    round_badge = badge_crop.copy()
    round_badge.putalpha(round_mask)

    # Save high-res 512x512 logo in assets
    assets_dir.mkdir(parents=True, exist_ok=True)
    logo_512 = squircle_badge.resize((512, 512), Image.Resampling.LANCZOS)
    logo_path = assets_dir / "logo.png"
    logo_512.save(logo_path, "PNG")
    print(f"Saved: {logo_path}")

    densities = {
        "mipmap-mdpi": {"legacy": 48, "adaptive": 108},
        "mipmap-hdpi": {"legacy": 72, "adaptive": 162},
        "mipmap-xhdpi": {"legacy": 96, "adaptive": 216},
        "mipmap-xxhdpi": {"legacy": 144, "adaptive": 324},
        "mipmap-xxxhdpi": {"legacy": 192, "adaptive": 432},
    }

    for folder, sizes in densities.items():
        folder_path = res_dir / folder
        folder_path.mkdir(parents=True, exist_ok=True)

        legacy_size = sizes["legacy"]
        adaptive_size = sizes["adaptive"]

        # 1. Legacy ic_launcher.png (maximized subject area 46/48)
        inner_badge_size = int(legacy_size * 46 / 48)
        offset = (legacy_size - inner_badge_size) // 2

        legacy_img = Image.new("RGBA", (legacy_size, legacy_size), (0, 0, 0, 0))
        badge_resized = squircle_badge.resize((inner_badge_size, inner_badge_size), Image.Resampling.LANCZOS)
        legacy_img.paste(badge_resized, (offset, offset), badge_resized)
        legacy_img.save(folder_path / "ic_launcher.png", "PNG")

        # 2. Legacy ic_launcher_round.png
        legacy_round_img = Image.new("RGBA", (legacy_size, legacy_size), (0, 0, 0, 0))
        round_resized = round_badge.resize((inner_badge_size, inner_badge_size), Image.Resampling.LANCZOS)
        legacy_round_img.paste(round_resized, (offset, offset), round_resized)
        legacy_round_img.save(folder_path / "ic_launcher_round.png", "PNG")

        # 3. Adaptive foreground: ic_launcher_foreground.png
        # Size subject to ~80dp out of 108dp (80/108 ratio), leaving only ~4dp border under system mask
        fg_subject_size = int(adaptive_size * 80 / 108)
        fg_offset = (adaptive_size - fg_subject_size) // 2
        fg_resized = Image.new("RGBA", (adaptive_size, adaptive_size), (0, 0, 0, 0))
        subject_resized = squircle_badge.resize((fg_subject_size, fg_subject_size), Image.Resampling.LANCZOS)
        fg_resized.paste(subject_resized, (fg_offset, fg_offset), subject_resized)
        fg_resized.save(folder_path / "ic_launcher_foreground.png", "PNG")

        # 4. Adaptive background: ic_launcher_background.png (vertical blue gradient)
        bg_resized = Image.new("RGBA", (adaptive_size, adaptive_size), (0, 0, 0, 0))
        for y in range(adaptive_size):
            t = y / max(1.0, float(adaptive_size - 1))
            r = int(27 * (1 - t) + 39 * t)
            g = int(165 * (1 - t) + 65 * t)
            b = int(202 * (1 - t) + 139 * t)
            for x in range(adaptive_size):
                bg_resized.putpixel((x, y), (r, g, b, 255))
        bg_resized.save(folder_path / "ic_launcher_background.png", "PNG")

        print(f"Generated assets for {folder}: legacy {legacy_size}px, adaptive {adaptive_size}px (subject {fg_subject_size}px)")

if __name__ == "__main__":
    main()
