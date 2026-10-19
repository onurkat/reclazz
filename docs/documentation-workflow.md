# Documentation workflow

The canonical documentation lives in `main/docs`. The website is generated from
those sources; `gh-pages` is no longer an authoring location. README is the short
project introduction, with links to the site and this source index.

## Where to make a change

| Change | Source |
|---|---|
| Detailed behavior, setup, protocol or acceptance scope | The relevant Markdown file under `docs/` |
| Curated explanations and navigation | `docs/site/` |
| Reload outcome, condition, next action and scope | `docs/reload-support.md` |
| Site layout, shared styles or landing pages | `website/` |
| Build, coverage and publishing workflow | `scripts/*docs-site.py`, `requirements-docs.txt`, `.github/workflows/docs.yml` |

Every top-level `docs/*.md` file is listed in [the source index](index.md) and
rendered in the website’s reference library. The 13 task-oriented guides introduce
those references; they are maintained in the same source tree, not in a separate
website checkout. Agent options are extracted from `docs/usage.md`, not copied
into a separately authored JSON table. The interactive matrix and downloadable JSON are derived from `docs/reload-support.md`. Support records must retain their source
and condition, with missing evidence clearly distinguished from known refusal.

## Build and preview

Use Python 3.9+ in an isolated environment. The pinned dependencies are documentation
build tools only; they do not enter the agent, IDE plugin or Maven/Gradle artifacts.

```sh
python3 -m venv .venv-docs
.venv-docs/bin/python -m pip install -r requirements-docs.txt
.venv-docs/bin/python scripts/build-docs-site.py
.venv-docs/bin/python scripts/check-docs-site.py
.venv-docs/bin/python scripts/test-docs-site.py
python3 -m http.server 18765 --bind 127.0.0.1 --directory build/site
```

On Windows use `.venv-docs/Scripts/python.exe` instead of `.venv-docs/bin/python`.
Open `http://127.0.0.1:18765/docs/`. Check a complete reference page, its internal
links, tables and code examples; also check desktop/mobile, both themes, search
and keyboard navigation. Build output is ignored and must not be edited by hand.

```sh
.venv-docs/bin/python scripts/build-docs-site.py --check
```

This check rejects stale output when a source, option, asset or build input changed.
The build manifest records every canonical document’s digest and public route.
The reference library includes JSON evidence downloads; it does not certify that
those acceptance runs were repeated by a documentation build.

## Continuous integration and publication

The docs workflow builds and checks documentation-only changes separately from
the application test gate. Pull requests produce a site artifact for review and
never deploy. A successful main push can deploy the same checked artifact through
GitHub Pages Actions. Its GitHub environment and repository branch protections
remain the owner’s controls; this task does not change those settings.

Before the first production deployment, finish independent review/commit and set
**Settings → Pages → Build and deployment → Source → GitHub Actions**. The previous
branch-based site and custom domain stay in service until that switch and a
successful deployment. Do not delete a live branch as part of local migration.

The workflow retains `CNAME`, `.nojekyll` and all existing landing/privacy/license
assets in the built site. No separate branch synchronization or copying of docs
is needed after adoption. Do not use a generated `gh-pages` file as source.

## Version and evidence discipline

Keep capability evidence tied to its recorded source/version. Site generation
must not upgrade an unverified lane or a component-only test into a watcher
acceptance claim. Source presence does not prove artifact publication.

If a claim moves out of README, relocate its registry surface path/line/quote and
file digest; keep verdicts, observing tests and execution records unchanged. Run
the existing evidence, protocol and argument checks before handoff.

Full reference pages rewrite known Markdown links to site routes. Links to source
code, examples or non-rendered project files retain the source repository location.
Markdown HTML/XML examples belong in code fences. Literal angle-bracket prose is escaped; existing empty ID anchors remain linkable. Curated layout fragments under `docs/site/` retain their separate HTML authoring path.

Keep examples synthetic and remove private names, credentials and customer paths.

## One source per fact

- Installation actions and runnable setup snippets: `docs/installation.md` and `docs/maven.md`.
  Quickstart uses `{{INCLUDE docs/file.md#heading}}` to render the canonical section;
  do not paste its commands into an HTML fragment. Missing sections fail the build.
- Agent arguments/defaults: `docs/usage.md`. The product reference links here and
  the options page is generated from this table.
- Support outcomes/conditions/actions: `docs/reload-support.md`. Keep linked technical
  scope precise; these are bounded editorial summaries, not automatic certification
  of the runtime test registries. Do not independently author a support JSON file.
- Source version: `pluginVersion` in `gradle.properties`. Evidence commit and actual
  review date: `docs/site/manifest.json`. Reviewing docs does not rerun acceptance tests.
  The build revision is separately recorded; never silently advance recorded evidence.
- AI discovery: `llms.txt` is generated from the README introduction and page metadata;
  no independent feature claims or support matrix are authored there.

Use `{{REF docs/usage.md#heading}}` for guide reading links. These resolve to website
references; source-code links retain the selected build revision. The evidence footer
retains the explicit capability baseline. Fix relative Markdown links at their source
so GitHub/offline reading also works; website aliases are not a source-link repair.

The checker validates source anchors as well as rendered routes, evidence surface hashes,
and the absence of independently editable AI/support outputs. Search links directly to
sections. The large evidence registry remains available in full, but only its introduction
is indexed to avoid making every reader download thousands of provenance lines.

Maintainer publishing/workflow pages have their own navigation group. Full reference
URLs and heading anchors remain stable when navigation is reorganized. When changing a
heading, preserve a legacy empty ID anchor or update every canonical link to it.

## Shared public layout

`website/layout.py` owns the public header, footer, search dialog and shared head assets
for documentation, home, comparison, privacy and license pages. Public HTML under
`website/static/` keeps its content and metadata, using the `SITE_HEAD`, `SITE_HEADER`,
`SITE_FOOTER` and `SITE_UTILITIES` markers exactly once. The builder rejects bypasses.

Shared visual tokens remain in `website/static/docs/assets/docs.css`; `assets/site.css`
styles the public navigation/footer and `assets/marketing.css` styles public content.
`assets/site.js` enhances the native mobile navigation; `assets/home.js` owns the existing
homepage FAQ and English/Turkish copy. Theme and docs search are shared across every page.
The full license uses native details so it works with a keyboard and without JavaScript.

The site checker validates all public HTML pages, their assets and local anchors in
addition to the documentation contracts. After layout changes, check mobile navigation,
search, theme persistence between pages, language switching and horizontal table scrolling.
