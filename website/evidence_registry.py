"""Read and refresh the live parts of the feature registries. Run records are immutable."""
from functools import lru_cache
import json
from pathlib import Path
import re
from tempfile import NamedTemporaryFile

from website.evidence import block_hash, java_mask, method_hash

HUMAN = "docs/feature-evidence.md"
HINT = "run ./gradlew :agent:refreshEvidence if the change keeps the claim true"


class RegistrySources:
    def __init__(self, root):
        self.root = root.resolve()
        self.texts = {}

    def read(self, name):
        if name not in self.texts:
            path = (self.root / name).resolve()
            if not path.is_relative_to(self.root):
                raise ValueError(f"source outside repository: {name}")
            self.texts[name] = path.read_text(encoding="utf-8")
        return self.texts[name]

    def surface(self, item):
        return block_hash(item["path"], self.read(item["path"]), item["quote"])

    def method(self, test):
        decl = test.get("declaration", test)
        owner, name = test["symbol"].split("#")
        parent, declared = decl["symbol"].split("#")
        if name != declared:
            raise ValueError(f"declaring method differs: {decl['symbol']}")
        for typ, item in ((owner, test), (parent, decl)):
            if not item["path"].endswith("/" + typ.replace(".", "/") + ".java"):
                raise ValueError(f"wrong source for {typ}: {item['path']}")
        if owner != parent:
            self.check_ancestor(owner, parent, test["path"])
        return method_hash(self.read(decl["path"]), name, parent.rsplit(".", 1)[-1])

    @lru_cache(maxsize=256)
    def check_ancestor(self, owner, parent, path):
        # The Java completeness checks independently walk compiled ASM method owners.
        seen = set()
        while owner not in seen:
            seen.add(owner)
            source = java_mask(self.read(path))
            package, _, simple = owner.rpartition(".")
            match = re.search(r"\bclass\s+" + re.escape(simple) + r"\s+extends\s+([\w.]+)", source)
            if not match:
                break
            base = match[1]
            if "." not in base:
                imports = re.findall(r"\bimport\s+([\w.]+\." + re.escape(base) + r")\s*;", source)
                base = imports[0] if imports else package + "." + base
            if base == parent:
                return
            candidates = list((self.root / "agent/src").glob("*/java/" + base.replace(".", "/") + ".java"))
            if len(candidates) != 1:
                break
            owner, path = base, str(candidates[0].relative_to(self.root))
        raise ValueError(f"{parent} does not declare an inherited method of {path}")


def references(claim):
    parts = []
    for item in claim["shipped_surfaces"]:
        if item["path"] == HUMAN:
            parts.append("this block (digest in JSON)")
        else:
            parts.append(f"\x60{item['path']}\x60 (block SHA-256 \x60{item['block_sha256']}\x60)")
    return "**Shipped surfaces:** " + "; ".join(parts) + "."


def render_human(source, claims):
    seen = set()
    def render(match):
        name, body = match.group(1), match.group(2)
        if name not in claims:
            return match[0]
        if name in seen:
            raise ValueError(f"{name}: duplicate human-readable heading")
        seen.add(name)
        reference = references(claims[name])
        lines = body.splitlines(keepends=True)
        slots = [i for i, line in enumerate(lines) if line.startswith("**Shipped surfaces:**")]
        if len(slots) > 1:
            raise ValueError(f"{name}: duplicate reference line")
        if slots:
            lines[slots[0]] = reference + "\n"
        else:
            lines = ["\n", reference + "\n"] + lines
        return "### " + name + "\n" + "".join(lines)
    result = re.sub(r"^### ([^\n]+)\n(.*?)(?=^#{1,3} |\Z)", render, source, flags=re.M | re.S)
    missing = claims.keys() - seen
    if missing:
        raise ValueError(f"missing claim headings: {', '.join(sorted(missing))}")
    return result


def registries(root):
    for path in sorted((root / "docs/feature-evidence").glob("*.json")):
        value = json.loads(path.read_bytes())
        if "claims" in value:
            yield path, value


def calculate(claim, sources):
    try:
        for item in claim["shipped_surfaces"]:
            item["block_sha256"] = sources.surface(item)
            item.pop("sha256", None)
            item.pop("line", None)
        for test in claim["tests"]:
            value = sources.method(test)
            for item in (test, test.get("declaration", test)):
                item["method_sha256"] = value
                item.pop("source_sha256", None)
    except (ValueError, OSError, KeyError) as error:
        raise ValueError(f"{claim['id']}: {error}; {HINT}") from error


def snapshot(root, registry):
    sources = RegistrySources(root)
    for claim in registry["claims"]:
        calculate(claim, sources)
    return registry["claims"]


def refresh(root):
    records = dict(registries(root))
    sources = RegistrySources(root)
    claims = {}
    for registry in records.values():
        for claim in registry["claims"]:
            if claim["id"] in claims:
                raise ValueError(f"duplicate claim {claim['id']}")
            calculate(claim, sources)
            claims[claim["id"]] = claim
    human = render_human(sources.read(HUMAN), claims)
    sources.texts[HUMAN] = human
    for claim in claims.values():
        for item in claim["shipped_surfaces"]:
            if item["path"] == HUMAN:
                item["block_sha256"] = sources.surface(item)
    outputs = {root / HUMAN: human}
    outputs.update({path: json.dumps(record, ensure_ascii=False, indent=2) + "\n"
                    for path, record in records.items()})
    changed = 0
    for path, text in outputs.items():
        data = text.encode()
        if path.read_bytes() != data:
            with NamedTemporaryFile(dir=path.parent, delete=False) as staged:
                staged.write(data)
                tmp = Path(staged.name)
            try:
                tmp.chmod(path.stat().st_mode)
                tmp.replace(path)
            finally:
                tmp.unlink(missing_ok=True)
            changed += 1
    return len(claims), changed
