#!/bin/bash
# ============================================
# HertzBeat 前端部署脚本
# 支持: 本地 Nginx 部署 / Docker 部署
# ============================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WEB_APP_DIR="${SCRIPT_DIR}/web-app"
DIST_DIR="${WEB_APP_DIR}/dist"
NGINX_HTML_DIR="/usr/share/nginx/html/hertzbeat"

log_info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# ========== 1. 环境检查 ==========
check_env() {
    log_info "检查环境..."

    if ! command -v node &> /dev/null; then
        log_error "未检测到 Node.js，请先安装: https://nodejs.org/"
        exit 1
    fi

    if ! command -v npm &> /dev/null && ! command -v pnpm &> /dev/null; then
        log_error "未检测到 npm 或 pnpm"
        exit 1
    fi

    log_info "Node.js 版本: $(node --version)"
}

# ========== 2. 安装依赖 ==========
install_deps() {
    log_info "安装前端依赖..."
    cd "$WEB_APP_DIR"

    if [ -f "pnpm-lock.yaml" ]; then
        corepack enable && corepack prepare pnpm@latest --activate
        pnpm install
    else
        npm install
    fi
}

# ========== 3. 构建 ==========
build() {
    log_info "开始构建生产包..."
    cd "$WEB_APP_DIR"

    if [ -f "pnpm-lock.yaml" ]; then
        pnpm run build
    else
        npm run build
    fi

    if [ ! -d "$DIST_DIR" ]; then
        log_error "构建失败：未找到 dist 目录"
        exit 1
    fi

    log_info "构建完成！产物目录: ${DIST_DIR}"
}

# ========== 4. 部署到本地 Nginx ==========
deploy_nginx() {
    log_info "部署到本地 Nginx..."

    # 检查 Nginx 是否安装
    if ! command -v nginx &> /dev/null; then
        log_warn "未安装 Nginx，请手动部署或使用 Docker 方式"
        log_info "手动部署步骤:"
        echo "  1. 复制 ${DIST_DIR}/* 到 ${NGINX_HTML_DIR}/"
        echo "  2. 复制 ${WEB_APP_DIR}/deploy/nginx.conf 到 /etc/nginx/conf.d/hertzbeat.conf"
        echo "  3. nginx -t && nginx -s reload"
        return
    fi

    # 创建目标目录
    sudo mkdir -p "$NGINX_HTML_DIR"

    # 清空并复制新文件
    sudo rm -rf "${NGINX_HTML_DIR:?}"/*
    sudo cp -r "$DIST_DIR"/. "$NGINX_HTML_DIR/"

    # 复制 Nginx 配置
    sudo cp "$WEB_APP_DIR/deploy/nginx.conf" /etc/nginx/conf.d/hertzbeat.conf

    # 测试配置并重载
    sudo nginx -t && sudo nginx -s reload

    log_info "Nginx 部署完成！访问 http://localhost"
}

# ========== 5. Docker 部署 ==========
deploy_docker() {
    log_info "Docker 构建与部署..."

    if ! command -v docker &> /dev/null; then
        log_error "未检测到 Docker"
        exit 1
    fi

    # 仅构建前端镜像
    docker build -t hertzbeat-frontend:latest -f "$WEB_APP_DIR/Dockerfile" "$WEB_APP_DIR"

    log_info "前端镜像构建完成: hertzbeat-frontend:latest"

    # 如果有 docker-compose，询问是否一键部署
    if [ -f "${SCRIPT_DIR}/docker-compose.yml" ]; then
        read -rp "是否使用 docker-compose 一键部署前后端？(y/n): " choice
        if [[ "$choice" =~ ^[Yy]$ ]]; then
            cd "$SCRIPT_DIR"
            docker compose up -d --build
            log_info "一键部署完成！"
            echo ""
            echo "  前端地址: http://localhost:80"
            echo "  后端地址: http://localhost:1157"
            echo ""
            echo "常用命令:"
            echo "  查看日志:   docker compose logs -f"
            echo "  停止服务:   docker compose down"
            echo "  重启服务:   docker compose restart"
        fi
    fi
}

# ========== 主流程 ==========
main() {
    echo ""
    echo "============================================="
    echo "       HertzBeat 前端部署工具"
    echo "============================================="
    echo ""

    check_env
    install_deps
    build

    echo ""
    echo "请选择部署方式:"
    echo "  1) 部署到本地 Nginx"
    echo "  2) Docker 镜像构建（含可选一键部署）"
    echo "  0) 仅打包（不部署）"
    echo ""
    read -rp "请输入选项 [0-2]: " mode

    case $mode in
        1) deploy_nginx ;;
        2) deploy_docker ;;
        0) log_info "仅打包完成，产物在: ${DIST_DIR}" ;;
        *) log_error "无效选项" ;;
    esac

    echo ""
    log_info "全部执行完毕！"
}

main "$@"
