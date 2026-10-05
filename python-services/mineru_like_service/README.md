# MinerU-like Python Service

这个目录提供一个**独立运行**的 MinerU-like Python 服务。

它基于 **PaddleOCR PP-StructureV3**，目标不是复刻 MinerU 的全部细节，而是先提供一条可落地的本地解析链路：

- 上传 `PDF / 图片 / Office 文档`
- Office 文档可选通过 `LibreOffice` 转成 PDF
- 使用 PaddleOCR 做版面解析、表格识别、公式识别
- 输出 **Markdown + JSON**
- 按页保存解析产物，便于后续接入 RAG / 知识库

## 目录结构

```text
python-services/mineru_like_service/
├─ app/
│  ├─ __init__.py
│  ├─ config.py
│  ├─ main.py
│  ├─ models.py
│  └─ pipeline.py
├─ .runtime/              # 运行时自动生成
├─ README.md
└─ requirements.txt
```

## 1. 安装依赖

### 1.1 Python 环境

建议 Python `3.10` 或 `3.11`。

### 1.2 安装 PaddlePaddle

`paddleocr` 依赖 `paddlepaddle` 或 `paddlepaddle-gpu`。这一步请按官方文档和你的硬件环境安装。

CPU 示例：

```bash
pip install paddlepaddle
```

GPU 示例请按官方文档选择对应 CUDA 版本，不要自己猜。

### 1.3 安装服务依赖

```bash
cd python-services/mineru_like_service
pip install -r requirements.txt
```

说明：

- `paddleocr` 在运行 `PP-StructureV3` 时还依赖 `paddlex` 的 `ocr` 扩展依赖。
- 如果你不是通过 `requirements.txt` 安装，至少还需要补一条：

```bash
pip install "paddlex[ocr]"
```

### 1.4 可选：安装 LibreOffice

如果你希望服务支持 `doc/docx/ppt/pptx/xls/xlsx`，需要安装 `LibreOffice`，并保证 `soffice` 在 `PATH` 中，或者配置环境变量：

```bash
set SOFFICE_BIN=C:\Program Files\LibreOffice\program\soffice.exe
```

如果不装 LibreOffice，服务仍然支持：

- `pdf`
- `png/jpg/jpeg/webp/bmp/tif/tiff`

## 2. 启动服务

```bash
cd python-services/mineru_like_service
uvicorn app.main:app --host 0.0.0.0 --port 8091
```

也可以通过环境变量调整默认参数：

```bash
set MINERU_LIKE_PORT=8091
set MINERU_LIKE_DEFAULT_LANG=ch
set MINERU_LIKE_DEFAULT_DEVICE=cpu
set MINERU_LIKE_DEFAULT_PARSE_MODE=structure
set MINERU_LIKE_TASK_MAX_WORKERS=2
set MINERU_LIKE_TASK_MAX_QUEUE_SIZE=16
set MINERU_LIKE_RATE_LIMIT_RPS=5
set MINERU_LIKE_RATE_LIMIT_BURST=10
```

并发与限流相关配置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MINERU_LIKE_TASK_MAX_WORKERS` | `2` | 后台解析线程数。OCR/版面分析通常较重，建议按 CPU/GPU 与内存容量谨慎调大。 |
| `MINERU_LIKE_TASK_MAX_QUEUE_SIZE` | `16` | 等待执行的任务队列容量；队列满时提交接口返回 `429`，避免无界堆积拖垮进程。 |
| `MINERU_LIKE_RATE_LIMIT_RPS` | `5` | 单进程 token bucket 平均限流速率；设为 `0` 可关闭。 |
| `MINERU_LIKE_RATE_LIMIT_BURST` | `10` | 单进程突发令牌桶容量；设为 `0` 可关闭。 |
| `MINERU_LIKE_MAX_UPLOAD_MB` | `100` | 上传大小上限，服务会在流式写入时提前拒绝超限文件。 |

## 3. 接口说明

### 3.1 健康检查

```bash
curl http://localhost:8091/health
```

健康检查会返回当前队列与运行统计，例如 `queued_tasks`、`running_tasks`、`rejected_tasks`、
`task_max_workers`、`task_max_queue_size` 和限流配置。它不是分布式监控系统，只是把单进程
服务的关键运行态诚实地摆出来。

### 3.2 版本信息

```bash
curl http://localhost:8091/version
```

### 3.3 上传文件解析

```bash
curl -X POST "http://localhost:8091/parse" ^
  -F "file=@C:/data/sample.pdf" ^
  -F "lang=ch" ^
  -F "parse_mode=structure" ^
  -F "save_artifacts=true" ^
  -F "include_raw_payload=false"
