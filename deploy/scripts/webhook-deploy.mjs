#!/usr/bin/env node
// ============================================================
// Gitee Webhook 接收 + 自动部署脚本（Node.js 实现）
//
// 工作流：
//   1. 监听 28082，接收 Gitee push 事件
//   2. 校验 HMAC 签名（避免被恶意 POST）
//   3. 解析 branch，只对 main 触发
//   4. cd /opt/metric-staging && git pull origin main
//   5. 前端 build + docker compose build + up -d
//   6. 健康检查，失败自动回滚到上一个可用 VERSION
//
// 部署方式（由 setup-staging.sh 自动完成）：
//   - 文件位置：/opt/metric-staging/deploy/scripts/webhook-deploy.mjs
//   - systemd 服务：/etc/systemd/system/webhook-deploy.service
//   - 启动：systemctl enable --now webhook-deploy
//
// Gitee Webhook 配置（仓库 → 管理 → Webhooks）：
//   URL:    http://<staging-host>:28082/gitee-webhook
//   事件:   Push
//   Secret: 与 /opt/metric-staging/.env.staging 的 GITEE_WEBHOOK_SECRET 一致
// ============================================================

import http from 'node:http';
import crypto from 'node:crypto';
import { exec } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const PORT = parseInt(process.env.WEBHOOK_PORT || '28082', 10);
const WEBHOOK_SECRET = process.env.GITEE_WEBHOOK_SECRET || '';
const DEPLOY_BRANCH = process.env.GITEE_BRANCH || 'main';
const DEPLOY_DIR = process.env.DEPLOY_REPO_DIR || '/opt/metric-staging';
const LOG_DIR = process.env.LOG_DIR || '/opt/metric-staging/logs';
const LOG_FILE = path.join(LOG_DIR, 'webhook.log');

// 简易日志（写到文件 + stdout）
function log(level, msg) {
    const ts = new Date().toISOString();
    const line = `${ts} [${level}] ${msg}\n`;
    fs.appendFileSync(LOG_FILE, line);
    process.stdout.write(line);
}

// 执行命令（返回 Promise）
function run(cmd, opts = {}) {
    return new Promise((resolve, reject) => {
        log('INFO', `exec: ${cmd}`);
        exec(cmd, { cwd: DEPLOY_DIR, maxBuffer: 10 * 1024 * 1024, ...opts }, (err, stdout, stderr) => {
            if (stdout) log('INFO', `stdout: ${stdout.trim().slice(-2000)}`);
            if (stderr) log('INFO', `stderr: ${stderr.trim().slice(-2000)}`);
            if (err) return reject(err);
            resolve(stdout);
        });
    });
}

// Gitee HMAC 签名校验（Gitee X-Gitee-Token = sha256(secret) 直接签名 body）
function verifySignature(rawBody, signature) {
    if (!WEBHOOK_SECRET) {
        log('WARN', 'WEBHOOK_SECRET 未配置，签名校验已禁用（仅 dev 用）');
        return true;
    }
    if (!signature) return false;
    const expected = crypto
        .createHmac('sha256', WEBHOOK_SECRET)
        .update(rawBody)
        .digest('hex');
    const a = Buffer.from(expected);
    const b = Buffer.from(signature);
    if (a.length !== b.length) return false;
    return crypto.timingSafeEqual(a, b);
}

// 从 .env.staging 读 VERSION
function readCurrentVersion() {
    const envFile = path.join(DEPLOY_DIR, '.env.staging');
    if (!fs.existsSync(envFile)) return 'staging-latest';
    const m = fs.readFileSync(envFile, 'utf-8').match(/^VERSION=(.+)$/m);
    return m ? m[1].trim() : 'staging-latest';
}

// 写 VERSION 到 .env.staging
function writeVersion(version) {
    const envFile = path.join(DEPLOY_DIR, '.env.staging');
    if (!fs.existsSync(envFile)) return;
    const content = fs.readFileSync(envFile, 'utf-8');
    const updated = content.replace(/^VERSION=.*$/m, `VERSION=${version}`);
    fs.writeFileSync(envFile, updated);
}

// 部署流程
async function deploy(commitMsg) {
    log('INFO', `start deploy: ${commitMsg}`);

    const prevVersion = readCurrentVersion();
    const newVersion = `staging-${Date.now()}`;
    log('INFO', `version: ${prevVersion} → ${newVersion}`);

    try {
        // 1. git pull
        await run(`git pull origin ${DEPLOY_BRANCH}`);

        // 2. 前端 build（产出 dist/，volume bind mount 到 nginx 容器）
        if (fs.existsSync(path.join(DEPLOY_DIR, 'frontend'))) {
            log('INFO', 'building frontend…');
            await run('npm ci --no-audit --no-fund', { cwd: path.join(DEPLOY_DIR, 'frontend') });
            await run('npm run build', { cwd: path.join(DEPLOY_DIR, 'frontend') });
        }

        // 3. 更新 VERSION（让 compose 拉到新镜像 tag）
        writeVersion(newVersion);

        // 4. docker compose up -d --build
        await run(`docker compose --project-directory . -f deploy/docker-compose.staging.yml --env-file .env.staging up -d --build`);

        // 5. 健康检查（等 30 秒启动，再 curl）
        await new Promise(r => setTimeout(r, 30000));
        await run(`curl -fsS http://localhost:18080/api/sso/login-url >/dev/null`);

        log('SUCCESS', `deploy done: ${newVersion}`);
    } catch (err) {
        log('ERROR', `deploy failed: ${err.message}, rolling back to ${prevVersion}`);
        writeVersion(prevVersion);
        await run(`docker compose --project-directory . -f deploy/docker-compose.staging.yml --env-file .env.staging up -d`).catch(() => {});
        process.exit(1);
    }
}

// HTTP server
const server = http.createServer((req, res) => {
    if (req.method !== 'POST' || req.url !== '/gitee-webhook') {
        res.writeHead(404);
        return res.end('not found');
    }

    let chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
        const rawBody = Buffer.concat(chunks).toString('utf-8');
        const signature = req.headers['x-gitee-token'];

        if (!verifySignature(rawBody, signature)) {
            log('WARN', 'signature verification failed');
            res.writeHead(401);
            return res.end('invalid signature');
        }

        let payload;
        try {
            payload = JSON.parse(rawBody);
        } catch (e) {
            res.writeHead(400);
            return res.end('invalid json');
        }

        // 仅处理 push 事件，且只在指定分支触发
        if (payload.ref !== `refs/heads/${DEPLOY_BRANCH}`) {
            log('INFO', `skip branch ${payload.ref} (only ${DEPLOY_BRANCH})`);
            res.writeHead(200);
            return res.end('skip');
        }

        const commits = (payload.commits || [])
            .map(c => c.message?.split('\n')[0] || '')
            .join('; ');
        log('INFO', `push received: ${payload.pusher?.name || '?'} → ${DEPLOY_BRANCH} (${commits})`);

        res.writeHead(200);
        res.end('accepted');

        // 异步部署（不阻塞响应）
        deploy(commits).catch(err => log('ERROR', err.message));
    });
});

server.listen(PORT, () => {
    // 确保日志目录存在
    fs.mkdirSync(LOG_DIR, { recursive: true });
    log('INFO', `webhook-deploy listening on :${PORT}, branch=${DEPLOY_BRANCH}, dir=${DEPLOY_DIR}`);
    if (!WEBHOOK_SECRET) {
        log('WARN', 'GITEE_WEBHOOK_SECRET 未配置，签名校验已禁用（仅 dev 用）');
    }
});
