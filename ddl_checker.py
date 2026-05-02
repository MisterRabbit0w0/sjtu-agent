#!/usr/bin/env python3
"""
DDL Checker — 多平台课程截止时间聚合工具

支持平台：
  1. Canvas LMS      (oc.sjtu.edu.cn)     — Bearer Token
  2. aihaoke.net     (sjtu.aihaoke.net)   — Cookie
  3. 物理实验        (phycai.sjtu.edu.cn) — Cookie
  4. 中国大学MOOC    (icourse163.org)     — Cookie

用法：
  python ddl_checker.py
  python ddl_checker.py --canvas-only
  python ddl_checker.py --skip icourse phycai
"""

import argparse
import json
import os
import platform
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone, timedelta
from pathlib import Path

import requests
from bs4 import BeautifulSoup

try:
    from playwright.sync_api import sync_playwright
    HAS_PLAYWRIGHT = True
except ImportError:
    HAS_PLAYWRIGHT = False

# ── 全局常量 ──────────────────────────────────────────────────────────────────

CONFIG_PATH = Path(__file__).parent / "config.json"
CST = timezone(timedelta(hours=8))
NOW = datetime.now(CST)
WEEKDAY_ZH = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

# aihaoke 课程列表（可在此处增减）
AIHAOKE_COURSES = [
    {"name": "高等数学上", "courseId": 1962,  "instanceId": 1881},
    {"name": "高等数学下", "courseId": 6095,  "instanceId": 10630},
    {"name": "电路理论",   "courseId": 11760, "instanceId": 1037},
    {"name": "电路实验",   "courseId": 23789, "instanceId": 1250},
]

# 中国大学MOOC课程列表（可在此处增减）
ICOURSE_COURSES = [
    {
        "name": "大学物理",
        "learn_url": "https://www.icourse163.org/learn/SJTU-1449794172?tid=1476751568",
        "term_id": 1476751568,
        "course_id": "SJTU-1449794172",
    },
]


# ── 工具函数 ──────────────────────────────────────────────────────────────────

def load_config() -> dict:
    if not CONFIG_PATH.exists():
        print(f"[错误] 未找到配置文件：{CONFIG_PATH}")
        print("       请将 config.example.json 复制为 config.json 并填入凭据。")
        sys.exit(1)
    with CONFIG_PATH.open(encoding="utf-8") as f:
        return json.load(f)


def parse_dt(s: str) -> datetime | None:
    """尝试多种格式解析时间字符串，统一返回 CST datetime。"""
    if not s:
        return None
    s = s.strip()
    fmts = [
        "%Y-%m-%dT%H:%M:%SZ",
        "%Y-%m-%dT%H:%M:%S.%fZ",
        "%Y-%m-%dT%H:%M:%S+08:00",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d %H:%M:%S",
        "%Y/%m/%d %H:%M:%S",
        "%Y-%m-%d %H:%M",
        "%Y/%m/%d %H:%M",
    ]
    for fmt in fmts:
        try:
            dt = datetime.strptime(s, fmt)
            if dt.tzinfo is None:
                # 无时区信息默认为 CST
                dt = dt.replace(tzinfo=CST)
            return dt.astimezone(CST)
        except ValueError:
            continue
    return None