```

常用表单字段：

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `file` | 必填 | 上传的文档 |
| `lang` | `ch` | PaddleOCR 语言 |
| `device` | `cpu` | `cpu` 或 `gpu[:id]` |
| `parse_mode` | `structure` | `structure` 或 `vl` |
| `save_artifacts` | `true` | 是否将 markdown/json 落盘 |
| `include_raw_payload` | `false` | 是否在响应中返回每页原始 JSON |
| `use_chart_recognition` | `false` | 是否开启图表识别 |
| `use_formula_recognition` | `true` | 是否开启公式识别 |
| `use_seal_recognition` | `false` | 是否开启印章识别 |
| `use_table_recognition` | `true` | 是否开启表格识别 |

### 3.4 解析本地文件路径

如果服务端本机能直接访问文件，也可以调用：

```bash
curl -X POST "http://localhost:8091/parse/path" ^
  -H "Content-Type: application/json" ^
  -d "{\"file_path\":\"C:/data/sample.pdf\",\"lang\":\"ch\",\"parse_mode\":\"structure\"}"
```

### 3.5 提交异步解析任务

当 Java 侧不希望长时间阻塞等待 OCR 时，可以改用异步任务接口：

```bash
curl -X POST "http://localhost:8091/tasks/rag" ^
  -F "file=@C:/data/sample.pdf" ^
  -F "doc_id=2081379723828576256" ^
  -F "callback_url=http://127.0.0.1:9090/api/rag/internal/mineru/callback"
```

成功后会立即返回 `task_id`，Python 后台线程池继续执行解析，并在完成后回调 Java。

### 3.6 查询异步任务结果

```bash
curl "http://localhost:8091/tasks/<task_id>/result"
```

该接口会返回任务状态；成功时附带完整的 `RagParseData` 结果，供 Java 在收到回调后拉取。

## 4. 返回结果示例

```json
{
  "success": true,
  "data": {
    "request_id": "9e5a...",
    "filename": "sample.pdf",
    "mime_type": "application/pdf",
    "summary": {
      "parser": "paddleocr-ppstructurev3",
      "parse_mode": "structure",
      "lang": "ch",
      "device": "cpu",
      "page_count": 3,
      "converted_from": null,
      "stored_source_path": "python-services/mineru_like_service/.runtime/output/9e5a.../sample.pdf",
      "normalized_source_path": "python-services/mineru_like_service/.runtime/output/9e5a.../sample.pdf",
      "combined_markdown_path": "python-services/mineru_like_service/.runtime/output/9e5a.../document.md"
    },
    "markdown": "<!-- page:1 --> ...",
    "pages": [
      {
        "page_index": 1,
        "markdown_text": "...",
        "markdown_path": ".../page-0001/content.md",
        "json_path": ".../page-0001/content.json",
        "image_count": 2,
        "block_count": 14,
        "raw_payload": null
      }
    ],
    "assets": [
      ".../page-0001/images/img-1.png"
    ],
    "metadata": {
      "request_output_dir": "...",
      "pipeline_options": {
        "lang": "ch",
        "device": "cpu",
        "parse_mode": "structure",
        "use_chart_recognition": false,
        "use_formula_recognition": true,
        "use_seal_recognition": false,
        "use_table_recognition": true
      }
    }
  }
}
```

## 5. 这套服务现在能做什么

- 先给现有 Java RAG 系统提供一个可调用的文档解析 HTTP 服务
- 先把 PDF / 图片 / Office 解析成 Markdown 和结构化 JSON
- 为后续做 chunk、metadata、向量化提供干净入口

## 6. 这套服务现在还不做什么

- 不做分布式任务队列（当前为单进程内线程池异步）
- 不做数据库持久化
- 不做权限校验
- 不做对象存储上传
- 不试图百分之百复刻 MinerU 的输出格式

不过它现在已经具备基本的单进程服务保护：

- 有界后台任务队列，队列满时返回 `429`
- token bucket 请求限流，限流命中时返回 `429` 与 `Retry-After`
- 上传大小流式检查，超限时返回 `413`
- 健康检查暴露队列、运行、拒绝等计数
- 任务状态文件使用临时文件原子替换，降低并发读写时读到半截 JSON 的概率

换句话说，这是一套**适合你先跑通链路**的服务，而不是某种自以为是的“大而全平台”。

## 7. 后续建议

如果你下一步要把它接入主项目，通常建议：

1. Java 侧通过 HTTP 上传文件到这个服务
2. 保存返回的 `markdown` 和每页 `json`
3. 再进入原有 chunk / embedding / index 流程

这样改动面最小，也最符合“先把系统接起来”的思路。
