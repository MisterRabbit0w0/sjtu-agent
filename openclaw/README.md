# sjtu-agent × OpenClaw 接入微信

将 sjtu-agent 的全部能力（查 DDL、查成绩、提交作业、课表、提醒等）注册为 OpenClaw 的 MCP 工具，
然后通过 OpenClaw 的 ClawBot 插件接入微信，即可在微信中直接与 SJTU Assistant 对话。

---

## 前置条件

| 条件 | 说明 |
|------|------|
| sjtu-agent 已配置 | 先完成主项目的 `sjtu-agent setup`，确保 `config.json` 中各平台凭据已填写 |
| Node.js ≥ 18 | 用于安装 OpenClaw |
| iOS 微信 ≥ 8.0.70 | ClawBot 插件的最低要求 |

---

## 步骤一：安装 OpenClaw

```bash
npm install -g openclaw@latest --registry=https://registry.npmmirror.com
```

验证安装：

```bash
openclaw --version
```

---

## 步骤二：注册 sjtu-agent 为 MCP 工具

将本目录下的 `openclaw.config.json` 内容合并到 OpenClaw 的配置文件中。

**自动写入（推荐）：**

```bash
# macOS / Linux
python3 setup_openclaw.py

# Windows
python setup_openclaw.py
```

**手动写入：**

找到 OpenClaw 配置目录（通常是 `~/.openclaw/config.json`），添加如下字段：

```json
{
  "mcpServers": {
    "sjtu-agent": {
      "command": "python3",
      "args": ["<sjtu-agent 项目绝对路径>/mcp_server.py"],
      "description": "SJTU 课程助手：查 DDL、查成绩、提交作业、课表"
    }
  }
}
```

> ⚠️ 将 `<sjtu-agent 项目绝对路径>` 替换为实际路径，例如 `/Users/zhaigong/Projects/sjtu-agent`。

---

## 步骤三：接入微信（ClawBot 插件）

**macOS / Linux：**

```bash
npx -y @tencent-weixin/openclaw-weixin-cli@latest install
```

终端出现二维码后，用 iPhone 微信扫码授权。

**Windows（npx 报错时）：**

```bash
openclaw plugins install "@tencent-weixin/openclaw-weixin"
openclaw config set plugins.entries.openclaw-weixin.enabled true
openclaw channels login --channel openclaw-weixin
```

---

## 步骤四：启动 OpenClaw

```bash
openclaw start
```

---

## 步骤五：在微信中使用

在微信搜索「ClawBot」，打开聊天窗口，直接发消息即可：

```
我今天有什么 DDL？
帮我查一下数学成绩
我下次物理实验是什么时候？
提醒我明天 10 点打电话给保卫处
```

---

## 可用工具说明

| 工具名 | 功能 |
|--------|------|
| `get_ddls` | 获取所有平台（Canvas、aihaoke、MOOC）未完成的 DDL |
| `get_next_lab` | 获取下一次物理实验安排 |
| `get_all` | 一次性获取 DDL + 物理实验汇总 |

> 更多工具（查成绩、查课表、浏览交大门户等）由 sjtu-agent 主进程（`agent.py`）提供，
> 通过上面注册的 MCP 接口统一对外暴露。

---

## 故障排查

**Q：微信消息有回复，但说"暂不支持该功能"**  
A：检查 `mcp_server.py` 是否能正常启动：`python3 mcp_server.py --http --port 8765`

**Q：扫码后显示连接失败**  
A：确保 `openclaw start` 已在运行，且网络正常

**Q：openclaw 命令找不到**  
A：执行 `npm install -g openclaw@latest` 后重新打开终端
