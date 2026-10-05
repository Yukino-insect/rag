import logging
import threading

from FlagEmbedding import FlagReranker

try:
    from .config import RerankServiceSetting
    from .models import RerankResultItem
except ImportError:  # pragma: no cover - 兼容直接脚本启动
    from config import RerankServiceSetting
    from models import RerankResultItem


class RerankService:
    logger = logging.getLogger("RerankService")

    def __init__(self, settings: RerankServiceSetting | None = None):
        self.settings = settings or RerankServiceSetting()
        self._reranker = None
        self._load_lock = threading.Lock()

    def rerank(self, query: str, docs: list[str], top_n: int | None = None) -> list[RerankResultItem]:
        # 构造 (query, doc) 配对输入
        pairs = [(query, doc) for doc in docs]
        scores = self.reranker.compute_score(pairs)

        # 当只传了 1 个 doc 时，compute_score 返回的是 float 而不是 list
        if isinstance(scores, (float, int)):
            scores = [scores]

        items = [
            RerankResultItem(index=idx, document=doc, score=float(score))
            for idx, (doc, score) in enumerate(zip(docs, scores))
        ]

        items.sort(key=lambda item: item.score, reverse=True)

        if top_n is not None and top_n > 0:
            items = items[:top_n]
        return items

    @property
    def reranker(self):
        if self._reranker is not None:
            return self._reranker
        with self._load_lock:
            if self._reranker is None:
                RerankService.logger.info("loading %s module", self.settings.model_name)
                self._reranker = FlagReranker(self.settings.model_name, use_fp16=self.settings.use_fp16)
                RerankService.logger.info("loading completed")
        return self._reranker

def test_rerank():
    reranker = RerankService()

    # 定义查询和文档
    query = "如何学习人工智能"
    documents = [
        "人工智能学习路径指南",
        "烹饪美食的十大技巧",
        "机器学习入门教程",
        "旅游攻略：北京三日游"
    ]

    # 计算相关性分数
    res = reranker.rerank(query=query, docs=documents)
    print(res)

if __name__ == "__main__":
    test_rerank()