def deadline_label(dt: datetime) -> str:
    delta = dt - NOW
    days = delta.days
    hours = int(delta.total_seconds() // 3600)
    if delta.total_seconds() < 0:
        return "已过期"
    if hours < 24:
        return f"今天 {hours}h后"
    if days == 1:
        return "明天"
    return f"{days}天后"


def fmt_due(dt: datetime) -> str:
    return dt.strftime("%Y/%m/%d %H:%M")


def make_session(cookies: dict, referer: str = "") -> requests.Session:
    s = requests.Session()
    s.cookies.update(cookies)
    s.headers.update({
        "User-Agent": (
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/124.0.0.0 Safari/537.36"
        ),
        **({"Referer": referer} if referer else {}),
    })
    return s


# ── Platform 1: Canvas LMS ────────────────────────────────────────────────────

def fetch_canvas(cfg: dict) -> list[dict]:
    """通过 Canvas REST API 获取近期必做作业。"""
    token = cfg.get("canvas_token", "").strip()
    base = cfg.get("canvas_base_url", "https://oc.sjtu.edu.cn").rstrip("/")
    if not token or token.startswith("YOUR_"):
        print("[Canvas] ⚠ 未配置 canvas_token，跳过")
        return []

    session = requests.Session()
    session.headers["Authorization"] = f"Bearer {token}"

    # 1. 分页获取所有在修课程
    courses: list[dict] = []
    url: str | None = f"{base}/api/v1/courses"
    params: dict = {"enrollment_state": "active", "per_page": 100}
    while url:
        try:
            r = session.get(url, params=params, timeout=15)
            r.raise_for_status()
        except requests.RequestException as e:
            print(f"[Canvas] 获取课程列表失败：{e}")
            return []
        courses.extend(r.json())
        url = r.links.get("next", {}).get("url")
        params = {}

    results: list[dict] = []

    # 2. 逐课程拉取即将到期作业，并用 /students/submissions 批量核查个人提交状态
    # （include[]=submission 在 SJTU Canvas 有 bug，数据不准确）
    for course in courses:
        cid = course["id"]
        cname = course.get("name", f"课程{cid}")

        # 2a. 拉取 upcoming 作业列表
        pending: list[dict] = []
        asgn_url: str | None = f"{base}/api/v1/courses/{cid}/assignments"
        asgn_params: dict = {"bucket": "upcoming", "per_page": 50, "order_by": "due_at"}
        while asgn_url:
            try:
                r = session.get(asgn_url, params=asgn_params, timeout=15)
                r.raise_for_status()
            except requests.RequestException as e:
                print(f"[Canvas] 获取 {cname} 作业失败：{e}")
                break
            for a in r.json():
                if not a.get("submission_types"):
                    continue
                due = parse_dt(a.get("due_at", ""))
                if not due or due < NOW:
                    continue
                pending.append({"id": a["id"], "name": a.get("name", "未知作业"), "due": due})
            asgn_url = r.links.get("next", {}).get("url")
            asgn_params = {}

        if not pending:
            continue

        # 2b. 批量查当前用户的提交状态
        aid_list = [str(a["id"]) for a in pending]
        submitted_ids: set[int] = set()
        sub_url: str | None = f"{base}/api/v1/courses/{cid}/students/submissions"
        sub_params: dict = {"student_ids[]": "self", "per_page": 100}
        for aid in aid_list:
            sub_params.setdefault("assignment_ids[]", []).append(aid)  # type: ignore[union-attr]
        try:
            sr = session.get(sub_url, params=sub_params, timeout=15)
            sr.raise_for_status()
            for sub in sr.json():
                if sub.get("workflow_state") in ("submitted", "graded") and sub.get("submitted_at"):
                    submitted_ids.add(sub["assignment_id"])
        except requests.RequestException as e:
            print(f"[Canvas] 查询 {cname} 提交状态失败：{e}")

        for a in pending:
            results.append({
                "platform": "Canvas",
                "course": cname,
                "name": a["name"],
                "due": a["due"],
                "submitted": a["id"] in submitted_ids,
            })

    return results


# ── Platform 2: sjtu.aihaoke.net (Playwright) ────────────────────────────────
# aihaoke 是纯 Vue SPA，数据通过私有封装的 HTTP 客户端加载到 Pinia store，
# 无法直接用 requests 复现。用 Playwright 注入 cookie、加载页面、从 store 读数据。

_AIHAOKE_JS = """
() => {
  const pinia = document.querySelector('#app')?.__vue_app__
                 ?.config?.globalProperties?.$pinia;
  if (!pinia) return null;
  const state = pinia.state.value?.studentTaskStore?.studentTaskState;
  if (!state) return null;
  return state.menuData || [];
}
"""

def _aihaoke_do_login(page, username: str = "", password: str = "") -> bool:
    """
    在 aihaoke 登录页完成 jAccount SSO。
    若提供了 username/password，遇到 jAccount 表单时自动填写；
    否则依赖已注入的 jaccount cookie 静默通过。
    """
    page.goto("https://sjtu.aihaoke.net/login", wait_until="networkidle", timeout=20_000)
    # 点击"统一身份认证"Tab
    page.locator(".login-type-tabs li").last.click()

    # 等待跳转（可能直接到 student 页，也可能跳到 jAccount 表单）
    try:
        page.wait_for_url("**/jaccount**", timeout=10_000)
    except Exception:
        # 已经在 student 页，说明 cookie 静默通过了
        if "student" in page.url:
            return True
        return False

    # 需要手动填写 jAccount 表单
    if not username:
        print("[aihaoke] jAccount 需要重新登录但未提供账号密码，尝试 cookie 回退…")
        return False

    page.evaluate("if (typeof switchLoginType === 'function') switchLoginType('password')")
    page.wait_for_timeout(400)
    page.fill("#input-login-user", username)
    page.fill("#input-login-pass", password)

    for attempt in range(3):
        cap = page.locator("#captcha-img")
        if cap.count() and cap.is_visible():
            code = _solve_captcha_phycai(cap.screenshot())
            page.fill("#input-login-captcha", code)
        page.click("#submit-password-button")
        try:
            page.wait_for_function(
                "() => !location.href.includes('jaccount.sjtu.edu.cn') || "
                "!!document.querySelector('.alert-danger, [class*=errorMsg]')",
                timeout=12_000,
            )
        except Exception:
            pass
        if "jaccount.sjtu.edu.cn" not in page.url:
            break
        print(f"  [jAccount] 第 {attempt + 1} 次验证码错误，刷新重试…")
        page.evaluate("if (typeof refreshCaptcha === 'function') refreshCaptcha()")
        page.wait_for_timeout(700)
    else:
        print("[aihaoke] jAccount 验证码多次失败")
        return False

    try:
        page.wait_for_url("**/sjtu.aihaoke.net/student/**", timeout=20_000)
        page.wait_for_load_state("networkidle", timeout=10_000)
    except Exception as e:
        print(f"[aihaoke] 等待登录完成超时：{e}")
        return False

    return True


def fetch_aihaoke(cfg: dict) -> list[dict]:
    """用 Playwright 加载 aihaoke 课程任务页，从 Pinia store 读取数据。"""
    if not HAS_PLAYWRIGHT:
        print("[aihaoke] ⚠ 未安装 playwright，跳过（pip install playwright && playwright install chromium）")
        return []

    # 读取 .env 凭据
    try:
        from dotenv import load_dotenv  # type: ignore
        load_dotenv()
    except ImportError:
        pass
    username = os.environ.get("JACCOUNT_USERNAME", "").strip()
    password = os.environ.get("JACCOUNT_PASSWORD", "").strip()
    has_creds = bool(username and password)

    raw_cookies = cfg.get("aihaoke_cookies", {})
    jaccount_cookies = cfg.get("jaccount_cookies", {})
    if not has_creds and not raw_cookies and not jaccount_cookies:
        print("[aihaoke] ⚠ 未配置 aihaoke_cookies / jaccount_cookies 且无 .env 凭据，跳过")
        return []

    results: list[dict] = []
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        ctx = browser.new_context()

        # 注入现有 cookies（即使用账号密码，jaccount cookie 也可能帮助静默通过）
        pw_cookies = [
            {"name": k, "value": v, "domain": "sjtu.aihaoke.net", "path": "/"}
            for k, v in raw_cookies.items()
        ] + [
            {"name": k, "value": v, "domain": "jaccount.sjtu.edu.cn", "path": "/"}
            for k, v in jaccount_cookies.items()
        ]
        if pw_cookies:
            ctx.add_cookies(pw_cookies)

        page = ctx.new_page()
        try:
            ok = _aihaoke_do_login(
                page,
                username=username if has_creds else "",
                password=password if has_creds else "",
            )
        except Exception as e:
            print(f"[aihaoke] SSO 登录失败：{e}")
            browser.close()
            return []

        if not ok:
            print("[aihaoke] SSO 登录失败，跳过")
            browser.close()
            return []

        if has_creds:
            print("[aihaoke] ✓ jAccount 登录成功")
            # 把新 cookies 写回 config.json
            new_cookies = {
                c["name"]: c["value"]
                for c in ctx.cookies()
                if "aihaoke.net" in c.get("domain", "")
            }
            if new_cookies:
                cfg["aihaoke_cookies"] = new_cookies
                CONFIG_PATH.write_text(
                    json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8"
                )
                print("[aihaoke] ✓ 已更新 cookies")

        for course in AIHAOKE_COURSES:
            cid   = course["courseId"]
            iid   = course["instanceId"]
            cname = course["name"]
            url   = f"https://sjtu.aihaoke.net/student/course/{cid}/task?instanceId={iid}&taskType=all-tasks"
            print(f"  [aihaoke] 加载 {cname}…")
            try:
                page.goto(url, wait_until="networkidle", timeout=30_000)
                # 等 Pinia store 填充（最多 10s）
                page.wait_for_function(
                    "() => document.querySelector('#app')?.__vue_app__"
                    "?.config?.globalProperties?.$pinia"
                    "?.state?.value?.studentTaskStore?.studentTaskState?.menuData?.length > 0",
                    timeout=10_000,
                )
                tasks = page.evaluate(_AIHAOKE_JS) or []
            except Exception as e:
                print(f"  [aihaoke] {cname} 加载失败：{e}")
                continue

            now_ts = NOW.timestamp() * 1000  # JS Date 用毫秒，这里用字符串比较
            for t in tasks:
                # requireFlag=1 必做，myStatus=10 待提交，endTime 未过期
                if t.get("requireFlag") != 1:
                    continue
                if t.get("myStatus") != 10:
                    continue
                # 只保留需要主动提交的任务类型：
                #   51=作业  50=思考与练习  30=讨论  70=实验报告
                #   10=阅读 / 20=视频 不需要主动提交，排除
                if t.get("taskType") not in (30, 50, 51, 70):
                    continue
                due = parse_dt(t.get("endTime", ""))
                if not due or due < NOW:
                    continue
                results.append({
                    "platform": "aihaoke",
                    "course": cname,
                    "name": t.get("taskName", "未知任务").strip(),
                    "due": due,
                    "submitted": False,
                })

        browser.close()
    return results


# ── Platform 3: phycai.sjtu.edu.cn ───────────────────────────────────────────

_GEEK_CAPTCHA_API = "https://geek.sjtu.edu.cn/captcha-solver/"


def _solve_captcha_phycai(img_bytes: bytes) -> str:
    """验证码识别：极客协会 API → Claude → 手动输入。"""
    import base64
    import io

    # 1. 极客协会 API
    try:
        try:
            from PIL import Image  # type: ignore
            img = Image.open(io.BytesIO(img_bytes)).convert("RGB").resize((110, 40))
            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=90)
            jpeg_bytes = buf.getvalue()
        except ImportError:
            jpeg_bytes = img_bytes
        r = requests.post(
            _GEEK_CAPTCHA_API,
            files={"image": ("cap.jpg", jpeg_bytes, "image/jpeg")},
            timeout=8,
        )
        if r.ok:
            code = r.json().get("result", "").strip()
            if code:
                print(f"  [CAPTCHA] 极客协会识别：{code}")
                return code
    except Exception:
        pass

    # 2. Claude Haiku
    try:
        import anthropic  # type: ignore
        api_key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
        if api_key:
            client = anthropic.Anthropic(api_key=api_key)
            msg = client.messages.create(
                model="claude-haiku-20240307",
                max_tokens=16,
                messages=[{"role": "user", "content": [
                    {"type": "image", "source": {
                        "type": "base64", "media_type": "image/png",
                        "data": base64.b64encode(img_bytes).decode(),
                    }},
                    {"type": "text", "text": "这是一个验证码图片，请只输出验证码文字，不要其他内容。"},
                ]}],
            )
            code = msg.content[0].text.strip()
            if code:
                print(f"  [CAPTCHA] Claude 识别：{code}")
                return code
    except Exception:
        pass

    # 3. 手动输入
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
        f.write(img_bytes)
        tmp = f.name
    try:
        if platform.system() == "Darwin":
            subprocess.Popen(["open", tmp])
    except Exception:
        pass
    code = input(f"  [CAPTCHA] 请手动输入验证码（图片：{tmp}）：").strip()
    try:
        os.unlink(tmp)
    except Exception:
        pass
    return code


