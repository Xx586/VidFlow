<div align="center">
  <h2>VidFlow — 智能视频解析与异步处理平台</h2>

  <p>
    <a href="https://github.com/Xx586/VidFlow"><img src="https://img.shields.io/badge/GitHub-VidFlow-181717?style=flat-square&logo=github" alt="VidFlow repository"></a>
    <img src="https://img.shields.io/badge/Java-21-E76F00?style=flat-square" alt="Java 21">
    <img src="https://img.shields.io/badge/Spring%20Boot-3.5.9-6DB33F?style=flat-square" alt="Spring Boot 3.5.9">
    <img src="https://img.shields.io/badge/Vue-3-42B883?style=flat-square" alt="Vue 3">
    <img src="https://img.shields.io/badge/MySQL-8-4479A1?style=flat-square" alt="MySQL 8">
    <img src="https://img.shields.io/badge/Redis-7-DC382D?style=flat-square" alt="Redis 7">
    <img src="https://img.shields.io/badge/RocketMQ-5.3.4-D77310?style=flat-square" alt="RocketMQ 5.3.4">
    <img src="https://img.shields.io/badge/LangChain4j-Agent-20232A?style=flat-square" alt="LangChain4j">
    <a href="./LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="MIT License"></a>
  </p>
</div>

<div align="center">

面向 Java 后端实践的 <strong>智能视频解析与异步处理平台</strong>。保留原有 Agent 分析与结果展示能力。

致力于将长视频转化为可检索、可追溯、可继续追问的结构化知识。


</div>

## 项目预览

**登录与注册**

![VidFlow 登录与注册](docs/images/login-register.png)

**视频工作台**

![VidFlow 视频工作台](docs/images/video-library.png)

**Agent 目标输入**

![VidFlow Agent 目标输入](docs/images/agent-compose.png)

**Agent 分析结果**

![VidFlow Agent 分析结果](docs/images/agent-result.png)



用户完成登录后，可以上传视频并在工作台管理解析任务；选择视频并输入分析目标后，可以手动选择分析模式，也可以交给 Agent 自动判断。工作台会展示结构化结论、时间戳证据、执行计划、阶段轨迹与质量评估，并支持基于同一视频继续追问。

## 六条后端实现与验证范围

以下结论根据当前源码和本地测试整理。**源码接入不等于真实中间件已经验收通过**；需要 MySQL、Redis、MinIO、RocketMQ、FFmpeg、OCR 与 AI 服务的场景尚未完成端到端实测。

| 能力 | 当前实现 | 已验证内容与边界 |
| :--- | :--- | :--- |
| 分片上传与断点续传 | `ChunkUploadService` 用 Redis Set 保存已完成分片序号、MinIO 保存对象；客户端按 5 MB 分片并查询缺片；上传与合并共用 Redisson 锁。MySQL `upload_id` 唯一约束和完成查询防止合并重试重复入库。 | 单元测试覆盖合并锁冲突与已持久化结果复用；断网续传、并发合并及 MinIO/数据库故障切换未做真实服务测试。 |
| RocketMQ 异步任务 | `AnalysisDispatchService` 限流后持久化待投递消息，投递 MQ 并返回 HTTP 202；消费者后台执行，`analysis_dispatches` 保存投递状态，定时任务补投待确认消息，失败后记录台账并转失败主题。 | 已验证待投递消息的序列化与补投调用；未实测 Broker 重投、故障恢复与多实例竞争。受理接口使用 `mediaId + goal + mode` 查询，不返回独立任务 ID。 |
| 分层去重与消费幂等 | 内容 MD5 与目标/模式摘要分别用于上下文复用和结果隔离；每个媒体保留自己的任务，内容锁和媒体目标锁协调重复消费及修订。 | 单元测试覆盖键隔离和同内容不同媒体各自提交；真实并发、旧消息与再次提交的竞争未验证，不能宣称严格 exactly-once。 |
| 并行 ASR/OCR | `VideoContextService` 将音频转写和抽帧 OCR 提交到独立线程池，再等待两个 `CompletableFuture`；单路失败保留另一条可用数据，按分钟窗口构建 `VideoContext`。 | 已审查线程池、超时与失败分支代码；没有真实视频和识别服务结果，时间戳是窗口粒度。 |
| 阶段级 Checkpoint | `AgentCheckpointRepository` 将上下文、计划、Executor 草稿、Critic 状态和最终结果写入 MySQL；Redis 用作热缓存；重试按可用产物恢复。 | 单元测试覆盖修订暂存顺序；未实测进程崩溃恢复。ASR/OCR 两个分支并未各自保存独立检查点，旧产物没有自动版本失效。 |
| Redis Lua 令牌桶 | Lua 使用 Redis 服务端时间，在一次执行中检查并扣减用户桶与全局桶；分析提交与交互请求调用限流，Redis 异常时拒绝放行。 | 单元测试验证脚本内容与调用参数；尚未在真实 Redis 执行并发压测。自动模式路由另用独立的 Redisson 配额。 |

