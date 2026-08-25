# pi-idea-plugin

IDEA 插件：把编辑器中**当前选中的代码**实时推送给 [Pi coding agent](https://github.com/andrewshururur/pi)（或任意本机进程），选中即上下文，无需复制粘贴。

对标 Claude Code 的 IDE 集成体验，为 Pi 实现。

## 工作原理

```
IDEA 选中代码
   │ SelectionListener（300ms 防抖）
   ▼
PiWebSocketServer（插件核心）
   ├─ HTTP  :127.0.0.1:19232/api/selection   实时查询当前选区
   ├─ HTTP  :127.0.0.1:19232/api/health      健康检查
   ├─ WS    :127.0.0.1:19233                 选区变化即时推送（无轮询）
   └─ 文件  ~/.pi/selection.md               降级方案，Pi 扩展直接读文件
```

选区数据：文件路径/文件名/选中内容/起止行列/语言。

服务仅监听 127.0.0.1，不对外网暴露。

## 安装

### 方式一：源码构建

```bash
git clone git@github.com:Colin606/pi-idea-plugin.git
cd pi-idea-plugin
./gradlew build
```

产物在 `build/distributions/pi-idea-plugin-1.0.0.zip`，IDEA → Settings → Plugins → ⚙ → Install Plugin from Disk。

要求：JDK 21，IDEA 2024.2+（sinceBuild 242）。

### 方式二：Pi 侧扩展

插件只负责“推”，Pi 侧还需要一个消费扩展：读 `~/.pi/selection.md`，提交 prompt 时注入 `<idea-selection>` 上下文，并在 TUI 显示当前选中。

本仓库 [`pi-extension/idea-selection.ts`](pi-extension/idea-selection.ts) 就是现成实现，安装：

```bash
# 1. 复制到 pi 全局扩展目录
mkdir -p ~/.pi/agent/extensions
cp pi-extension/idea-selection.ts ~/.pi/agent/extensions/

# 2. 重启 pi 会话生效（扩展随会话加载）
```

扩展提供的能力：

| 能力 | 说明 |
|------|------|
| 上下文注入 | 每次提交 prompt 前，把当前选区以 `<idea-selection>` 标记自动注入，模型直接可见 |
| TUI 实时 widget | 编辑区上方显示 `IDEA selection: Xxx.java (起-止行)`，随选区变化实时刷新（150ms 防抖） |
| 过期保护 | 选中超 24 小时不再注入，避免陈旧内容误导 |
| 手动查看 | pi 内执行 `/selection` 命令查看当前注入的选中内容 |

> 注：扩展当前走文件降级方案（watch selection.md），插件同源的 HTTP/WS 接口（19232/19233）已就绪，后续可切换为长连接推送。

## 使用

1. IDEA 打开任意项目（插件随项目启动自动起服务）
2. 选中一段代码
3. 在 Pi 里直接提问——"我选的代码有什么问题"——选区已自动在上下文里

## 配置端口（可选）

```bash
# HTTP 查询端口默认 19232，WS 推送端口默认 19233，见 PiWebSocketServer
```

## License

MIT