def _phycai_fetch_with_login(cfg: dict) -> str | None:
    """通过 phycai jAccount SSO 按钮登录，成功则返回页面 HTML 并刷新 cookies。"""
    if not HAS_PLAYWRIGHT:
        return None

    try:
        from dotenv import load_dotenv  # type: ignore
        load_dotenv()
    except ImportError:
        pass

    username = os.environ.get("JACCOUNT_USERNAME", "").strip()
    password = os.environ.get("JACCOUNT_PASSWORD", "").strip()
    if not username or not password:
        return None

    print("[phycai] 正在通过 jAccount 登录…")
    target_url = "http://www.phycai.sjtu.edu.cn/pe/student/select.aspx"
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True)
            ctx = browser.new_context()
            page = ctx.new_page()

            # Jlogin.aspx 会直接跳转到 jAccount OAuth
            page.goto(
                "http://www.phycai.sjtu.edu.cn/pe/Jlogin.aspx",
                wait_until="networkidle", timeout=25_000,
            )

            if "jaccount.sjtu.edu.cn" in page.url:
                page.evaluate(
                    "if (typeof switchLoginType === 'function') switchLoginType('password')"
                )
                page.wait_for_timeout(400)
                page.fill("#input-login-user", username)
                page.fill("#input-login-pass", password)

                logged_in = False
                for attempt in range(3):
                    cap = page.locator("#captcha-img")
                    if cap.count() and cap.is_visible():
                        code = _solve_captcha_phycai(cap.screenshot())
                        page.fill("#input-login-captcha", code)
                    page.click("#submit-password-button")
                    try:
                        page.wait_for_function(
                            "() => !location.href.includes('jaccount.sjtu.edu.cn') || "
                            "!!document.querySelector('.alert-danger, [class*=errorMsg]')",
                            timeout=12_000,
                        )
                    except Exception:
                        pass
                    if "jaccount.sjtu.edu.cn" not in page.url:
                        logged_in = True
                        break
                    print(f"  [jAccount] 第 {attempt + 1} 次验证码错误，刷新重试…")
                    page.evaluate(
                        "if (typeof refreshCaptcha === 'function') refreshCaptcha()"
                    )
                    page.wait_for_timeout(700)

                if not logged_in:
                    print("[phycai] jAccount 登录失败")
                    browser.close()
                    return None

                # 等待跳回 phycai（任意 phycai 路径即可）
                try:
                    page.wait_for_url("**/phycai.sjtu.edu.cn/**", timeout=15_000)
                except Exception:
                    pass

            # 无论跳到哪里，直接导航到目标页
            page.goto(target_url, wait_until="networkidle", timeout=20_000)
            html = page.content()

            # 把新 cookies 写回 config.json
            new_cookies = {
                c["name"]: c["value"]
                for c in ctx.cookies()
                if "phycai.sjtu.edu.cn" in c.get("domain", "")
            }
            if new_cookies:
                cfg["phycai_cookies"] = new_cookies
                CONFIG_PATH.write_text(
                    json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8"
                )
                print("[phycai] ✓ 已登录并更新 cookies")

            browser.close()
            return html
    except Exception as e:
        print(f"[phycai] jAccount 登录出错：{e}")
        return None


