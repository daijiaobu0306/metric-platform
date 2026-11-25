#!/usr/bin/env python3
"""Import-level architecture guard for the modular monolith (Python standard library only)."""
import argparse
from collections import Counter
import os
from pathlib import Path
import re
import subprocess
import sys

SOURCE = "backend/src/main/java/"
LEGACY_SOURCE = "src/main/java/"
BUSINESS = frozenset("alarm gongdan metric issue collect observe report audit config".split())
# Blank comments and literals while preserving newlines and offsets for diagnostics.
IGNORED = re.compile(r'/\*.*?\*/|//[^\n]*|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.S)
PACKAGE = re.compile(r'\bpackage\s+com\.metric\.([\w]+)(?:\.[\w]+)*\s*;')
IMPORT = re.compile(r'\bimport\s+(?:static\s+)?(com\.metric\.[\w.*]+)\s*;')


def parse(text):
    clean = IGNORED.sub(lambda m: re.sub(r'[^\n]', ' ', m.group()), text)
    package = PACKAGE.search(clean)
    module = package.group(1) if package else None
    return module, [(m.group(1), clean.count('\n', 0, m.start()) + 1) for m in IMPORT.finditer(clean)]


def classify(source, target):
    parts = target.split('.')
    destination = parts[2]
    if source is None or source == destination:
        return None
    if source == 'shared' and destination in BUSINESS:
        return 'ERROR', '公共层依赖业务模块', '将业务逻辑放回所属模块；shared 仅保留通用能力。'
    if 'controller' in parts[3:-1]:
        return 'ERROR', '跨模块引用 Controller', '改为调用目标模块的 Service。'
    if source in BUSINESS and destination in BUSINESS and 'repository' in parts[3:-1]:
        return 'WARN', '跨模块访问 Repository', '确认用途；写操作优先通过目标模块 Service，避免绕过校验、状态流转和审计。'
    return None


def git(*args, check=True):
    result = subprocess.run(['git', *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace').strip())
    return result


def git_text(ref, path):
    result = git('show', f'{ref}:{path}', check=False)
    return result.stdout.decode('utf-8', errors='replace') if result.returncode == 0 else None


def java_paths(raw):
    return {p for p in raw.decode('utf-8').split('\0') if p.startswith(SOURCE) and p.endswith('.java')}


def main():
    parser = argparse.ArgumentParser(description='模块依赖检查：错误阻止提交，提醒不阻止。默认只检查相对 HEAD 新增的 import。')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--changed', action='store_true', help='检查工作区相对 HEAD 的新增依赖（含未跟踪文件，默认）')
    mode.add_argument('--staged', action='store_true', help='仅检查 Git 暂存区相对 HEAD 的新增依赖')
    mode.add_argument('--all', action='store_true', help='全量审计工作区，包含存量依赖')
    args = parser.parse_args()
    root = Path(git('rev-parse', '--show-toplevel').stdout.decode().strip())
    os.chdir(root)
    has_head = git('rev-parse', '--verify', 'HEAD', check=False).returncode == 0
    # Compare by exact import, not added diff lines: formatting changes are not new dependencies.
    head_paths = java_paths(git('ls-tree', '-r', '--name-only', '-z', 'HEAD').stdout) if has_head else set()
    # Include old layout only as comparison baseline during the src -> backend/src migration.
    old_paths = set()
    if has_head:
        old_paths = {p for p in git('ls-tree', '-r', '--name-only', '-z', 'HEAD').stdout.decode().split('\0')
                     if p.startswith(LEGACY_SOURCE) and p.endswith('.java')}
    renames = {}
    if not args.all and has_head:
        options = ['--cached'] if args.staged else []
        fields = git('diff', *options, '--name-status', '-z', '-M', 'HEAD').stdout.decode().split('\0')
        i = 0
        while i < len(fields) and fields[i]:
            status = fields[i]; i += 1
            if status.startswith(('R', 'C')):
                old, new = fields[i:i + 2]; i += 2
                renames[new] = old
            else:
                i += 1
    if args.staged:
        paths = java_paths(git('ls-files', '-z').stdout)
    else:
        paths = {p.as_posix() for p in Path(SOURCE).rglob('*.java')}
    totals = Counter()
    suppressed = 0
    for path in sorted(paths):
        text = git_text('', path) if args.staged else Path(path).read_text(encoding='utf-8')
        if text is None:
            continue
        source, imports = parse(text)
        baseline = set()
        if not args.all and has_head:
            previous = renames.get(path, path)
            legacy = path.replace(SOURCE, LEGACY_SOURCE, 1)
            if previous not in head_paths and legacy in old_paths:
                previous = legacy
            old_text = git_text('HEAD', previous)
            if old_text is not None:
                old_source, old_imports = parse(old_text)
                # A package move changes the dependency boundary, so re-evaluate its imports.
                if source == old_source:
                    baseline = {target for target, _ in old_imports}
        seen = set()
        for target, line in imports:
            if target in seen:
                continue
            seen.add(target)
            finding = classify(source, target)
            if not finding:
                continue
            if target in baseline:
                suppressed += 1
                continue
            level, title, advice = finding
            totals[level] += 1
            print(f'[{level}] {path}:{line} {title}\n  {target}\n  {advice}')
    print(f'检查完成：{totals["ERROR"]} 个错误，{totals["WARN"]} 个提醒。提醒不阻止提交。')
    if not args.all:
        print(f'已略过 {suppressed} 处存量依赖；使用 --all 查看全量结果。')
    return 1 if totals['ERROR'] else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (RuntimeError, OSError, UnicodeError) as exc:
        print(f'检查无法完成：{exc}', file=sys.stderr)
        sys.exit(2)