### 本次本地验证

在 `server` 执行 `mvn -o clean test`：**12 项测试通过，默认干净构建通过**。在 `client` 执行 `npm test`：**2 项测试通过**；执行 `npm run build`：**构建成功**。验证环境使用 Maven 3.9.16、JDK 25 运行并以 Java 21 为编译目标；JDK 21 运行时未单独实测。本机 Docker daemon 未运行，且 PATH 中没有 `ffmpeg` 和 `tesseract`，因此未启动后端、未执行数据库迁移或真实视频分析。以上结果不代表完整链路通过。

### 已知限制

- `analysis_dispatches` 当前以媒体、目标摘要和动作作为主键；同一任务失败后再次提交会复用同一行，迟到的旧消息仍可能更新新提交的状态。需要独立的投递代次标识才能可靠区分两次执行。
- 修订任务运行时，如果旧分析结果仍存在，状态查询会先返回旧结果；前端可能暂时显示“已完成”。
- 同内容视频的不同媒体可分别受理，消费者会等待内容锁并尝试复用结果；这是代码路径，尚无真实 MQ 并发验证。
- 分析接口返回 202 而非新的任务 ID；客户端必须保留 `mediaId`、归一化后的 `goal` 与 `mode`，用 `/analysis/analysis-status` 查询。

## 核心功能

长视频处理天然是**长耗时、高资源消耗、外部调用成本敏感**的场景。VidFlow 的设计都围绕这一背景展开，可以概括为四层能力。

### 🎬 可靠的视频任务链路

> 通过分片上传与消息队列承接长耗时处理，请求返回受理状态，分析由消费者继续执行。

- **分片上传 + 断点续传** — 前端按 5 MB 分片，Redis 记录已完成分片，MinIO 保存合并后的视频，弱网中断后可查询已传分片并补传。
- **异步削峰** — RocketMQ 将视频解析移出请求线程，提交后返回 HTTP 202（受理，不代表完成）；Redisson 按「内容指纹 + 分析目标」加锁，协调并发执行与重复消息。
- **成本护栏** — Redis Lua 原子令牌桶限制分析与追问请求速率；ASR 与模型调用采用有限次数的指数退避重试，兜底第三方网络抖动。

### 🧩 时序多模态 VideoContext

> 把语音、画面文字与时间戳融合成一份可检索、可校验的统一上下文。

- **双分支抽取** — FFmpeg 将音频按 60 秒切片，同时通过场景变化检测抽取关键帧，并以 30 秒保底采样避免遗漏静态板书。
- **并行与容错** — ASR 与 OCR 使用独立有界线程池并行执行；相邻画面通过感知哈希去重，单路失败时仍保留另一条有效信息。
- **统一结构** — 语音区间、OCR 文本、关键帧与时间戳被合并为统一的 `VideoSegment`，后续检索与校验不再依赖底层模型格式。

```text
[02:00 - 03:00]
ASR      接下来讲解二叉树的前序遍历
OCR      前序遍历：根节点、左子树、右子树
Evidence frame_000125.jpg
```

### 🔁 有证据约束的 AgentLoop

> 每条结论都必须绑定可在原始视频中核验的时间戳证据，拒绝模型自由发挥。