def fetch_phycai(cfg: dict) -> dict | None:
    """获取最近一次未到来的物理实验安排。"""
    url = "http://www.phycai.sjtu.edu.cn/pe/student/select.aspx"

    # 优先：用已有 cookies（避免每次都要过验证码）
    cookies = cfg.get("phycai_cookies", {})
    if cookies and not all(v.startswith("YOUR_") for v in cookies.values()):
        session = make_session(cookies)
        try:
            r = session.get(url, timeout=15)
            r.encoding = r.apparent_encoding
            r.raise_for_status()
            # 简单判断是否跳转到登录页（cookies 过期时会跳转）
            if "login" not in r.url.lower() and "Jlogin" not in r.url:
                result = _parse_phycai_table(r.text)
                if result is not None:
                    return result
        except requests.RequestException:
            pass

    # 回退：用 .env 账号密码通过 jAccount 登录
    html = _phycai_fetch_with_login(cfg)
    if html is None:
        print("[phycai] ⚠ 登录失败，跳过")
        return None

    return _parse_phycai_table(html)


def _parse_phycai_table(html: str) -> dict | None:
    soup = BeautifulSoup(html, "lxml")

    # 找到包含实验数据的主表（通常是内容最多的那个）
    tables = soup.find_all("table")
    if not tables:
        print("[phycai] ⚠ 未找到任何表格，可能 cookie 已过期")
        return None
    table = max(tables, key=lambda t: len(t.find_all("tr")))

    rows = table.find_all("tr")
    if len(rows) < 2:
        return None

    # 解析表头，建立列索引
    headers = [th.get_text(strip=True) for th in rows[0].find_all(["th", "td"])]
    col: dict[str, int] = {}
    mappings = {
        "name": ["实验项目", "实验名称", "项目名称", "项目"],
        "date": ["实验日期", "日期", "上课日期"],
        "time": ["实验时间", "时间", "上课时间"],
        "room": ["上课教室", "教室", "地点", "实验室"],
    }
    for key, words in mappings.items():
        for i, h in enumerate(headers):
            if any(w in h for w in words):
                col[key] = i
                break

    experiments: list[dict] = []
    for row in rows[1:]:
        cells = [c.get_text(strip=True) for c in row.find_all("td")]
        if not cells:
            continue

        def cell(key):
            i = col.get(key)
            return cells[i] if i is not None and i < len(cells) else ""

        date_str = cell("date")
        time_str = cell("time")
        # 提取第一个 HH:MM，兼容"星期五18:00"/"18:00~21:00"等格式
        m = re.search(r"\d{1,2}:\d{2}", time_str)
        time_start = m.group() if m else ""
        dt = _parse_phycai_dt(date_str, time_start)
        if dt and dt > NOW:
            experiments.append({
                "name":     cell("name"),
                "dt":       dt,
                "room":     cell("room"),
                "time_str": time_str,
            })

    if not experiments:
        return None
    experiments.sort(key=lambda x: x["dt"])
    return experiments[0]


