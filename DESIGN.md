# Reclazz website design

The site is plain HTML/CSS/JavaScript served from GitHub Pages. No framework,
Vercel service, animation dependency or generated imagery is required.

## Identity and colors
Use `palette.css` as the only source of theme color tokens. The four HTML pages
link it after their page styles. Keep the reload mark and purple/teal identity.
Primary actions use a solid purple fill and white text. Heading accents use two
theme-aware purple/teal stops; do not reuse bright dark-theme text on white.
Use success, warning and error tokens with a label or symbol, not color alone.
Decorative logo fills, terminal dots and low-opacity background glows are not
text colors. Keep semantic foregrounds separate from decorative fills.

Normal text targets at least 4.5:1; large headings and control boundaries target
3:1. Check hover/focus and tinted badges as well as flat cards. Muted text still
needs to be legible. Noninteractive card dividers may be subtle; controls need
a clear boundary and visible keyboard focus. Do not treat token checks as full
WCAG certification; rendered backgrounds, images and assistive technology need
separate review. Reference: https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html

## Typography and layout
Poppins for headings, Inter for reading, JetBrains Mono for code. Preserve the
existing content hierarchy and limit heading gradients to emphasis. Long code
must wrap in cards or scroll within its own code area. No clipped body text.
The homepage uses a compact navigation through 1200px; its closed links must
not receive focus. Touch controls have a minimum 44px hit area.

## Interaction
Use native links/buttons. Main content has a working skip link. Navigation
reports expanded state, closes on Escape and returns focus. FAQ answers use
intrinsic height, a stable aria-controls target and hidden when collapsed.
Switching languages must not duplicate decorative content. Respect reduced
motion across the entire page; no endless decorative pulse. Keep transitions
limited to explicit properties.

## Verification before a site commit
Run `python3 tests/verify_palette.py` (Python standard library only).
Serve locally and use browser/Playwright checks for:
- English and Turkish at 320, 390, 768, 1024, 1200, 1280 and 1440px.
- Document overflow AND clipped child bounds, especially hero and CTA links.
- Closed/open menu keyboard order, Escape, anchor navigation and resize.
- All ten FAQ answers, including long Turkish text and repeated language changes.
- Light/dark on home, compare, license and privacy; reduced-motion preference.
- Focus visibility, console errors, screenshots and local asset/anchor validity.

Reference checklists: https://vercel.com/design/guidelines and
https://playwright.dev/docs/intro . These are references, not hosting choices.
