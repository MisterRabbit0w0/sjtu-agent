"""sjtu_agent/news_aggregator/aggregator.py — 主聚合流程。"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import TYPE_CHECKING

from sjtu_agent.news_aggregator.sources.base import NewsItem
from sjtu_agent.news_aggregator.sources.jwc import JwcSource
from sjtu_agent.news_aggregator.sources.shuiyuan import ShuiyuanSource
from sjtu_agent.news_aggregator.sources.official import OfficialSource
from sjtu_agent.news_aggregator.sources.canvas import CanvasSource
from sjtu_agent.news_aggregator.profile import UserProfile
from sjtu_agent.news_aggregator.ranker import NewsRanker
from sjtu_agent.news_aggregator.digest import DigestBuilder
from sjtu_agent.news_aggregator.storage import NewsStorage
from sjtu_agent.config import cfg as config_store


class NewsAggregator:
    """完整的新闻聚合流程。"""

    def __init__(self, llm_client=None, model: str = ""):
        self.settings = config_store.news_digest_config
        self.sources = self._build_sources(self.settings.get("sources", {}))
        self.profile  = UserProfile()
        self.ranker   = NewsRanker()
        self.builder  = DigestBuilder()
        self.storage  = NewsStorage()
        self.llm_client = llm_client
        self.model    = model
        self.top_k = int(self.settings.get("top_k", 8) or 8)
        self.score_threshold = float(self.settings.get("score_threshold", 0.5) or 0.5)

    def _build_sources(self, sources_cfg: dict) -> list:
        def enabled(name: str, default: bool = True) -> bool:
            item = sources_cfg.get(name, {})
            return bool(item.get("enabled", default)) if isinstance(item, dict) else default

        sources = []
        if enabled("jwc"):
            sources.append(JwcSource())
        if enabled("shuiyuan"):
            sy_cfg = sources_cfg.get("shuiyuan", {}) if isinstance(sources_cfg.get("shuiyuan"), dict) else {}
            sources.append(ShuiyuanSource(
                min_views=int(sy_cfg.get("min_views", 50) or 50),
                min_likes=int(sy_cfg.get("min_likes", 3) or 3),
            ))
        if enabled("official"):
            sources.append(OfficialSource())
        if enabled("canvas"):
            sources.append(CanvasSource())
        return sources

    def run(self, hours: int = 24, top_k: int | None = None) -> tuple[str, str]:
        """
        完整聚合流程。
        返回 (markdown_digest, telegram_html_digest)。
        """
        if top_k is None:
            top_k = self.top_k
        if not self.sources:
            empty_msg = "📰 新闻日报没有启用任何信息源。"
            return empty_msg, empty_msg

        # 1. 并发采集
        all_items: list[NewsItem] = []
        with ThreadPoolExecutor(max_workers=len(self.sources)) as pool:
            futures = {pool.submit(s.fetch_recent, hours): s for s in self.sources}
            for fut in as_completed(futures):
                src = futures[fut]
                try:
                    items = fut.result()
                    all_items.extend(items)
                    print(f"[news/{src.name}] 采集到 {len(items)} 条", flush=True)
                except Exception as e:
                    print(f"[news/{src.name}] 失败：{e}", flush=True)

        print(f"[news] 总计采集 {len(all_items)} 条", flush=True)

        # 2. 去重（过滤已推送）
        all_items = self.storage.dedupe(all_items)
        print(f"[news] 去重后 {len(all_items)} 条", flush=True)

        # 3. 用户画像过滤
        all_items = [i for i in all_items if not self.profile.is_blocked(i)]

        if not all_items:
            empty_msg = "📰 今天没有新的值得关注的内容。"
            return empty_msg, empty_msg

        # 4. 智能排序
        ranked = self.ranker.rank(
            all_items,
            self.profile,
            top_k=top_k,
            llm_client=self.llm_client,
            model=self.model,
            score_threshold=self.score_threshold,
        )
        print(f"[news] 排序后精选 {len(ranked)} 条", flush=True)

        # 5. 生成日报
        md_digest   = self.builder.build(ranked, self.profile)
        html_digest = self.builder.build_telegram_html(ranked, self.profile)

        # 6. 标记已推送
        if ranked:
            self.storage.mark_pushed([item.id for item, _, _ in ranked])

        return md_digest, html_digest

    def send_via_telegram(self, html_digest: str) -> bool:
        """通过 Telegram 推送日报。"""
        from sjtu_agent.notifiers import NotificationDispatcher

        result = NotificationDispatcher().send_telegram(
            html_digest,
            parse_mode="HTML",
            disable_web_page_preview=True,
        )
        if result.skipped:
            print(f"[news] {result.message}，跳过推送", flush=True)
        elif not result.ok:
            print(f"[news] Telegram 推送失败：{result.message}", flush=True)
        return result.ok

    def send_via_wechat(self, md_digest: str) -> bool:
        """通过微信 ilink Bot 推送日报（纯文本/Markdown）。"""
        from sjtu_agent.notifiers import NotificationDispatcher

        result = NotificationDispatcher().send_wechat(md_digest)
        if result.skipped:
            print(f"[news] {result.message}，跳过推送", flush=True)
        elif not result.ok:
            print(f"[news] 微信推送失败：{result.message}", flush=True)
        return result.ok
