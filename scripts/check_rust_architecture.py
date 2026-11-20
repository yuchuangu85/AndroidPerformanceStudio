"""Check reviewed internal production edges and Rust source ownership; see rust-desktop/ARCHITECTURE.md."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

# Short names omit the common aps- prefix. The shell composes features, not engines.
NORMAL = {
    'app': 'adb ai analysis battery benchmark capture contracts cpu frame gpu json layout-inspector memory '
           'method-recording network perfetto platform source startup storage ui-foundation',
    'cli': 'adb ai analysis battery benchmark capture contracts cpu har-engine json layout-inspector '
           'memory method-recording perfetto platform startup',
    'analysis': 'contracts frame json',
    'adb': 'platform', 'ai': 'contracts json platform', 'battery': 'adb json platform',
    'benchmark': 'contracts json', 'capture': 'adb contracts platform', 'contracts': 'primitives',
    'cpu': 'simpleperf-engine json contracts', 'frame': '', 'frame-mcp': 'analysis contracts frame json',
    'gpu': 'adb contracts json perfetto platform',
    'har-engine': '', 'hprof-engine': 'primitives', 'json': '',
    'layout-inspector': 'adb contracts json platform protocol', 'memory': 'hprof-engine platform',
    'method-recording': '', 'network': 'contracts har-engine json platform',
    'perfetto': 'contracts frame platform', 'platform': '', 'primitives': '',
    'protocol': 'contracts primitives', 'simpleperf-engine': 'primitives',
    'source': 'contracts platform', 'startup': 'adb json perfetto platform', 'storage': 'contracts json source',
    'ui-foundation': '',
}
ALLOWED = {('aps-' + src, 'normal', 'aps-' + dst)
           for src, names in NORMAL.items() for dst in names.split()}
ALLOWED.add(('aps-ui-foundation', 'build', 'aps-json'))


def edge_errors(owner, dependencies, roots):
    problems = []
    for dep in dependencies:
        dst, kind = dep['name'], dep['kind'] or 'normal'  # name is not the import alias
        if kind == 'dev' or dst not in roots:
            continue
        if not dep.get('path') or Path(dep['path']).resolve() != roots[dst]:
            problems.append(f'{owner}: {dst} must refer to the workspace member')
        elif (owner, kind, dst) not in ALLOWED:
            problems.append(f'{owner} --{kind}--> {dst} is not an approved dependency')
    return problems


# Tokenize Rust strings and comments so snippets in documentation, raw test strings,
# etc. cannot masquerade as directives. Keep offsets for actionable diagnostics.
TOKEN = re.compile(r"""//[^\n]*|/\*|(?:br|cr|r)(?P<h>\#*)"(?s:.*?)"(?P=h)|
    (?:b|c)?"(?:\\[\s\S]|[^"\\])*"|'(?:\\[^\n]|[^'\\\n])'|
    \w+|[^\s]""", re.VERBOSE)
BLOCK = re.compile(r'/\*|\*/')
CANDIDATE = re.compile(r'\b(?:path|include(?:_str|_bytes)?)\b')


def lex(source):
    pos = 0
    while match := TOKEN.search(source, pos):
        token, pos = match[0], match.end()
        if token == '/*':
            depth = 1
            for delimiter in BLOCK.finditer(source, pos):
                depth += 1 if delimiter[0] == '/*' else -1
                if not depth:
                    pos = delimiter.end()
                    break
            else:
                return
        elif not token.startswith('//'):
            yield token, match.start()


def literal(token):
    if token.startswith('"') and token.endswith('"'):
        # Reject unrecognized escapes rather than silently treating them as a local path.
        try:
            return json.loads(token)
        except ValueError:
            return None
    if re.match(r'r#*"', token):
        return token[token.index('"') + 1:token.rindex('"')]
    return None


def source_errors(file, crate, source):
    crate = crate.resolve()
    if not CANDIDATE.search(source):
        return []
    pairs = list(lex(source))
    tokens = [token for token, _ in pairs]
    problems = []
    for i, (token, offset) in enumerate(pairs):
        if token == 'path' and tokens[i + 1:i + 2] == ['=']:
            # #[path=...] or #[cfg_attr(..., path=...)].
            is_attr = tokens[max(0, i - 2):i] == ['#', '[']
            if not is_attr and i and tokens[i - 1] == ',':
                j = i - 1
                while j > 0 and tokens[j] not in ('[', ']'):
                    j -= 1
                is_attr = tokens[j:j + 2] == ['[', 'cfg_attr']
            if not is_attr:
                continue
            start, ends = i + 2, (']', ')', ',')
        elif token in ('include', 'include_str', 'include_bytes') and (
            tokens[i + 1:i + 2] == ['!'] and tokens[i + 2:i + 3] in (['('], ['['], ['{'])
        ):
            start, ends = i + 3, (')', ']', '}', ',')
        else:
            continue
        base = file.parent
        path = literal(tokens[start]) if start < len(tokens) else None
        if path is not None and (start + 1 >= len(tokens) or tokens[start + 1] not in ends):
            path = None
        # Common Rust patterns: generated output and known manifest-relative input.
        if tokens[start:start + 6] == ['concat', '!', '(', 'env', '!', '(']:
            args = tokens[start + 6:start + 12]
            if len(args) == 6 and args[1:3] == [')', ','] and args[4] == ')' and args[5] in ends:
                suffix = literal(args[3])
                if suffix:
                    suffix = suffix.replace(chr(92), '/')
                if suffix and suffix.startswith('/') and not suffix.startswith('//'):
                    if literal(args[0]) == 'CARGO_MANIFEST_DIR':
                        base, path = crate, suffix[1:]
                    elif literal(args[0]) == 'OUT_DIR' and '..' not in Path(suffix).parts:
                        continue
        reason = None
        if path is None:
            reason = 'unsupported include/path expression; make its owner explicit'
        else:
            target = (base / path.replace(chr(92), '/')).resolve()
            if not target.is_relative_to(crate) and (
                token in ('include', 'path') or target.suffix == '.rs'
            ):
                reason = f'{token} imports source outside its crate: {path}'
        if reason:
            line = source.count('\n', 0, offset) + 1
            problems.append(f'{file}:{line}: {reason}')
    return problems


def check(workspace):
    command = ['cargo', 'metadata', '--offline', '--locked', '--no-deps', '--format-version=1']
    metadata = json.loads(subprocess.check_output(command, cwd=workspace))
    members = set(metadata['workspace_members'])
    packages = [p for p in metadata['packages'] if p['id'] in members]
    roots = {p['name']: Path(p['manifest_path']).resolve().parent for p in packages}
    problems = []
    for pkg in packages:
        name, root = pkg['name'], roots[pkg['name']]
        if not name.startswith('aps-') or name[4:] not in NORMAL:
            problems.append(f'{name}: workspace member is not classified in the architecture policy')
        problems.extend(edge_errors(name, pkg['dependencies'], roots))
        for target in pkg['targets']:
            if not Path(target['src_path']).resolve().is_relative_to(root):
                problems.append(f"{name}: Cargo target imports source outside crate: {target['src_path']}")
        for file in sorted(root.rglob('*.rs')):
            if not file.resolve().is_relative_to(root):
                problems.append(f'{file}: source symlink points outside crate')
            else:
                problems.extend(source_errors(file, root, file.read_text(encoding='utf-8')))
    return problems


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path,
                        default=Path(__file__).resolve().parents[1] / 'rust-desktop')
    problems = check(parser.parse_args().workspace)
    if problems:
        print('\n'.join(problems), file=sys.stderr)
        sys.exit(1)
    print('Rust architecture: approved production dependencies and source ownership.')
