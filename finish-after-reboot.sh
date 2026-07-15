#!/usr/bin/env bash
# 服务器卡死后：在【本机 Mac】执行此脚本（需能 SSH）
# bash ~/Desktop/GoFilmAndroid/finish-after-reboot.sh
set -euo pipefail
KEY="$HOME/.ssh/id_ed25519"
HOST="root@120.27.148.45"
BIN="/tmp/gofilm-linux-amd64"

if [[ ! -f "$BIN" ]]; then
  echo "本机无二进制，开始交叉编译..."
  TMP=$(mktemp -d)
  git clone --depth 1 https://github.com/ProudMuBai/GoFilm.git "$TMP/GoFilm"
  (cd "$TMP/GoFilm/server" && \
    GOPROXY=https://goproxy.cn,direct GOSUMDB=off CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -ldflags="-s -w" -o "$BIN" ./main.go)
fi

echo "上传二进制..."
scp -i "$KEY" -o ConnectTimeout=20 "$BIN" "$HOST:/opt/film/gofilm-bin"

ssh -i "$KEY" -o ConnectTimeout=20 "$HOST" 'bash -s' << 'REMOTE'
set -euo pipefail
cd /opt/film
chmod +x gofilm-bin

cat > Dockerfile << 'EOF'
FROM alpine:3.20
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories \
    && apk add --no-cache ca-certificates tzdata \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime || true
COPY gofilm-bin /gofilm
RUN chmod +x /gofilm
EXPOSE 3601
ENTRYPOINT ["/gofilm"]
EOF

systemctl stop nginx 2>/dev/null || true
docker compose build film
docker compose up -d
sleep 30
docker compose ps
curl -sS -m 10 http://127.0.0.1:3601/config/basic || true
echo
curl -sS -m 10 http://127.0.0.1/api/config/basic || true
echo
echo "完成。后台: http://$(curl -s ifconfig.me)/manage  admin/admin"
REMOTE
