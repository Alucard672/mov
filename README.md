# GoFilm Android（Alucard 影视）

Kotlin + Jetpack Compose + Retrofit + Media3 ExoPlayer + Room

## 域名规划（子域名，不暴露内部 API 路径）

| 子域名 | 用途 | 用户看到的路径 |
|---|---|---|
| `https://home.alucard.top/` | Web 前台 | `/` |
| `https://api.alucard.top/` | App 片库网关（根路径即业务） | `/`、`/r`、`/d`、`/t/...`、`/e` |
| `https://down.alucard.top/` | App 下载落地页 | `/` |
| `https://ota.alucard.top/` | OTA 版本与 APK | `/v.json`、`/*.apk` |
| `https://ads.alucard.top/` | 广告合作说明 | `/` |
| `https://admin.alucard.top/` | 管理后台 | `/` |
| `https://ops.alucard.top/` | 运营工具中心 | `/` |
| `https://daily.alucard.top/` | 每日更新登记 | `/` |
| `https://stats.alucard.top/` | 用户统计看板 | `/`、`/get` |

### App 默认配置

```text
片库：  https://api.alucard.top/
OTA：   https://ota.alucard.top/v.json
下载页：https://down.alucard.top/
```

短路径说明（仅 App / 内网，不在对外文案中宣传）：

| 短路径 | 含义 |
|---|---|
| `POST /r` | 链接解析 |
| `POST /d` | 服务端代下 |
| `GET /f/{id}/{name}` | 代下文件 |
| `GET /t/{hash}.torrent` | 磁力代理 |
| `POST /e` | 埋点事件 |

Nginx 配置草稿：`server/nginx/alucard-domains.conf`  
客户端统一入口：`ServerConfig` + `BuildConfig` 域名字段。

### 旧路径兼容

- 旧 `…/api/` 默认地址会在 App 内自动迁移到 `https://api.alucard.top/`
- Nginx 仍可保留 `/api/` 反代，兼容未更新客户端
- 旧 `down…/daily`、`/stats`、`/ads`、`/app/version.json` 建议 301 到新子域

## 功能

| 模块 | 状态 |
|---|---|
| 首页（热播/更新/下拉刷新） | ✅ |
| 继续观看（读本地历史） | ✅ |
| 分类浏览 | ✅ |
| 搜索 | ✅ |
| 详情（多源/选集） | ✅ |
| 收藏（本机 Room） | ✅ |
| 播放（HLS / 换源 / 上下集） | ✅ |
| 播放进度记忆 | ✅ |
| 观看历史 | ✅ |
| 服务器设置 + 连通测试 | ✅ |
| 强制直连（不走 Clash 代理） | ✅ |

## 打开工程

1. Android Studio 打开本仓库
2. HTTP Proxy 选 **No proxy**（国内镜像已配）
3. Sync → 使用 **assembleRelease / installRelease**（工程已禁用可调试 debug 包）

## 服务器未就绪时

App 会显示友好错误（404/502/超时），不影响界面使用。  
部署完成并采集数据后，下拉刷新首页即可。

## 部署注意

1. 先配 DNS + 证书（推荐 `*.alucard.top`）
2. 应用 `server/nginx/alucard-domains.conf`（按实际上游端口微调）
3. 静态页放到 Nginx 对应 root；`version.json` 同步到 ota 目录，文件名可用 `v.json` 或 alias
4. 重启 ytdlp / stats / daily 服务后再发 App
