#!/usr/bin/env python3
"""按「可达性」裁剪 vite dist，只保留当前构建真正用到的静态资源。

背景：frontend/vite.config 里 emptyOutDir:false 是为了让发布前已打开的页面
还能加载旧 hash 的懒加载 chunk，代价是 dist/assets 会堆历次构建的产物。
直接整目录打进部署包会把几十 MB 死文件带上（实测 174 个 1.3MB 的旧 index-*.js）。

做法：从根目录 html 出发，递归收集文本里出现的 assets/<name> 引用，
不在闭包里的 assets 文件全部删除。vite 产物的 chunk 名都是字面量字符串，
所以闭包是完备的；不用猜、不用按时间排序。

用法: python3 prune-dist.py <dist_dir>
"""
import os
import re
import sys
from pathlib import Path

REF = re.compile(r'assets/[A-Za-z0-9._\-]+')
# CSS 里的字体/图片可能是相对引用（url(xxx.woff2)），补一条按文件名匹配，避免误删
NAME = re.compile(
    r'[A-Za-z0-9._\-]+\.(?:js|mjs|css|woff2?|ttf|eot|otf|png|jpe?g|gif|svg|webp|ico)'
)
TEXT_EXT = {'.html', '.js', '.mjs', '.css', '.json', '.svg'}


def main() -> int:
    if len(sys.argv) != 2:
        print('用法: prune-dist.py <dist_dir>', file=sys.stderr)
        return 2
    dist = Path(sys.argv[1])
    assets = dist / 'assets'
    if not assets.is_dir():
        print(f'[prune] 跳过：{assets} 不存在', file=sys.stderr)
        return 0

    queue = [p for p in dist.iterdir() if p.is_file() and p.suffix in ('.html', '.json')]
    if not queue:
        print('[prune] 警告：dist 根目录没有 html，跳过裁剪', file=sys.stderr)
        return 0

    keep = set()
    seen = set()
    while queue:
        f = queue.pop()
        if f in seen or not f.is_file():
            continue
        seen.add(f)
        if f.suffix not in TEXT_EXT:
            continue
        try:
            text = f.read_text(encoding='utf-8', errors='ignore')
        except OSError:
            continue
        for rel in REF.findall(text):
            target = dist / rel
            keep.add(target)
            if target.suffix in TEXT_EXT and target not in seen:
                queue.append(target)
        # 相对引用兜底：文本里出现的文件名，只要 assets 下同名存在就算被引用
        for name in NAME.findall(text):
            target = assets / name
            if target.is_file():
                keep.add(target)
                if target.suffix in TEXT_EXT and target not in seen:
                    queue.append(target)

    removed_files = 0
    removed_bytes = 0
    kept_bytes = 0
    for p in assets.rglob('*'):
        if not p.is_file():
            continue
        if p in keep:
            kept_bytes += p.stat().st_size
            continue
        removed_bytes += p.stat().st_size
        removed_files += 1
        p.unlink()

    kept = sum(1 for p in assets.rglob('*') if p.is_file())
    print(f'[prune] assets 保留 {kept} 个 / {kept_bytes / 1048576:.1f}MB，'
          f'删除 {removed_files} 个死文件 / {removed_bytes / 1048576:.1f}MB')
    return 0


if __name__ == '__main__':
    sys.exit(main())
