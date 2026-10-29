# 小枭背词 · 云同步服务器（自托管版）

> **Self-hosted sync server for the Xiaoxiao Vocab app.** Run this tiny Java server on your
> own machine/VPS and the app (PWA or Android APK) will sync your word library and study
> progress to *your* server only. One passcode = one isolated data slot, so a single server
> can serve several people. No database, no dependencies — a single `.java` file on JDK 17+.

「小枭背词」App 的可选云同步后端：一个零依赖的单文件 Java 小服务器。
把词库和复习进度只存在你自己的服务器上，手机和电脑多设备互相同步。

## 功能

- `GET/POST /api/sync/store`：云同步接口（App 里填服务器地址＋口令即可用）
- `/app/`：同源托管背词网页版（浏览器打开就能用，绕开 https 页面调 http 接口的浏览器限制）
- **多槽位**：每个口令一份独立数据，互不干扰。站长用 `.env` 里的 `VOCAB_PASS`；
  想让家人/朋友共用同一台服务器，把他们的口令逐行写进 `data/passes.txt` 即可（不用重启）
- 每次覆盖前自动备份（`data/sync-slot-backups/`，每槽位留最近 10 份），原子写入不怕断电

## 运行方式 A：Docker（推荐）

需要一台装了 Docker（带 compose）的 Linux 服务器：

1. 把本目录整个传到服务器，进入目录
2. 复制口令文件并改成你自己的口令：`cp .env.example .env`，编辑其中的 `VOCAB_PASS`
3. 启动：`docker compose up -d --build`

## 运行方式 B：直接用 Java（免 Docker）

需要 JDK 17 或更高版本：

```
javac -encoding UTF-8 SyncServer.java
VOCAB_PASS=你的口令 PORT=8081 DATA_DIR=./data java SyncServer
```

（Windows 下先用 `set` 或在系统环境变量里设置同名变量。）

## 验证

```
curl -H "X-Vocab-Pass: 你的口令" http://localhost:8081/api/sync/store
```

返回 `{"exists":false}` 就是通了（还没有数据同步上来过）。
云服务器记得在安全组/防火墙放行你用的端口（示例为 8081，想换端口改 compose 映射或 `PORT` 环境变量）。

## 在 App 里怎么填

打开背词 App（或浏览器打开 `http://你的服务器IP:8081/app/`）→「更多 → 云同步」：

- 服务器地址：`http://你的服务器IP:8081`（在服务器自己的 /app 页面上使用时，地址留空即可）
- 口令：服务器 `.env` 里的 `VOCAB_PASS`（共用者用 passes.txt 里登记的口令）

保存后点「立即同步」。有数据的设备第一次用「仅推送」上传，新设备第一次用「仅拉取」接入，之后用「立即同步」即可。

## 进阶：配域名 + HTTPS

若给服务器配上域名和 HTTPS（例如用 Caddy 或 Nginx 反向代理到本服务），
GitHub Pages 上的网页版也能直接同步（https 页面无法调用 http 接口，这是浏览器规定，与本服务无关）。

## 数据在哪

- 每个人的数据：`data/sync-slots/<口令的 SHA-256>.json`（文件名是哈希，不含明文口令）
- 备份：`data/sync-slot-backups/`；整个 `data/` 目录拷走即完整备份

## 安全边界（请先看清）

- 直接暴露在公网时传输是明文 HTTP：口令和数据不加密。背词数据自用没问题，
  **不要存任何敏感信息**，口令别与其它重要账号共用、别太简单。
- 知道口令＝能读写那一份数据。不要把口令公开发布。
- 在意的同学请走上面的 HTTPS 方案，或只在局域网/VPN 内使用。

## License

MIT
