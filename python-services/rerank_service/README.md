# Rerank Python Service

这个目录提供本地 BGE rerank HTTP 服务，默认模型为 `BAAI/bge-reranker-v2-m3`。

它现在不是单纯的 demo 同步接口：服务入口增加了请求限流、有界队列、推理超时、输入大小保护和健康检查统计。注意，这些能力是**单进程内**的；如果你用多个 uvicorn worker 或多实例部署，每个进程都会有自己的队列、限流桶和模型实例。

## 1. 安装依赖

建议 Python `3.10` 或 `3.11`。

```bash
cd python-services/rerank_service
pip install -r requirements.txt
```

如果使用 GPU，请按你的 CUDA/PyTorch 环境单独安装合适版本，不要靠运气解决推理环境。

## 2. 启动服务

直接脚本启动：

```bash
python main.py
```

或用 uvicorn：

```bash
cd python-services
uvicorn rerank_service.main:app --host 0.0.0.0 --port 8092
```

## 3. 接口

健康检查：

```bash
curl http://localhost:8092/health
```

重排序：

```bash
curl -X POST "http://localhost:8092/rerank" ^
  -H "Content-Type: application/json" ^
  -d "{\"query\":\"如何学习人工智能\",\"documents\":[\"人工智能学习路径指南\",\"烹饪技巧\"],\"top_n\":1}"
```

## 4. 并发与限流配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `RERANK_MAX_WORKERS` | `2` | 后台 rerank 推理线程数。模型/GPU 未必适合高并行，建议压测后调整。 |
| `RERANK_MAX_QUEUE_SIZE` | `32` | 等待执行的请求队列容量；队列满时返回 `429`。 |
| `RERANK_REQUEST_TIMEOUT_SECONDS` | `60` | 请求等待结果的最长时间；超时返回 `504`。已开始的推理任务无法强制中断，会在后台自然结束。 |
| `RERANK_RATE_LIMIT_RPS` | `20` | 单进程 token bucket 平均限流速率；设为 `0` 可关闭。 |
| `RERANK_RATE_LIMIT_BURST` | `40` | 单进程突发令牌桶容量；设为 `0` 可关闭。 |
| `RERANK_MAX_DOCUMENTS` | `128` | 单次 rerank 最大文档数。 |
| `RERANK_MAX_QUERY_CHARS` | `4096` | query 最大字符数。 |
| `RERANK_MAX_DOCUMENT_CHARS` | `8192` | 单个 document 最大字符数。 |

健康检查会返回 `queued_requests`、`running_requests`、`completed_requests`、`failed_requests`、`rejected_requests`、`timed_out_requests` 等指标，便于 Java 侧或本地脚本判断服务是否已经开始积压。
