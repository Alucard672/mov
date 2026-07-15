#!/usr/bin/env bash
# GoFilm 修复版一键部署（解决：Go1.25 / goproxy / 网络 / compose 网络定义）
# 在服务器上以 root 执行：
#   bash /root/fix-deploy-gofilm.sh
set -euo pipefail

echo "======== GoFilm 修复部署开始 ========"
[[ "$(id -u)" -eq 0 ]] || { echo "请用 root 运行"; exit 1; }

export DEBIAN_FRONTEND=noninteractive

# ---------- 基础 ----------
apt-get update -y
apt-get install -y ca-certificates curl git wget

if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
  systemctl enable docker
  systemctl start docker
fi

# 停掉占用 80 的系统 nginx
systemctl stop nginx 2>/dev/null || true
systemctl disable nginx 2>/dev/null || true

# 小内存
grep -q 'vm.overcommit_memory' /etc/sysctl.conf || echo 'vm.overcommit_memory = 1' >> /etc/sysctl.conf
sysctl vm.overcommit_memory=1 || true

# ---------- 代码 ----------
if [[ ! -d /opt/film/server ]]; then
  TMP=$(mktemp -d)
  cd "$TMP"
  git clone --depth 1 https://github.com/ProudMuBai/GoFilm.git
  rm -rf /opt/film
  mkdir -p /opt
  cp -a GoFilm/film /opt/film
  rm -rf "$TMP"
fi

cd /opt/film
mkdir -p data/nginx/logs data/redis/data

# ---------- Dockerfile（Go1.25 + 国内代理）----------
cat > Dockerfile << 'EOF'
FROM golang:1.25-alpine AS builder

RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories \
    && apk add --no-cache ca-certificates git

WORKDIR /build

ENV GOPROXY=https://goproxy.cn,direct \
    GOSUMDB=off \
    CGO_ENABLED=0 \
    GOOS=linux \
    GOARCH=amd64 \
    GOTOOLCHAIN=local

COPY ./server/go.mod ./server/go.sum ./
RUN go mod download

COPY ./server .
RUN go build -ldflags="-s -w" -o /build/gofilm ./main.go

FROM alpine:3.20
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories \
    && apk add --no-cache ca-certificates tzdata \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime || true

COPY --from=builder /build/gofilm /gofilm
EXPOSE 3601
ENTRYPOINT ["/gofilm"]
EOF

# ---------- docker-compose（完整 networks，无 ./main）----------
cat > docker-compose.yml << 'EOF'
services:
  nginx:
    container_name: film_nginx
    image: nginx:1.27-alpine
    restart: always
    ports:
      - "80:80"
    volumes:
      - /opt/film/data/nginx/html:/usr/share/nginx/html
      - /opt/film/data/nginx/nginx.conf:/etc/nginx/nginx.conf
      - /opt/film/data/nginx/logs:/var/log/nginx
    networks:
      - film-network
    depends_on:
      - film

  film:
    container_name: film_api
    build:
      context: .
      dockerfile: Dockerfile
    restart: always
    ports:
      - "3601:3601"
    networks:
      - film-network
    depends_on:
      - mysql
      - redis

  mysql:
    container_name: film_mysql
    image: mysql:8.0
    restart: always
    ports:
      - "3610:3306"
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: FilmSite
    networks:
      - film-network
    command:
      - mysqld
      - --default-storage-engine=INNODB
      - --default-time-zone=+8:00
      - --lower-case-table-names=1
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci

  redis:
    container_name: film_redis
    image: redis:7-alpine
    restart: always
    ports:
      - "3620:6379"
    volumes:
      - /opt/film/data/redis/redis.conf:/etc/redis/redis.conf
      - /opt/film/data/redis/data:/data
    networks:
      - film-network
    command: redis-server /etc/redis/redis.conf

networks:
  film-network:
    driver: bridge
EOF

# redis 配置兜底（没有则写简单版）
if [[ ! -f data/redis/redis.conf ]]; then
  cat > data/redis/redis.conf << 'RDEOF'
bind 0.0.0.0
protected-mode no
port 6379
daemonize no
appendonly yes
RDEOF
fi

# nginx 配置兜底
if [[ ! -f data/nginx/nginx.conf ]]; then
  cat > data/nginx/nginx.conf << 'NGEOF'
worker_processes  1;
events { worker_connections  1024; }
http {
    include       mime.types;
    default_type  application/octet-stream;
    sendfile        on;
    keepalive_timeout  65;
    client_max_body_size 200m;
    server {
        listen       80;
        server_name  localhost;
        location / {
            root   /usr/share/nginx/html;
            try_files $uri $uri/ /index.html;
            index  index.html index.htm;
        }
        location /api/ {
            proxy_pass http://film:3601/;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
        }
    }
}
NGEOF
fi

# 后端配置：很多版本写死 mysql:端口，确认 server 内配置
# 若 DataConfig 使用 docker 服务名，保持默认即可

echo "======== 开始构建 film 镜像（含 goproxy + go1.25）========"
# 若 1.25 镜像不存在，自动改 latest
if ! docker pull golang:1.25-alpine; then
  echo "golang:1.25-alpine 拉取失败，改用 golang:latest"
  sed -i 's|golang:1.25-alpine|golang:latest|' Dockerfile
fi

docker compose build --no-cache film
docker compose up -d

echo "======== 等待初始化 ========"
sleep 25
docker compose ps
echo
echo "---- film logs ----"
docker compose logs --tail=40 film || true
echo
echo "---- 本地探测 ----"
curl -sS -m 8 http://127.0.0.1:3601/config/basic || true
echo
curl -sS -m 8 http://127.0.0.1/api/config/basic || true
echo
echo "======== 完成 ========"
echo "管理后台: http://$(curl -s ifconfig.me || echo 公网IP)/manage"
echo "账号密码: admin / admin （登录后立刻修改）"
echo "App 地址: http://公网IP/api/"
echo
echo "若 film 启动失败，多半是连不上 MySQL/Redis，执行:"
echo "  cd /opt/film && docker compose logs -f film"
