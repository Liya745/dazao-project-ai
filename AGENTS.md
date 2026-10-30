# 大枣角色工作室

## 协作规则

- 默认用中文回答。复杂需求先确认方向，已获授权的工作持续完成。
- 根目录是 Maven 聚合父工程与独立项目仓库目录；Vue 前端放在 `frontend/`。`gpt/` 仅存放方案、设计和验证说明，不存业务代码或运行依赖。
- 先阅读相关实现与 `gpt/大枣小站新项目/` 文档，按 KISS 原则实现当前需求，避免无关抽象。
- Java 注解各占一行，放在对应声明上方；方法体规范换行，不能写成单行方法。注释解释必要的业务规则，不添加作者标记。
- 安装新依赖前说明用途、位置、大小和维护方式并取得授权。不要安装全局软件或将密钥写入源码。
- 不擅自修改旧备份、云服务器或 Saya 的业务。旧项目恢复路径见 `gpt/大枣小站新项目/目录替换记录.json`。
- 任务完成时汇报文件、依赖、下载与构建产物的路径及大小，以及 outputs 和临时文件情况。临时测试类用完删除，保留有价值的正式测试。

## 当前结构

- Java 17 / Spring Boot 3.5.16，根 POM 聚合 common、system、framework、ai、character、admin 六个 Maven 模块。
- `dazao-admin/`：启动类 `com.dazao.character.CharacterStudioApplication`、Web/SSE 入口，`src/main/resources/` 为配置及 H2 建表语句，`src/test/` 为内存数据库与模型替身集成测试。
- `dazao-common/`：业务错误；`dazao-system/`：账号服务；`dazao-framework/`：认证/CSRF 安全配置。
- `dazao-ai/`：模型适配和请求额度；`dazao-character/`：角色/会话领域、持久化、生成与聊天编排；底层模块不得反向依赖 admin。
- 只有 admin 打包可执行 Jar；普通产物在各模块 `target/`，部署 Jar 在 `dazao-admin/target/deployment/`。运行脚本及 Maven 启动均以仓库根目录为工作目录，保证 `data/` 路径不变。
- `frontend/`：Vue 3 / Vite，开发端口 5199，`/api` 代理到后端 8099。
- 本地服务只监听 127.0.0.1，H2 数据写在 `data/`；本阶段为本地 MVP。
- 腾讯云试运行入口 `http://dazao.xyz/dzproject/`，服务器 `111.230.151.168`，专用目录 `/usr/dazao-character/`、Compose 项目 `dazao-character`。部署配置见 `deploy/README.md`，上线验证见 `gpt/大枣小站新项目/腾讯云部署-2026-10-14.md`。
- 云端 H2 位于 `/usr/dazao-character/data/studio.mv.db`，只操作本项目，不能碰 Saya 或旧大枣的数据库。部署前端与后端带 `/dzproject` 前缀，构建使用 Maven deployment profile，不覆盖本地根路径版本。

## 验证命令

```powershell
cd frontend
npm ci
npm run build
cd ..
mvn test
mvn package
```

也可以在根目录运行 `./build.ps1`；构建前端后再打包，Jar 会包含前端静态资源。

## 关键约束

- 会话 Cookie + CSRF，资源归属从登录身份取得。不要接受浏览器传入的 ownerId。
- 角色修改使用版本检查；会话绑定角色快照，旧会话不能自动改用最新设定。
- 局部生成只能合并选中且未锁定的白名单字段，不能只用提示词保护锁定字段。
- 生成与聊天仅使用后端环境变量 `AI_API_KEY`、`AI_BASE_URL`、`AI_MODEL`，无配置时明确报错。
- 模型输出按文本渲染，不使用 v-html，不给角色内容执行工具或访问其他用户数据的权限。
