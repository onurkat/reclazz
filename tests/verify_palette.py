"""Check theme contrast on flat surfaces and sampled heading gradients.
Run: python3 tests/verify_palette.py. Browser checks remain necessary for layout,
composited gradients, images, interaction states and actual rendered text.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
css = (ROOT / 'palette.css').read_text()
blocks = re.findall(r'(?:^|\n)(:root|html\[data-theme="light"\])\s*\{([^}]+)\}', css)
assert len(blocks) == 2
colors = {}
checks = []


def rgb(value):
    assert re.fullmatch(r'#[0-9a-f]{6}', value), value
    return tuple(int(value[i:i+2], 16) / 255 for i in (1, 3, 5))


def luminance(c):
    return sum((x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4) * w
               for x, w in zip(c, (.2126, .7152, .0722)))


def contrast(a, b):
    lo, hi = sorted((luminance(a), luminance(b)))
    return (hi + .05) / (lo + .05)


def verify(name, a, b, minimum=4.5):
    ratio = contrast(a, b)
    checks.append((name, ratio, minimum))


foregrounds = ['text', 'text-secondary', 'text-muted', 'text-dim', 'primary-glow',
               'accent', 'cyan', 'pink', 'orange', 'success', 'warning', 'error']
backgrounds = ['bg-dark', 'bg-elevated', 'bg-card', 'bg-card-hover', 'bg-subtle']
for selector, declarations in blocks:
    colors.update(dict(re.findall(r'--([\w-]+):\s*([^;]+);', declarations)))
    theme = 'dark' if selector == ':root' else 'light'
    for bg in backgrounds:
        for fg in foregrounds:
            verify(f'{theme}:{fg}/{bg}', rgb(colors[fg]), rgb(colors[bg]))
        verify(f'{theme}:control-border/{bg}', rgb(colors['control-border']), rgb(colors[bg]), 3)
        # Gradient headings are large; sample the interpolated colors as well as stops.
        a, b = rgb(colors['heading-start']), rgb(colors['heading-end'])
        for step in range(101):
            middle = tuple(x + (y - x) * step / 100 for x, y in zip(a, b))
            verify(f'{theme}:heading[{step}]/{bg}', middle, rgb(colors[bg]), 3)
    for bg in ['primary', 'primary-dark']:
        verify(f'{theme}:white/{bg}', (1, 1, 1), rgb(colors[bg]))
    # These badges use the original translucent decorative fills.
    for fg, fill in [('success', '#10b981'), ('warning', '#fbbf24'), ('error', '#f43f5e')]:
        tint = tuple(a * .15 + b * .85 for a, b in zip(rgb(fill), rgb(colors['bg-card'])))
        verify(f'{theme}:{fg}/badge', rgb(colors[fg]), tint)

for name in ['index.html', 'compare/index.html', 'license/index.html', 'privacy/index.html']:
    html = (ROOT / name).read_text()
    assert html.count('href="/palette.css"') == 1, name
    assert '--text:' not in html and '--accent:' not in html, 'Duplicate palette in ' + name

failures = [(name, ratio, minimum) for name, ratio, minimum in checks if ratio < minimum]
for name, ratio, minimum in failures:
    print(f'FAIL {name}: {ratio:.3f} < {minimum}')
assert not failures, f'{len(failures)} contrast failures'
print(f'PASS: {len(checks)} contrast checks, shared palette linked from all 4 pages')
print('Normal text >=4.5:1; large gradient text and control borders >=3:1')
