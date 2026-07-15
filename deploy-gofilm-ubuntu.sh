#!/usr/bin/env bash
# GoFilm 一键部署脚本（Ubuntu + Docker）
# 用法：
#   1) scp 到服务器后: bash deploy-gofilm-ubuntu.sh
#   2) 或在服务器: curl -fsSL ... | bash  （若你自行托管脚本）
set -euo pipefail

echo "======== GoFilm 部署开始 ========"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 root 运行: sudo bash $0"
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive

echo "[1/7] 安装基础工具..."
apt-get update -y
apt-get install -y ca-certificates curl git wget gnupg lsb-release

echo "[2/7] 安装 Docker（若未安装）..."
if ! command -v docker >/dev/null 2>&1; then
  # 官方安装脚本（Ubuntu）
  curl -fsSL https://get.docker.com | sh
  systemctl enable docker
  systemctl start docker
else
  echo "Docker 已存在: $(docker --version)"
fi

# compose 插件
if ! docker compose version >/dev/null 2>&1; then
  apt-get install -y docker-compose-plugin || true
fi

echo "[3/7] 处理 80 端口占用（若本机 nginx 冲突）..."
if systemctl is-active --quiet nginx 2>/dev/null; then
  echo "检测到系统 nginx 正在运行，将停止并禁用（避免占 80 端口）"
  systemctl stop nginx || true
  systemctl disable nginx || true
fi
# 其它可能占用 80 的进程提示
if ss -lntp 2>/dev/null | grep -q ':80 '; then
  echo "警告: 仍有进程监听 80 端口:"
  ss -lntp | grep ':80 ' || true
  echo "若 docker 启动失败，请手动释放 80 端口"
fi

echo "[4/7] 小内存优化（Redis）..."
if ! grep -q 'vm.overcommit_memory' /etc/sysctl.conf 2>/dev/null; then
  echo 'vm.overcommit_memory = 1' >> /etc/sysctl.conf
fi
sysctl vm.overcommit_memory=1 || true

echo "[5/7] 下载 GoFilm 到 /opt/film ..."
TMP_DIR=$(mktemp -d)
cd "$TMP_DIR"
# 只需要 film 目录；用 sparse 不太方便，直接浅克隆后拷贝
git clone --depth 1 https://github.com/ProudMuBai/GoFilm.git
rm -rf /opt/film
mkdir -p /opt
cp -a GoFilm/film /opt/film
cd /opt/film

# 若 docker-compose 里 command 与 Dockerfile 不一致，做兼容修正
if [[ -f Dockerfile ]] && grep -q 'ENTRYPOINT \["/gofilm"\]' Dockerfile 2>/dev/null; then
  # 新版镜像入口是 /gofilm，去掉错误的 ./main 覆盖
  if grep -q "./main" docker-compose.yml 2>/dev/null; then
    echo "修正 docker-compose.yml 启动命令以匹配 Dockerfile..."
    # 删除 command 覆盖，使用镜像默认 ENTRYPOINT
    python3 - <<'PY' || true
from pathlib import Path
p = Path("docker-compose.yml")
text = p.read_text()
# 简单去掉 film 服务下的 command 段
import re
text2 = re.sub(
    r"(  film:\n(?:.*\n)*?)(    command: \[\n(?:.*\n)*?    \]\n)",
    r"\1",
    text,
    count=1,
)
if text2 != text:
    p.write_text(text2)
    print("已移除 film.command 覆盖")
else:
    print("未改 command（可能已正确）")
PY
  fi
fi

echo "[6/7] 构建并启动容器（首次较慢，可能 5~15 分钟）..."
docker compose build
docker compose up -d

echo "[7/7] 等待服务就绪..."
sleep 15
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'

echo
echo "======== 部署命令已执行完毕 ========"
echo "请等待 3~8 分钟让后端初始化。"
echo
echo "浏览器访问："
echo "  管理后台: http://$(curl -s ifconfig.me 2>/dev/null || echo 你的公网IP)/manage"
echo "  默认账号: admin / admin  （登录后立刻改密码）"
echo "  前台:     http://公网IP/index"
echo
echo "安卓 App 服务器地址填写:"
echo "  http://公网IP/"
echo
echo "常用命令:"
echo "  cd /opt/film && docker compose logs -f film"
echo "  cd /opt/film && docker compose ps"
echo "  cd /opt/film && docker compose restart"
echo

# 清理临时目录
rm -rf "$TMP_DIR"
