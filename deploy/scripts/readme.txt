  步骤 1：备份生产 DB（在生产 MySQL 主机上）

  ssh <prod-db-host>
  bash deploy/scripts/backup-db.sh -h 127.0.0.1 -P 3306 -u <produser> -p<prodpwd>
  ls -la deploy/db-backup-*.sql.gz | tail -1
  gunzip -t deploy/db-backup-*.sql.gz
  scp deploy/db-backup-*.sql.gz <prod-app-host>:/home/deploy/metric-code/deploy/

  步骤 2：scp 文件到生产 app 主机（dev Mac 上）

  PROD_APP=<prod-app-host>

  # 4 个镜像文件
  scp /Users/developer/metric_code/deploy/metric-backend-v2026.07.03-amd64.tar.gz \
      /Users/developer/metric_code/deploy/metric-backend-v2026.07.03-amd64.tar.gz.md5 \
      /Users/developer/metric_code/deploy/metric-frontend-v2026.07.03-amd64.tar.gz \
      /Users/developer/metric_code/deploy/metric-frontend-v2026.07.03-amd64.tar.gz.md5 \
      ${PROD_APP}:/home/deploy/metric-code/deploy/

  # 新脚本 + compose
  scp /Users/developer/metric_code/deploy/scripts/prod-load-and-run.sh \
      ${PROD_APP}:/home/deploy/metric-code/deploy/scripts/
  scp /Users/developer/metric_code/deploy/docker-compose.prod.yml \
      ${PROD_APP}:/home/deploy/metric-code/docker-compose.yml

  步骤 3：在生产 app 主机上部署

  ssh ${PROD_APP}
  cd /home/deploy/metric-code

  # 确认备份文件在了
  ls -la deploy/db-backup-*.sql.gz | tail -1

  # 跑部署脚本（会自动检查备份、加载镜像、起容器、等健康检查）
  bash deploy/scripts/prod-load-and-run.sh v2026.07.03-amd64

  步骤 4：验证（生产 app 主机）

  docker ps --filter name=metric- --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
  curl -fsS http://localhost:18080/api/session/config

  ---
  就这 4 步。 一台机器一台机器跑，别并行。每步做完告诉我，我帮你检查下一步。

  你从步骤 1 开始。
