# 腾讯云部署包

目标：`111.230.151.168`，HTTP 入口 `/dzproject/`。后端与前端构建打成同一个 Jar，由一份 Compose 启动。旧账号不迁移，新站自行注册。

## 文件与隔离

- 服务器专用目录 `/usr/dazao-character/`，Compose 项目 `dazao-character`。
- 复用服务器已有 Java 17 镜像 `dazao-migration/java:20261011`；`artifact.json` 记录核验的镜像 ID 与 Jar SHA-256，启动脚本拒绝不一致镜像，不自动拉取。
- `app/application.jar`、`compose.yaml`、`scripts/`、`nginx/` 为部署输入；私有 `.env` 仅在服务器保存，权限 600。
- `data/studio.mv.db` 是独立 H2 数据库，目录由 UID 10001 的新容器使用。账号、角色和会话从空库开始，后续重启保留。
- 端口只绑定 `127.0.0.1:18199`，不开放数据库端口。容器不接入 Saya 网络。
- 容器内存上限 384 MiB、CPU 0.5 核、日志最多 3 × 5 MiB，根文件系统只读，临时目录上限 32 MiB。
- 前端资源与 API 带 `/dzproject/` 前缀，后端设置相同 context-path，独立 Cookie 名称和路径。

## 本地准备

在根目录执行：

```powershell
./deploy/prepare.ps1
```

复用已有前端依赖和 Maven 缓存，运行测试后输出部署 Jar 到 `target/deployment/`，复制到 `deploy/artifacts/app/application.jar`。部署页面输出到 `deploy/artifacts/web/`。根目录本地构建仍使用 `/` 和 `target/`。

上传应用 Jar、Compose、脚本和 Nginx 片段，不上传 `node_modules`、本地数据库或旧项目备份。凭据通过私有传输进入服务器 `.env`，不可写进 Jar。实际第一次部署的产物清单在 `deploy/artifacts/artifact.json`，运行资料和检查记录保存在服务器 `backups/`。

## 当前服务器启动

```sh
cd /usr/dazao-character
sh scripts/start.sh
```

已完成首次入口切换。不要在当前正式站点重复执行首次基线和入口安装脚本。

日常查看：

```sh
docker compose -p dazao-character -f compose.yaml ps
docker compose -p dazao-character -f compose.yaml logs --tail 100 studio
```

更新 Jar 时先备份、原子替换文件，再仅重建本项目 studio 服务：

```sh
docker compose -p dazao-character -f compose.yaml up -d --force-recreate --wait studio
```

不要使用全局 Docker 清理、重启 Docker、覆盖整个宿主机 Nginx 配置或操作 Saya Compose。

## 验收与回退

`scripts/preflight.py` 只访问本机新服务，验证资源前缀、注册登录/CSRF/Cookie、手工角色、会话和导出，不调用外部模型。它创建临时账号，删除测试角色与会话；临时账号需安排维护窗口通过专用 H2 驱动清理，不可直接删除正在使用的数据库文件。

`scripts/verify.py` 会使用已配置模型进行一次角色生成和一次 SSE 聊天，产生供应商调用费用；执行前必须获得这次测试的授权。此次用户已明确批准并执行一次。不要把该脚本放进自动启动或健康检查。

`scripts/inventory.py after` 校验 Saya 与旧大枣的容器身份、启动时间、重启次数和相关配置摘要。`scripts/ingress.py` 只管理大枣路径片段及其专用代理、限流文件。原宿主机 server 与 Saya 的回调 location 保持原字节，Nginx 检查通过后平滑 reload。

如需恢复旧大枣入口：

```sh
cd /usr/dazao-character
python3 scripts/ingress.py rollback
```

回退脚本检查安装后配置摘要，发现后续编辑则拒绝覆盖。旧大枣四个容器及数据仍保留运行，新站数据也保留，不自动同步到旧社区。

## 数据备份

本阶段是单实例文件数据库。安全的数据库备份顺序是：停止本项目 studio → 复制 `data/studio.mv.db` 到带时间的私有备份目录 → 启动 studio。不要将运行中的 H2 文件直接复制作为一致性备份，也不要删除真实业务数据来清理磁盘。

## 清理

本地 `deploy/artifacts/` 和 `target/deployment/` 都是可重新构建的产物，已忽略提交。服务器 `backups/deploy.bundle.tar.gz` 是保留的部署输入包，需要省空间时可在确认应用与回退资料完整后删除。服务器 `.env`、`data/` 和 `backups/ingress/` 需保留。
