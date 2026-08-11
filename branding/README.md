# Reclazz branding

One mark, used everywhere. `reclazz-mark.svg` is the master: a two-arrow
reload cycle on a rounded gradient tile, drawn in a 64x64 box and
180-degree rotationally symmetric about its centre. `reclazz-glyph.svg`
is the same paths without the tile, cropped to the glyph's bounds for
contexts that supply their own background.

Colours are the product's own ramp, violet `#7c3aed` to teal `#06d6a0`,
which is also what the website's gradient text uses.

## Where it is used

| Where | File | Form |
| --- | --- | --- |
| Marketplace / plugin listing | `src/main/resources/META-INF/pluginIcon.svg` | tile, 40px |
| Marketplace, dark IDE themes | `src/main/resources/META-INF/pluginIcon_dark.svg` | tile, 40px |
| IDE tool window, light themes | `src/main/resources/icons/reclazz.svg` | glyph, 16px, `#6d28d9` to `#059669` |
| IDE tool window, dark themes | `src/main/resources/icons/reclazz_dark.svg` | glyph, 16px, `#a78bfa` to `#34d399` |
| Website header and licence page | `onurkat.github.io/reclazz/index.html`, `license.html` | tile, inline SVG, 34px |
| Website favicon | `onurkat.github.io/reclazz/favicon.svg` | tile, 64px |
| iOS home screen | `onurkat.github.io/reclazz/apple-touch-icon.png` | 180px, square (iOS masks its own corners) |
| Social preview | `onurkat.github.io/reclazz/og-image.png` | 1200x630 card |
| This repository | `README.md` | tile, 88px |

## Changing it

Edit `reclazz-mark.svg`, then copy the paths into each file above. The
arc endpoints and the arrowhead triangles are computed to meet exactly:
each arc carries a round cap that its arrowhead covers, which is what
makes the join look seamless. Redrawing them by eye will not reproduce
that, so copy rather than retrace.

The mark and the name are trademarks and are not covered by the Apache
licence on the source. See `TRADEMARK.md`.
