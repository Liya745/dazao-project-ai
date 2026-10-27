# 大枣 · 角色工作室

根据用户需求创建、编辑并试聊原创、IP 或功能型角色的独立项目。后端在仓库根目录，前端在 `frontend/`，方案在 `gpt/大枣小站新项目/`。

## 已实现的首版

- 独立账号注册、登录、退出；Spring Security 会话 Cookie 与 CSRF。
- 生成完整角色草稿，支持作品和版本信息；手工编辑与保存。
- 字段锁定和局部重生成，服务端限制写回范围。
- 角色库搜索、编辑、复制、删除、导出 Character Card V2 JSON。
- 保存采用版本号，避免多个页面互相覆盖。
- 角色试聊使用 POST + SSE，保存用户输入、回复和失败状态。失败后输入保留，可重新发送。
- 会话保存创建时的角色快照，角色后续更新不改变旧会话。
- H2 文件数据库保存账号、角色和聊天记录。
- Vue 前端构建后嵌入 Spring Boot Jar，可通过一个本地地址使用。

首版不包含图片生成、角色卡导入、原作知识检索、长期记忆和微信/QQ 接入。云端试运行已部署到腾讯云，真实模型生成与流式聊天完成一次联通验收；更多角色的还原度和多轮表现仍需评估。

## 云端入口与部署

访问 **http://dazao.xyz/dzproject/**，备用入口 **http://111.230.151.168/dzproject/**。新站账号独立，旧账号未迁移，没有预置管理员，请自行注册。

服务器目录 `/usr/dazao-character/`，Compose 项目 `dazao-character`，使用已有 Java 17 镜像及一份 `compose.yaml`。宿主机只发布 `127.0.0.1:18199`，Nginx 将 `/dzproject/` 转发到新服务。

数据库是独立 H2 文件 `/usr/dazao-character/data/studio.mv.db`，不使用 Saya 的 MySQL。首次启动自动执行 `schema.sql`，创建 `studio_users`、`characters`、`conversations`、`chat_turns` 四张表。服务器 `.env` 保存模型配置，权限 600，不提交 Git。

部署文件在 `deploy/`，操作说明见 `deploy/README.md`。`deploy/prepare.ps1` 生成前缀为 `/dzproject/` 的独立部署构建，输出到 `deploy/artifacts/` 和 `target/deployment/`；本地 `/` 构建不受影响。实际上线及资源记录见 `gpt/大枣小站新项目/腾讯云部署-2026-10-14.md`。

## 本地启动

要求 Java 17、Maven 3.9、Node.js 22.12 或更新版本。当前开发验证使用 Java 17.0.11、Maven 3.9.9、Node.js 24.13.1。

在根目录 PowerShell 中配置自己的 OpenAI 兼容模型。以下为示例，具体模型名按供应商可用列表填写：

```powershell
$env:AI_API_KEY = '你自己的模型 API Key'
$env:AI_BASE_URL = 'https://api.deepseek.com/v1'
$env:AI_MODEL = 'deepseek-chat'
```

`.env.example` 用于说明变量；`run.ps1` 可读取根目录未提交的 `.env` 中的三个模型变量，显式环境变量优先。直接运行 Java 或 Maven 时仍需设置环境变量。不要把真实 Key 提交到 Git。

一体化构建和启动：

```powershell
./build.ps1
./run.ps1
```

然后访问 **http://127.0.0.1:8099/**。`build.ps1` 在 `frontend/node_modules/` 安装锁文件中的依赖，并运行前端构建及 Maven 测试、打包。`run.ps1` 直接启动已构建 Jar，按 Ctrl+C 停止。

不配置模型也能注册、编辑、保存、导出角色和浏览会话；生成及回复会明确提示模型待配置。页面“模型已配置”只代表有后端配置，不代表供应商连通性已验证。

开发时也可运行两个终端：

```powershell
# 终端一，项目根目录
mvn spring-boot:run

# 终端二
cd frontend
npm ci
npm run dev
```

开发页面为 **http://127.0.0.1:5199/**，通过 Vite 代理调用 8099 的后端。

## 配置与数据

后端配置在 `src/main/resources/application.yml`。默认只监听 `127.0.0.1:8099`。H2 写入根目录 `data/studio.mv.db`，退出账号不会删除数据；数据库与构建产物均忽略提交。

使用流程：注册 → 描述需求 → 生成草稿 → 调整/锁定/局部重生成 → 保存 → 开始试聊。也可手工填写标有星号的必填字段后保存，不必调用生成器。锁定只约束重生成，用户仍可手动编辑该字段。

模型输出仅按文本展示。生成上限 3000 token，聊天上限 1200 token；聊天携带最近 12 个完成的轮次。每用户生成及聊天合计最多 60 次/小时，流式回复同时最多 4 个，同一会话不允许并行回复。额度与并发状态保存在进程内，重启会重置额度。客户端断线后供应商请求可能持续至上游完成或超时，首版未提供上游取消功能。

## 框架与参考

实现复用成熟组件，没有复制旧社区代码，也没有克隆参考项目：

| 组件 | 固定版本 | 用途 |
| --- | --- | --- |
| Spring Boot / Spring Security / JDBC | 3.5.16 及其 BOM | Web、认证、校验、数据库 |
| LangChain4j OpenAI | 1.3.0 | 后端模型生成与流式聊天 |
| Vue | 3.5.44 | 页面与状态 |
| Vite / plugin-vue | 8.3.4 / 6.0.10 | 前端开发与构建 |
| H2 | Spring Boot BOM 管理 | 本地文件与测试内存数据库 |

参考 [Character Card V2 规范](https://github.com/malfoyslastname/character-card-spec-v2/blob/main/spec_v2.md) 设计导出；参考 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 的角色资产与对话分离思路。后续聊天平台接入方向见总体方案中的 LangBot 评估。

## 验证与运行边界

`mvn test` 验证登录/CSRF、资源归属隔离、版本冲突、会话快照、锁定字段合并、角色卡导出、SSE 成功及失败记录。模型替身仅在测试类路径，测试不产生供应商费用。浏览器验证记录和页面截图在 `gpt/大枣小站新项目/`。

这是小规模试运行 MVP，采用单实例 H2，模型服务限额与 Nginx 登录/注册/生成限流共同限制调用。生产运营和多实例部署前仍需完成更完整的模型质量评估、数据库迁移与备份及运营限额。按本次要求使用 HTTP。当前账号体系没有找回密码或管理后台，请保管账号与数据库。

## 目录替换与仓库

2026-10-14 按用户要求替换项目根目录。旧项目、旧 Git 记录和旧部署材料移至同级：

`E:/JavaWeb-java-items/dazao-project-ai-backup-20261014-150653/`

恢复记录在 `gpt/大枣小站新项目/目录替换记录.json`。本地替换后另行完成本次腾讯云部署，旧云端大枣服务保留用于入口回退。新项目已初始化独立本地 Git 仓库，分支 `codex/character-studio`，与旧 Git 历史分离；未创建提交，没有远程仓库地址，未推送 Git。

## 可清理内容

- `target/`：Jar、编译和测试报告，可删后重新构建。
- `frontend/dist/`：前端构建产物，可重新构建。
- `frontend/node_modules/`：前端依赖，可重新执行 `npm ci`。
- `data/`：实际业务数据，删除会丢失账号与角色，不作为临时目录清理。
- 同级旧项目备份含旧代码与数据，确认新项目及恢复不再需要后再自行决定保留或移走。

任务中没有使用 `outputs/` 存放产物。
