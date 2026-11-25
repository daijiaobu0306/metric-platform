# Python 依赖离线安装指南

## 背景

服务器处于内网环境，无法直接使用 `pip install` 安装依赖。需要：

1. 在有网络的机器上下载依赖包
2. 上传到服务器
3. 在服务器上离线安装

## 使用步骤

### 步骤 1：在有网络的机器上下载依赖

```bash
# Linux/Mac
chmod +x download_python_deps.sh
./download_python_deps.sh

# 或手动执行
pip download -d wheelhouse pandas openpyxl pymysql python-dateutil numpy
```

这会在 `wheelhouse/` 目录生成所有 `.whl` 文件。

### 步骤 2：上传到服务器

将 `wheelhouse/` 目录上传到服务器，例如：

```bash
scp -r wheelhouse/ user@server:/opt/metric-code/wheelhouse
```

### 步骤 3：在服务器上安装

```bash
chmod +x setup_python_env.sh
./setup_python_env.sh /opt/metric-code/wheelhouse
```

脚本会自动：
- 创建虚拟环境 `python_env/`
- 安装所有依赖包
- 验证安装结果

### 步骤 4：配置应用

在 `application.yml` 中配置 Python 路径：

```yaml
script:
  storage:
    path: /data/scripts
  raw-data:
    path: /data/raw_files
  python:
    path: /opt/metric-code/python_env/bin/python3
  execution:
    timeout: 60
```

## 目录说明

| 目录 | 说明 |
|------|------|
| `wheelhouse/` | 下载的 wheel 包目录，上传后保存在服务器 |
| `python_env/` | 虚拟环境目录，脚本自动创建 |

## 验证安装

```bash
source python_env/bin/activate
python -c "import pandas; import openpyxl; import pymysql; print('OK')"
```

## 常见问题

**Q: pip download 失败？**
A: 确保网络连接正常，尝试分批下载：
```bash
pip download -d wheelhouse pandas
pip download -d wheelhouse openpyxl
pip download -d wheelhouse pymysql
```

**Q: 虚拟环境创建失败？**
A: 确保 python3 已安装且版本 >= 3.6：
```bash
python3 --version
```

## 目录与容器入口

- `bash scripts/check-root-layout.sh`：检查根目录是否出现未归类文件。
- `bash scripts/compose.sh dev up -d --build`：在固定的仓库根目录启动开发容器；支持 dev、staging、prod、default。
- Python 离线依赖默认生成到 `deploy/python/wheelhouse/`，虚拟环境默认生成到 `deploy/python/venv/`。

## 模块依赖检查

`bash scripts/check-module-import.sh` 检查工作区相对 HEAD 的新增依赖（包含未跟踪文件）；`--staged` 检查暂存内容；`--all` 全量审计。Service / DTO / 实体跨模块引用允许，Repository 跨模块引用仅提醒，新增 Controller 跨模块引用和 shared 反向业务依赖阻止通过。需要 Python 3。详见 [模块依赖规范](../doc/模块依赖规范.md)。