def _parse_phycai_dt(date_str: str, time_str: str) -> datetime | None:
    date_str = date_str.strip()
    time_str = time_str.strip()
    date_fmts = ["%Y-%m-%d", "%Y/%m/%d", "%Y年%m月%d日"]
    time_fmts = ["%H:%M", "%H时%M分", "%H:%M:%S"]
    for df in date_fmts:
        for tf in time_fmts:
            try:
                dt = datetime.strptime(f"{date_str} {time_str}", f"{df} {tf}")
                return dt.replace(tzinfo=CST)
            except ValueError:
                continue
    return None


# ── Platform 4: icourse163.org ────────────────────────────────────────────────

def _icourse_fill_form(page_or_frame, username: str, password: str) -> None:
    """在登录表单（页面或 iframe）中填写账号密码并提交。"""
    page_or_frame.locator("input[type='text'], input[type='tel'], input[placeholder*='手机'], input[placeholder*='邮箱']").first.fill(username)
    page_or_frame.locator("input[type='password']").first.fill(password)
    page_or_frame.locator("button.btn-login, .btn-submit, button[type='submit'], .f-btn-login").first.click()


def _icourse_login_with_creds(cfg: dict) -> dict | None:
    """
    用 .env 中的 MOOC_USERNAME / MOOC_PASSWORD 登录 icourse163。
    点击首页登录按钮 → 在弹出的 reg.icourse163.org iframe 中填写手机号+密码。
    成功则返回新 cookies dict 并写回 config.json，失败返回 None。
    """
    if not HAS_PLAYWRIGHT:
        return None
    try:
        from dotenv import load_dotenv  # type: ignore
        load_dotenv()
    except ImportError:
        pass
    username = os.environ.get("MOOC_USERNAME", "").strip()
    password = os.environ.get("MOOC_PASSWORD", "").strip()
    if not username or not password:
        return None

    print("[icourse163] 正在用账号密码登录…")
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True)
            ctx = browser.new_context()
            page = ctx.new_page()

            page.goto("https://www.icourse163.org/", wait_until="networkidle", timeout=40_000)
            page.wait_for_timeout(2000)

            # 关掉 AI 助手弹窗（如有）
            try:
                page.keyboard.press("Escape")
                page.wait_for_timeout(500)
            except Exception:
                pass

            # 点击"登录 | 注册"
            page.get_by_text("登录", exact=False).first.click()

            # 等待 reg.icourse163.org 的登录 iframe 出现
            page.wait_for_selector(
                "iframe[src*='reg.icourse163.org'][src*='index_dl2']",
                timeout=15_000,
            )
            page.wait_for_timeout(1000)

            # 找到登录 iframe（优先手机号登录，即 index_dl2）
            login_frame = None
            for frame in page.frames:
                if "reg.icourse163.org" in frame.url and "index_dl2" in frame.url:
                    if frame.locator("input[type='password']").count() > 0:
                        login_frame = frame
                        break

            if login_frame is None:
                print("[icourse163] 未找到登录 iframe")
                browser.close()
                return None

            # 填写手机号和密码（取第一个可见的输入框）
            txt_inputs = login_frame.locator(
                "input[type='text'], input[type='tel']"
            ).all()
            txt_field = next((i for i in txt_inputs if i.is_visible()), None)
            if txt_field is None:
                print("[icourse163] 未找到手机号输入框")
                browser.close()
                return None
            txt_field.fill(username)

            pwd_inputs = login_frame.locator("input[type='password']").all()
            pwd_field = next((i for i in pwd_inputs if i.is_visible()), None)
            if pwd_field is None:
                print("[icourse163] 未找到密码输入框")
                browser.close()
                return None
            pwd_field.fill(password)

            # 点击登录按钮（绿色「登 录」按钮）
            login_frame.get_by_text("登 录", exact=True).click()

            # 等待 modal 消失或页面刷新为已登录状态
            try:
                page.wait_for_function(
                    "() => !document.querySelector('iframe[src*=\"reg.icourse163.org\"]')",
                    timeout=20_000,
                )
            except Exception:
                pass

            page.wait_for_timeout(2000)

            new_cookies = {
                c["name"]: c["value"]
                for c in ctx.cookies()
                if "icourse163.org" in c.get("domain", "")
            }
            browser.close()

            if not new_cookies.get("NTESSTUDYSI"):
                print("[icourse163] 登录失败，请确认 MOOC_USERNAME / MOOC_PASSWORD 正确")
                return None

            cfg["icourse_cookies"] = new_cookies
            CONFIG_PATH.write_text(
                json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            print("[icourse163] ✓ 登录成功，已更新 cookies")
            return new_cookies

    except Exception as e:
        print(f"[icourse163] 登录出错：{e}")
        return None


def fetch_icourse(cfg: dict) -> list[dict]:
    """获取中国大学MOOC得分为0且未过期的测试。"""
    # 优先：用 .env 账号密码重新登录
    new_cookies = _icourse_login_with_creds(cfg)
    if new_cookies:
        cookies = new_cookies
    else:
        cookies = cfg.get("icourse_cookies", {})
        if not cookies or all(v.startswith("YOUR_") for v in cookies.values()):
            print("[icourse163] ⚠ 未配置 icourse_cookies 且无 .env 凭据，跳过")
            return []

    session = make_session(cookies, referer="https://www.icourse163.org/")
    results: list[dict] = []
    for course in ICOURSE_COURSES:
        results.extend(_fetch_icourse_one(session, course, cookies))
    return results


def _fetch_icourse_one(session: requests.Session, course: dict, cookies: dict) -> list[dict]:
    cname   = course["name"]
    term_id = course["term_id"]

    # 优先尝试 JSON RPC 接口（速度快、无需浏览器）
    rpc_result = _icourse_rpc(session, term_id)
    if rpc_result is not None:
        return _parse_icourse_rpc(rpc_result, cname)

    # 降级：用 Playwright 加载页面并拦截 RPC 响应（icourse163 是 SPA，HTML 无法直接解析）
    print(f"  [icourse163] RPC 直连失败，切换 Playwright 模式…")
    return _fetch_icourse_playwright(cookies, course)


def _icourse_rpc(session: requests.Session, term_id: int) -> dict | None:
    """尝试调用 icourse163 的 JSON RPC 接口获取课程结构。"""
    url = "https://www.icourse163.org/web/j/courseBean.getLastLearnedMocTermDto.rpc"
    try:
        r = session.post(
            url,
            data={"csrfKey": session.cookies.get("NTESSTUDYSI", ""), "termId": str(term_id)},
            timeout=15,
        )
        if r.status_code == 200 and "application/json" in r.headers.get("Content-Type", ""):
            data = r.json()
            if data.get("result"):
                return data["result"]
    except (requests.RequestException, json.JSONDecodeError):
        pass
    return None


def _parse_icourse_rpc(result: dict, cname: str) -> list[dict]:
    results = []
    # result 可能是 {"mocTermDto": {...}, ...} 或直接就是 mocTermDto
    moc = result.get("mocTermDto") or result

    # 从章节结构中查找测验单元 (contentType=5)
    for chapter in moc.get("chapters", []):
        for lesson in chapter.get("lessons", []):
            for unit in lesson.get("units", []):
                if unit.get("contentType") != 5:
                    continue
                score = unit.get("testScore")
                if score is not None and float(score) > 0:
                    continue
                deadline_ms = unit.get("deadline") or unit.get("testEndTime")
                if not deadline_ms:
                    continue
                due = datetime.fromtimestamp(int(deadline_ms) / 1000, tz=CST)
                if due < NOW:
                    continue
                results.append({
                    "platform": "icourse163",
                    "course": cname,
                    "name": unit.get("name") or "未知测试",
                    "due": due,
                    "submitted": False,
                })

    # 从 exams 列表中查找考试
    for exam in moc.get("exams") or []:
        score = exam.get("userScore") or exam.get("testScore")
        if score is not None and float(score) > 0:
            continue
        deadline_ms = exam.get("endTime") or exam.get("deadline")
        if not deadline_ms:
            continue
        due = datetime.fromtimestamp(int(deadline_ms) / 1000, tz=CST)
        if due < NOW:
            continue
        results.append({
            "platform": "icourse163",
            "course": cname,
            "name": exam.get("name") or exam.get("title") or "未知考试",
            "due": due,
            "submitted": False,
        })

    return results


def _fetch_icourse_playwright(cookies: dict, course: dict) -> list[dict]:
    """Playwright fallback：加载课程页面并拦截 RPC 响应，适用于 RPC 直连失败的情况。"""
    if not HAS_PLAYWRIGHT:
        print(f"  [icourse163] ⚠ Playwright 不可用，跳过 {course['name']}")
        return []

    cname = course["name"]
    pw_cookies = [
        {"name": k, "value": v, "domain": ".icourse163.org", "path": "/"}
        for k, v in cookies.items()
    ]

    captured: dict = {}

    def _on_response(resp):
        # 拦截任何包含课程结构数据的 RPC 响应
        if captured or "MocTermDto" not in resp.url:
            return
        try:
            body = resp.json()
            if body.get("result"):
                captured["result"] = body["result"]
        except Exception:
            pass

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        ctx = browser.new_context()
        ctx.add_cookies(pw_cookies)
        page = ctx.new_page()
        page.on("response", _on_response)
        try:
            page.goto(course["learn_url"], wait_until="networkidle", timeout=30_000)
            # networkidle 后再等一小段，确保异步 RPC 已发出
            page.wait_for_timeout(2000)
        except Exception as e:
            print(f"  [icourse163] Playwright {cname} 页面加载失败：{e}")
        finally:
            browser.close()

    if "result" in captured:
        return _parse_icourse_rpc(captured["result"], cname)

    print(f"  [icourse163] {cname} 未捕获到数据（请确认已登录 icourse163）")
    return []


# ── 输出格式化 ────────────────────────────────────────────────────────────────

def print_report(ddl_items: list[dict], lab: dict | None) -> None:
    print("\n===== 近期必做 DDL（按截止时间排序）=====")
    # 过滤掉已提交的
    pending = [x for x in ddl_items if not x.get("submitted")]
    if not pending:
        print("  （暂无即将到来的必做任务）")
    else:
        for item in pending:
            label    = deadline_label(item["due"])
            platform = f"[{item['platform']}]"
            print(f"⚠️  [{label}] {platform} {item['course']} · {item['name']}"
                  f"  截止：{fmt_due(item['due'])}")

    print("\n===== 下一次物理实验 =====")
    if not lab:
        print("  （未获取到实验安排，请检查 phycai_cookies 或页面结构）")
    else:
        dt      = lab["dt"]
        weekday = WEEKDAY_ZH[dt.weekday()]
        print(f"  {lab['name']}")
        print(f"  时间：{dt.strftime('%Y/%m/%d')} ({weekday}) {lab['time_str']}")
        print(f"  地点：{lab['room']}")
    print()


# ── 主入口 ────────────────────────────────────────────────────────────────────

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="多平台课程 DDL 聚合工具")
    p.add_argument("--skip", nargs="+", choices=["canvas", "aihaoke", "phycai", "icourse"],
                   default=[], help="跳过指定平台")
    p.add_argument("--canvas-only", action="store_true", help="仅抓取 Canvas")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    cfg  = load_config()

    skip = set(args.skip)
    if args.canvas_only:
        skip = {"aihaoke", "phycai", "icourse"}

    all_ddl: list[dict] = []

    if "canvas" not in skip:
        print("[*] 正在获取 Canvas 作业…")
        all_ddl.extend(fetch_canvas(cfg))

    if "aihaoke" not in skip:
        print("[*] 正在获取 aihaoke 任务…")
        all_ddl.extend(fetch_aihaoke(cfg))

    if "icourse" not in skip:
        print("[*] 正在获取 MOOC 测试…")
        all_ddl.extend(fetch_icourse(cfg))

    all_ddl.sort(key=lambda x: x["due"])

    lab: dict | None = None
    if "phycai" not in skip:
        print("[*] 正在获取物理实验安排…")
        lab = fetch_phycai(cfg)

    print_report(all_ddl, lab)


if __name__ == "__main__":
    main()