- **角色分工** — Planner 将用户目标拆成可执行任务，Executor 生成固定结构的结论、证据与建议。
- **闭环校验** — Critic 检查目标覆盖、结构完整性与时间戳证据；不通过时依据缺失内容和时间范围定向重新检索。
- **自动模式路由** — 根据用户目标自动选择通用、学习、审查或创作模式；路由不可用时回退通用模式，不阻断分析任务。
- **四类结构化产物** — 通用模式生成结论与建议，学习模式生成大纲、自测题与易错点，审查模式定位逻辑漏洞与存疑结论，创作模式提取爆点、标题与口播脚本。
- **成本可控** — AgentLoop 最多执行两轮，既允许定向修正，也通过轮次上限约束延迟与 Token 成本。

### 🔍 长视频检索与断点恢复

> 面向数小时长视频的分段检索，以及分阶段可恢复的任务状态机。

- **混合检索** — 每 5 分钟生成片段摘要、关键词与 Embedding，通过关键词匹配与 Qdrant 语义召回选出 TopK 原始证据。
- **优雅降级** — Qdrant 或 Embedding 服务不可用时，退化到本地关键词与已有向量排序，不阻断主分析链路。
- **断点恢复** — Checkpoint 以 MySQL 为恢复真源、Redis 为热缓存，持久化 `VideoContext`、分块、计划、Critic 状态与最终结果。
- **状态可观测** — 前端通过 SSE 接收任务阶段；失败消息写入独立失败主题与失败任务表，可由管理接口重新投递。

## 系统流程

```mermaid
sequenceDiagram
    autonumber
    actor User as 用户
    participant Web as Vue 工作台
    participant API as Spring Boot API
    participant MQ as RocketMQ
    participant Worker as 分析消费者
    participant Context as VideoContext
    participant Search as Qdrant 检索
    participant Agent as AgentLoop
    participant State as MySQL + Redis

    User->>Web: 上传视频并填写分析目标
    Web->>API: 分片上传与合并
    API->>MQ: 投递视频分析任务
    API-->>Web: 返回 202 Accepted
    MQ->>Worker: 异步消费
    Worker->>State: 查询幂等结果与 Checkpoint

    alt 已存在可恢复结果
        State-->>Worker: 返回最近成功阶段
    else 首次解析
        par 语音分支
            Worker->>Context: FFmpeg 分段 + ASR
        and 视觉分支
            Worker->>Context: 关键帧抽取 + OCR
        end
        Context->>State: 保存时序多模态上下文
    end

    Worker->>Search: 摘要、关键词与 Embedding 混合检索
    Search-->>Agent: 返回相关原始证据
    loop Critic 未通过且未达到两轮
        Agent->>Agent: Planner -> Executor -> Critic
        Agent->>Search: 按反馈定向补充证据
    end
    Agent->>State: 保存结构化结果与 Checkpoint
    Worker-->>Web: SSE 推送阶段与最终结果
    Web-->>User: 展示结论、证据与后续追问
```

## 技术栈

| 层次 | 技术 | 用途 |
| :--- | :--- | :--- |
| Web | Vue 3、Vite、SSE、Marked | 上传、Agent 工作台、实时进度与安全 Markdown 展示 |
| API | Java 21、Spring Boot 3.5.9、Undertow、MyBatis-Plus | 鉴权、媒体管理、任务编排与 REST API |
| 异步与缓存 | RocketMQ 5.3.4、Redis 7.4、Redisson | 异步削峰、状态缓存、限流、锁与消费幂等 |
| 数据与存储 | MySQL 8、MinIO、Qdrant | 业务数据、视频对象、Checkpoint 与向量检索 |
| 视频与 AI | FFmpeg、Tesseract、LangChain4j、DeepSeek、TeleSpeechASR、BGE-M3 | 音视频处理、多模态解析、Agent 推理与 Embedding |
| 部署 | Docker Compose | 本地中间件编排 |

## 本地运行

### 环境要求

| 组件 | 要求 | 说明 |
| :--- | :--- | :--- |
| JDK | 21 | 后端运行环境 |
| Node.js | 22 | Vue 与 Vite 构建环境 |
| Docker | Compose v2 | 启动 MySQL、Redis、MinIO、Qdrant 与 RocketMQ |
| FFmpeg | 可在终端调用 | 音频切分与关键帧抽取 |
| Tesseract | 安装 `chi_sim` 与 `eng` | 中英文关键帧 OCR |
| yt-dlp | 可选 | 仅解析在线视频链接时需要 |

