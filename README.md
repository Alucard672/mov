# GoFilm Android v0.2

Kotlin + Jetpack Compose + Retrofit + Media3 ExoPlayer + Room

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

## 默认服务器

```text
http://120.27.148.45/api/
```

Nginx 把 `/api/` 反代到后端，**不要漏掉 `/api/`**。

## 打开工程

1. Android Studio 打开 `Desktop/GoFilmAndroid`
2. HTTP Proxy 选 **No proxy**（国内镜像已配）
3. Sync → Run

## 服务器未就绪时

App 会显示友好错误（404/502/超时），不影响界面使用。  
部署完成并采集数据后，下拉刷新首页即可。
