# ============================================
# HertzBeat 前端 Dockerfile
# 多阶段构建：Node 构建 → Nginx 部署
# ============================================

# 阶段 1: 构建前端
FROM node:18-alpine AS builder

WORKDIR /app

# 先复制依赖定义文件，利用 Docker 缓存
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./

# 安装 pnpm 并安装依赖
RUN corepack enable && corepack prepare pnpm@latest --activate \
    && pnpm install --frozen-lockfile

# 复制源代码并构建
COPY . .
RUN pnpm run build

# 阶段 2: Nginx 部署
FROM nginx:1.25-alpine

# 移除默认配置
RUN rm /etc/nginx/conf.d/default.conf

# 复制自定义 Nginx 配置
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf

# 从构建阶段复制产物到 Nginx 目录
COPY --from=builder /app/dist /usr/share/nginx/html

# 暴露端口
EXPOSE 80

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