建议先确认命令均可用：

```bash
java -version
node --version
docker compose version
ffmpeg -version
tesseract --version
```

### 1. 准备配置

```bash
cp .env.example .env
```

编辑 `.env`，至少替换数据库、Redis、MinIO、Qdrant 的示例密码并设置 `SILICONFLOW_API_KEY`。全新数据库中 `DB_USERNAME` 与 `MYSQL_APP_USER` 应保持一致；`MYSQL_ROOT_PASSWORD` 仅供数据库初始化使用。密钥只保存在本地 `.env`，不要提交到仓库。

默认 LLM 为 `deepseek-ai/DeepSeek-V3.2`。历史示例模型 `deepseek-ai/DeepSeek-R1-Distill-Qwen-32B` 已被硅基流动禁用，会返回 `Model disabled`。`LLM_TIMEOUT_SECONDS` 默认是 `300`，用于避免长视频证据分析在模型响应尚未返回时过早超时；模型或超时配置变更后需要重启后端。

### 2. 启动中间件

```bash
./scripts/dev-up.sh
```

脚本会检查本机命令与版本、校验 Compose 配置，并等待 MySQL、Redis、MinIO、Qdrant 和 RocketMQ 启动。中间件与后端默认只监听 `127.0.0.1`，不会直接暴露到局域网；远程部署时再显式修改 `SERVER_ADDRESS` 并配置反向代理。

### 3. 启动后端

```bash
set -a
source .env
set +a

cd server
./mvnw spring-boot:run
```

后端默认地址为 `http://localhost:9090`，启动时会初始化项目所需数据表。另开终端确认服务可用：

```bash
curl http://localhost:9090/health
```

成功时返回 `{"code":0,"message":"success","data":"UP"}`。

### 4. 启动前端

```bash
set -a
source .env
set +a

cd client
npm ci
npm run dev
```

浏览器访问 `http://localhost:5173`。开发环境默认通过 Vite 代理访问后端；后端地址不同时修改 `VITE_DEV_PROXY_TARGET`，前后端分开部署时再设置 `VITE_API_BASE_URL`。

只查看前端 Agent 工作台时，可以打开 `http://localhost:5173/?demo`。Demo 模式使用内置示例数据，不依赖后端服务。

### 常见问题

| 现象 | 处理方式 |
| :--- | :--- |
| 后端无法连接 MySQL 或 Redis | 运行 `docker compose --env-file .env ps`，确认服务健康且 `.env` 密码一致 |
| 页面提示无法连接后端 | 先访问 `/health`；再检查 `VITE_DEV_PROXY_TARGET` 或 `VITE_API_BASE_URL` |
| 视频解析提示命令不存在 | 确认 `ffmpeg`、`tesseract` 可在终端执行，并安装 Tesseract `chi_sim` 与 `eng`；OCR 命令可由 `OCR_COMMAND` 指定 |
| AI 接口返回 401 或模型不可用 | 检查 `SILICONFLOW_API_KEY` 与模型名称，修改后重启后端 |
| Maven 提示 `maven-default-http-blocker` | 在 `server` 目录执行 `./mvnw -s .mvn/central-settings.xml spring-boot:run`，临时绕过失效的用户级镜像 |

停止本地中间件：

```bash
docker compose --env-file .env down
```

该命令不会删除 `mysql/data`、`redis/data`、`minio/data`、`qdrant/data` 或 RocketMQ 命名卷。需要完全重置时请先备份，再使用 `docker compose --env-file .env down --volumes` 并手动清理这些数据目录。

## 目录结构

```text
VidFlow（源码目录可继续使用原名）
├── client/              # Vue 3 工作台
├── server/              # Spring Boot API 与 Video Agent
├── rocketmq/            # Broker 配置
├── docker-compose.yml   # 中间件编排
└── .env.example         # 本地配置模板
```

## License

本项目基于 [MIT License](LICENSE) 开源。
