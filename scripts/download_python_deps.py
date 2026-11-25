#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
下载 Python 依赖到本地目录（用于内网环境离线安装）

在有网络的机器上执行，将依赖包下载到当前目录的 wheelhouse/ 文件夹

使用方法:
    python download_python_deps.py

依赖:
    pip download pandas openpyxl pymysql python-dateutil
    pip download numpy (pandas 的依赖)
    pip download python-dateutil pytz (python-dateutil 的依赖)
"""

import subprocess
import os
import shutil

# 需要下载的包及其依赖
PACKAGES = [
    'pandas',
    'openpyxl',
    'pymysql',
    'python-dateutil',
    'numpy',
    'pytz',
    'six',
    'et-xmlfile',
    'packaging',
]

def download_packages():
    wheelhouse = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'deploy', 'python', 'wheelhouse')
    os.makedirs(wheelhouse, exist_ok=True)

    # 先下载所有包到 wheelhouse
    print(f"下载包到 {wheelhouse}/ ...")
    for pkg in PACKAGES:
        print(f"  下载 {pkg}...")
        subprocess.run([
            'pip', 'download', '--no-deps', '-d', wheelhouse, pkg
        ], check=False)

    # 下载所有包的依赖
    print("\n下载所有依赖...")
    subprocess.run([
        'pip', 'download', '-d', wheelhouse, '-r', '/dev/stdin'
    ], input='\n'.join(PACKAGES).encode(), check=False)

    print(f"\n下载完成！wheelhouse/ 目录内容:")
    for f in os.listdir(wheelhouse):
        print(f"  {f}")

if __name__ == '__main__':
    download_packages()
