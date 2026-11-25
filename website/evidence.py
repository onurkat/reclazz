"""Stable fingerprints of the source passages and test methods cited by feature claims."""
from functools import lru_cache
import hashlib
import re
from xml.parsers import expat


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def locate(text, quote):
    if not quote or "\n" in quote:
        raise ValueError("expected a single nonempty quoted line")
    lines = text.splitlines(keepends=True)
    found = [i for i, line in enumerate(lines) if line.strip() == quote.strip()]
    if not found:
        found = [i for i, line in enumerate(lines) for _ in range(line.count(quote))]
    if len(found) != 1:
        raise ValueError(f"quote has {len(found)} matches: {quote}")
    i = found[0]
    return lines, i, sum(map(len, lines[:i])) + lines[i].index(quote)


def markdown_section(lines, index):
    headings = []
    fence = None
    for i, line in enumerate(lines):
        delimiter = re.match(r"^ {0,3}(\x60{3,}|~{3,})(.*)", line)
        if delimiter:
            mark, tail = delimiter.groups()
            if fence is None:
                fence = mark
            elif mark.startswith(fence) and not tail.strip():
                fence = None
            continue
        heading = re.match(r"^ {0,3}(#{1,6})(?:\s|$)", line)
        if heading and fence is None:
            headings.append((i, len(heading[1])))
    start, depth = next(((i, h) for i, h in reversed(headings) if i <= index), (0, 0))
    end = next((i for i, h in headings if i > index and (depth == 0 or h <= depth)), len(lines))
    return "".join(lines[start:end]).rstrip("\n") + "\n"


@lru_cache(maxsize=256)
def java_mask(text):
    # Preserve offsets while hiding punctuation inside comments, chars, strings and text blocks.
    tokens = r'//[^\n]*|/\*[\s\S]*?\*/|"""(?:\\[\s\S]|(?!""")[\s\S])*"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\''
    return re.sub(tokens, lambda m: re.sub(r"[^\n]", " ", m[0]), text)


def java_body(text, mask, start, opening):
    depth = 1
    for match in re.finditer(r"[{}]", mask[opening + 1:]):
        depth += 1 if match[0] == "{" else -1
        if depth == 0:
            return text[start:opening + 2 + match.start()]
    raise ValueError("unclosed Java declaration")


def method_start(text, mask, position):
    # Walk backwards past annotations (including multiline arguments and array literals).
    # A preceding member's closing brace or semicolon is outside this method.
    parentheses = brackets = 0
    for i in range(position - 1, -1, -1):
        token = mask[i]
        if token == ")":
            parentheses += 1
        elif token == "(":
            parentheses -= 1
        elif token == "]":
            brackets += 1
        elif token == "[":
            brackets -= 1
        elif token in ";{}" and not parentheses and not brackets:
            start = i + 1
            break
    else:
        start = 0
    # A comment after the previous member on its closing line belongs to that member.
    trailing = re.match(r"[ \t]*(?://[^\n]*|/\*[^\n]*?\*/)[ \t]*\n", text[start:position])
    if trailing:
        start += trailing.end()
    while start < position and text[start].isspace():
        start += 1
    return start


@lru_cache(maxsize=2048)
def method_hash(text, name, declaring_class=None):
    mask = java_mask(text)
    start = 0
    if declaring_class:
        declaration = re.search(r"\b(?:class|interface|enum|record)\s+" + re.escape(declaring_class)
                                + r"\b[^{]*\{", mask)
        if not declaration:
            raise ValueError(f"missing declaring class {declaring_class}")
        start = declaration.end()
    pattern = r"\bvoid\s+" + re.escape(name) + r"\s*\("
    methods = []
    for candidate in re.finditer(pattern, mask):
        if declaring_class:
            prefix = mask[start:candidate.start()]
            if candidate.start() < start or prefix.count("{") != prefix.count("}"):
                continue
        methods.append(candidate)
    if len(methods) != 1:
        raise ValueError(f"expected one declaring test method {name}, found {len(methods)}")
    method = methods[0]
    opening = mask.find("{", method.end())
    if opening < 0 or ";" in mask[method.end():opening]:
        raise ValueError(f"missing method body for {name}")
    return digest(java_body(text, mask, method_start(text, mask, method.start()), opening))


def xml_block(text, pos):
    # Expat offsets refer to UTF-8 bytes. CDATA boundaries enclose literal HTML.
    data = text.encode()
    offset = len(text[:pos].encode())
    parser = expat.ParserCreate()
    stack, blocks, cdata = [], [], []

    def open_element(_name, _attrs):
        start = parser.CurrentByteIndex
        # A literal '>' in an attribute is valid. Find the actual end of the tag.
        tag = re.match(rb"""<(?:[^>"']|"[^"]*"|'[^']*')*>""", data[start:])
        stack.append((start, tag[0].endswith(b"/>")))

    def close(_name):
        begin, empty = stack.pop()
        end = parser.CurrentByteIndex
        if not empty:
            end = data.index(b">", end) + 1
        if begin <= offset < end:
            blocks.append(data[begin:end])

    parser.StartElementHandler = open_element
    parser.EndElementHandler = close
    parser.StartCdataSectionHandler = lambda: cdata.append(parser.CurrentByteIndex + 9)
    parser.EndCdataSectionHandler = lambda: cdata.append(parser.CurrentByteIndex)
    parser.Parse(data, True)
    for begin, end in zip(cdata[::2], cdata[1::2]):
        if begin <= offset < end:
            fragment = data[begin:end].decode()
            point = len(data[begin:offset].decode())
            opened = []
            for tag in re.finditer(r"<(/?)(li|p)\b[^>]*>", fragment, re.I):
                if not tag[1]:
                    opened.append((tag[2].lower(), tag.start()))
                elif opened and opened[-1][0] == tag[2].lower():
                    _, start = opened.pop()
                    if start <= point < tag.end():
                        return fragment[start:tag.end()]
            return fragment[fragment.rfind("\n", 0, point) + 1:].split("\n", 1)[0]
    if not blocks:
        raise ValueError("quote is outside XML elements")
    return blocks[0].decode()


@lru_cache(maxsize=2048)
def block_hash(path, text, quote):
    lines, index, pos = locate(text, quote)
    if path.endswith(".md"):
        block = markdown_section(lines, index)
    elif path.endswith(".xml"):
        block = xml_block(text, pos)
    elif path.endswith(".java"):
        mask = java_mask(text)
        start = pos - len(lines[index][:lines[index].index(quote)])
        opening = re.search(r"[{;]", mask[pos:])
        if not opening:
            raise ValueError("missing Java declaration")
        brace = pos + opening.start()
        block = text[start:brace + 1] if opening[0] == ";" else java_body(text, mask, start, brace)
    else:
        raise ValueError(f"unsupported source {path}")
    return digest(block)
