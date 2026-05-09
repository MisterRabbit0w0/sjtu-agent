"""sjtu_agent/news_aggregator/ranker.py — 新闻智能排序。

两段式：
1. 关键词初筛（无 LLM，快速）
2. LLM 精排（top 30 → 打分 + 推荐理由）
"""
from __future__ import annotations

import json
import math
import re
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from sjtu_agent.news_aggregator.sources.base import NewsItem
    from sjtu_agent.news_aggregator.profile import UserProfile


class NewsRanker:
    """新闻排序器。"""

    def rank(
        self,
        items: list["NewsItem"],
        profile: "UserProfile",
        top_k: int = 8,
        llm_client=None,
        model: str = "",
        score_threshold: float = 0.5,
    ) -> list[tuple["NewsItem", float, str]]:
        """
        返回 [(news_item, score, reason), ...]，按 score 降序。
        score 范围 0-1，reason 是推荐理由。
        """
        if not items:
            return []

        profile_data = profile.load()

        # 1. 关键词初筛打分
        scored = [(item, self._keyword_score(item, profile_data)) for item in items]
        scored.sort(key=lambda x: x[1], reverse=True)

        # 2. 取 top 30 进 LLM 精排
        candidates = scored[:30]

        if llm_client and model:
            try:
                ranked = self._llm_rank(candidates, profile_data, llm_client, model)
                if ranked:
                    return [r for r in ranked if r[1] >= score_threshold][:top_k]
            except Exception as e:
                print(f"[ranker] LLM 精排失败，降级到关键词分数：{e}", flush=True)

        # 降级：直接用关键词分数
        result = []
        for item, score in candidates:
            if score >= score_threshold:
                result.append((item, min(score, 1.0), ""))
        # 画像为空时（所有分数都是 0），直接推送 top-k 条（时效性最高的）
        if not result:
            for item, score in candidates[:top_k]:
                result.append((item, 0.5, ""))  # 给默认分数 0.5
        return result[:top_k]

    def _keyword_score(self, item: "NewsItem", profile_data: dict) -> float:
        """基于关键词词频和兴趣标签计算初步相关度。"""
        text = f"{item.title} {item.summary} {item.category} {' '.join(item.tags)}"
        score = 0.0

        # 关键词匹配
        keywords = profile_data.get("keywords", {})
        total_kw = sum(keywords.values()) or 1
        for kw, count in keywords.items():
            if kw in text:
                # 词频归一化 + log 平滑
                score += math.log(1 + count / total_kw * 10) * 0.3

        # 兴趣标签加成
        interests = profile_data.get("interests", {})
        for tag, weight in interests.items():
            if tag in text:
                score += weight * 0.5

        # 时效性加成（越新越好）
        try:
            age_h = item.age_hours()
            if age_h < 2:
                score += 0.2
            elif age_h < 6:
                score += 0.1
            elif age_h < 12:
                score += 0.05
        except Exception:
            pass

        return min(score, 1.0)

    def _llm_rank(
        self,
        candidates: list[tuple["NewsItem", float]],
        profile_data: dict,
        client,
        model: str,
    ) -> list[tuple["NewsItem", float, str]]:
        """LLM 批量打分 + 生成推荐理由。"""
        from sjtu_agent.agent.runner import _is_anthropic_model

        persona = profile_data.get("persona_summary", "")
        interests = profile_data.get("interests", {})
        blocked = profile_data.get("blocked_categories", [])

        # 构建兴趣描述
        top_interests = sorted(interests.items(), key=lambda x: x[1], reverse=True)[:5]
        interests_text = "、".join(f"{k}({v:.0%})" for k, v in top_interests) or "未知"

        # 构建新闻列表
        news_lines = []
        for i, (item, _) in enumerate(candidates, 1):
            news_lines.append(
                f"[{i}] {item.title} | {item.source} | {item.summary[:80]}"
            )
        news_text = "\n".join(news_lines)

        prompt = f"""你是新闻推荐系统。基于用户画像，给以下新闻打分（0-1）并给出推荐理由。

## 用户画像
{persona or '交大在校学生'}

关注主题：{interests_text}
屏蔽分类：{', '.join(blocked) or '无'}

## 候选新闻（共 {len(candidates)} 条）
{news_text}

## 评分标准
- 0.9-1.0：用户当前关注的核心问题，必须看
- 0.7-0.9：与用户兴趣高度相关
- 0.5-0.7：用户可能感兴趣
- 0.3-0.5：通用信息，可看可不看
- 0.0-0.3：与用户兴趣关联弱或已屏蔽

## 输出格式（仅输出 JSON 数组，无其他内容）
[
  {{"id": 1, "score": 0.95, "reason": "用户最近多次问保研，这条是官方政策更新"}},
  ...
]"""

        try:
            if _is_anthropic_model(model):
                resp = client.messages.create(
                    model=model,
                    max_tokens=1024,
                    messages=[{"role": "user", "content": prompt}],
                )
                text = resp.content[0].text
            else:
                resp = client.chat.completions.create(
                    model=model,
                    messages=[{"role": "user", "content": prompt}],
                    max_tokens=1024,
                )
                text = resp.choices[0].message.content

            # 提取 JSON 数组
            m = re.search(r"\[.*\]", text, re.DOTALL)
            if not m:
                return []
            scores_data = json.loads(m.group())

            # 构建结果
            result = []
            id_map = {i + 1: (item, base_score) for i, (item, base_score) in enumerate(candidates)}
            for entry in scores_data:
                idx = entry.get("id")
                score = float(entry.get("score", 0))
                reason = entry.get("reason", "")
                if idx in id_map:
                    item, _ = id_map[idx]
                    result.append((item, score, reason))

            result.sort(key=lambda x: x[1], reverse=True)
            return result

        except Exception as e:
            print(f"[ranker] LLM 响应解析失败：{e}", flush=True)
            return []
