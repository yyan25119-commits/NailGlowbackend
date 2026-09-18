# NailGlow 后端重构说明（若依 RuoYi-Vue + Redis）

本次重构把原有自研的 Spring Boot 后端改造为**若依 RuoYi-Vue 3.9.2（springboot3 分支）**架构，
并引入 Redis 承担登录令牌、验证码、业务缓存与实时流量统计。

**前端 `NailGlowfrontend` 未做任何改动**（`git status` 干净，HEAD 仍为 `afad3ca 终版`），
所有 `/api` 接口契约保持不变。

---

## 1. 技术栈变化

| 组件 | 重构前 | 重构后 |
|---|---|---|
| Spring Boot | 4.0.6 | **3.5.16**（若依 springboot3 要求） |
| Java | 21 | **17**（若依要求，仍用 JDK 21 运行） |
| 安全框架 | 自研 `AuthRequiredFilter` | **Spring Security + 若依 JWT** |
| 会话存储 | MySQL `auth_sessions` 表 | **Redis `login_tokens:<uuid>`** |
| 持久层 | HikariCP + `JdbcTemplate` | **Druid + MyBatis（若依）**，业务 SQL 仍走 `JdbcTemplate` |
| JSON | Jackson 3（`tools.jackson.*`） | **Jackson 2（`com.fasterxml.jackson.*`）** |
| 口令 | 无盐 SHA-256 | **BCrypt**（兼容历史 SHA-256 登录） |
| 权限模型 | `users.role` 单列精确匹配 | `users.role` **叠加** 若依 `sys_user/sys_role/sys_menu` |

> 若依以**单模块**方式内嵌（`com.ruoyi.common/framework/system` 源码直接引入，
> 未拆多模块），因为业务代码大量使用相对工作目录的路径
> （`src/main/python`、`uploads`、`models`、`runtime`）。
> **因此必须从 `NailGlowbackend/` 目录启动应用。**

---

## 2. 目录结构

```
src/main/java/com/nailglow/backend/   # 业务代码（原有，接口契约不变）
  ├─ config/
  │   ├─ RestAuthHandlers.java        # 401/403 响应契约（新增）
  │   ├─ TrafficInterceptor.java      # 接口流量统计拦截器（新增）
  │   ├─ ApiExceptionHandler.java     # 异常响应契约（最高优先级）
  │   └─ CorsConfig.java              # 静态资源映射（CORS 交给若依）
  ├─ security/
  │   ├─ NailGlowAuthorizationManager.java  # 路径→角色鉴权（替代 AuthRequiredFilter）
  │   └─ RuoYiRbacBridge.java               # 业务账号 ↔ 若依权限表桥接
  └─ service/
      ├─ AuthService.java             # 门面：对外签名不变，底层改为若依 TokenService + Redis
      ├─ RealtimeTrafficService.java  # Redis 实时流量统计（新增）
      └─ DatabaseInitializer.java     # 建表/迁移（新增排序规则统一）
src/main/java/com/ruoyi/              # 若依内核（common/framework/system，新增）
src/main/java/com/ruoyi/web/controller/common/CaptchaController.java  # Redis 验证码（新增）
src/main/resources/
  ├─ application.yml                  # 若依风格配置（新增）
  ├─ application-druid.yml            # 数据源配置（新增）
  ├─ application.properties.bak       # 原配置（改名，避免覆盖 YAML）
  └─ db/ruoyi-sys-schema.sql          # sys_* 建表脚本（幂等，新增）
```

---

## 3. 接口契约保持不变（关键）

前端只把 token 当**不透明字符串**回填到 `Authorization: Bearer`，因此换成若依 JWT 是透明的。

| 项 | 保持的契约 |
|---|---|
| 成功响应 | `{"code":0,"message":"ok","data":{...}}` |
| 登录响应 | `{"token","expiresAt","user"\|"admin":{...}}`（含 `displayName`、`publicId`） |
| 未登录 | **HTTP 401** + `{"code":401,"message":"请先登录","data":null}` |
| 无权限 | **HTTP 403** + `{"code":403,"message":"没有访问权限","data":null}` |
| 业务异常 | HTTP 200 + `{"code":1,"message":"..."}` |
| 注册 | `{"pendingApproval":true,"status":"待审核","message":"..."}` |
| 令牌有效期 | **7 天**（`token.expireTime=10080` 分钟，对齐原 `SESSION_DAYS=7`） |
| WebSocket | `/ws/admin/live?token=<token>`，需 admin 角色 |

路径 → 角色规则（等价于原 `AuthRequiredFilter`）：

```
/api/admin/**                  -> 需要 admin
/api/user/**、/api/agent/**    -> 需要 user
/api/auth/**、/api/test、/api/external-image、/uploads/**、/ws/**  -> 放行
```

> 角色为**精确匹配**（与原实现一致）：admin 令牌访问 `/api/user/**` 会得到 403。

---

## 4. Redis 承担的能力

| 用途 | Key 前缀 | TTL |
|---|---|---|
| 登录令牌 | `login_tokens:<uuid>` | 7 天（剩余不足 20 分钟自动续期） |
| 验证码 | `captcha_codes:<uuid>` | 2 分钟 |
| 业务缓存（系统设置） | `nailglow:setting:<key>` | 6 小时，写入时主动失效 |
| 实时流量统计 | `nailglow:traffic:{min,hour,day,total,path}` | 分钟 3m / 小时 2h / 天 2d / 总计永久 |
| 管理端在线连接数 | `nailglow:ws:online` | 永久（断开递减） |

验证码由若依原生 `CaptchaController` 提供（`GET /captchaImage`），开关为
`sys_config.sys.account.captchaEnabled`。**NailGlow 登录页没有验证码输入框，故不改动登录流程**；
需要启用时在若依后台打开该开关即可。

实时流量可通过 `GET /api/admin/traffic/realtime` 查看，也作为 `realtime` 字段
追加在既有 `GET /api/admin/traffic` 响应中（新增字段，不破坏前端）。

---

## 5. 认证与权限的桥接方式

- **业务 `users` 表仍是账号与口令的唯一权威来源**，登录逻辑、中文状态
  （`正常`/`禁用`/`待审核`/`观察`）全部保留。
- 登录时把业务角色映射到若依角色：
  `users.role='admin'` → 若依 `admin`（拥有 `*:*:*`）；`users.role='user'` → 若依 `common`。
- 权限由若依 `SysPermissionService` 依据 `sys_menu`/`sys_user_role` 计算，不再硬编码。
- 业务账号会**懒同步**到 `sys_user` / `sys_user_role`，使若依后台可统一管理这些账号。

### 本次修复的安全问题

1. **未授权访问**：`AuthorizationManager` 返回 `null` 在 Spring Security 中表示「弃权」，
   会被当作**放行**。已改为返回 `false`，未登录请求正确返回 401。
   （修复前 `/api/admin/**` 无令牌也能拿到全部数据。）
2. **令牌吊销缺失**：登录态迁移到 Redis 后，管理端「停用/删除用户」只清空了废弃的
   `auth_sessions` 表，旧令牌仍在有效期内可用。已新增按用户清理 Redis 会话
   （停用、删除、重置数据均生效）。
3. **管理员口令被覆盖**：`DatabaseInitializer.ensureAdminUser()` 原本每次启动都用
   无盐 SHA-256 重写管理员口令，会（a）重置管理员自行修改的密码，
   （b）把 BCrypt 降级为 SHA-256。现已改为仅在账号不存在时用 BCrypt 创建。
4. **排序规则冲突**：若依 `sys_*` 表继承数据库默认 `utf8mb4_0900_ai_ci`，
   而业务表是 `utf8mb4_unicode_ci`，跨表 JOIN 会抛
   `ERROR 1267 Illegal mix of collations`。已统一为 `utf8mb4_unicode_ci`。
5. **CORS 重复写入**：移除自研 MVC `CorsRegistry`，统一由若依 `CorsFilter` 处理，
   避免重复的 `Access-Control-Allow-Origin` 头导致浏览器拒绝跨域。
6. **异常响应格式泄漏**：若依 `GlobalExceptionHandler` 会返回 `{code,msg}`，
   与前端期望的 `{code,message,data}` 不符。已让 `ApiExceptionHandler` 以最高优先级
   兜底，并显式处理 403 与 404。

---

## 6. 启动方式

### 前置条件
- JDK 17+（本机 JDK 21）
- Maven 3.8+
- MySQL 8（本机 3306）
- Redis（本机 6379）

### 配置
复制 `.env.local.example` 为 `.env.local`（已被 `.gitignore` 忽略）并填写：
```properties
MYSQL_USER=root
MYSQL_PASSWORD=你的密码
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
TOKEN_SECRET=请改成随机长字符串
```

数据库无需手工创建：连接串带 `createDatabaseIfNotExist=true`，
`sys_*` 表由 `spring.sql.init` 幂等建表脚本自动创建，业务表由 `DatabaseInitializer` 创建。

### 启动
```bash
cd NailGlowbackend          # 必须在此目录启动（业务路径相对于工作目录）
mvn spring-boot:run
```
后端：<http://localhost:8080>　Druid 监控：<http://localhost:8080/druid>（ruoyi / 123456）

### 前端
```bash
cd NailGlowfrontend
npm install
npm run dev                 # http://localhost:5173，/api 与 /uploads 已代理到 :8080
```

默认管理员：`admin` / `admin`（可用环境变量 `NAILGLOW_ADMIN_PASSWORD` 覆盖，仅首次建库时生效）。

---

## 7. 已知限制

- **业务 SQL 仍走 `JdbcTemplate`**（218 处），MyBatis 与若依权限 SQL 并存。
  按约定「先做透若依内核，业务 SQL 分轮次渐进迁移」，本轮未迁移业务 SQL。
- **WebSocket 在 dev 模式下回退到轮询**：前端用 `window.location.hostname:port` 拼 WS 地址，
  Vite 的 `server.proxy` 未配置 `/ws`，故 dev 下连的是 5173 而非 8080。
  前端已有轮询兜底，功能不受影响；因要求前端零改动，未修改 `vite.config.js`。
- **Python AI 能力需自行安装依赖**：`requirements-*.txt`（langgraph / scikit-learn /
  mediapipe 等）未安装时相关接口会走 `fallback()` 降级返回，不影响登录与业务主流程。
  Windows 下已提供 `scripts/project-python.cmd`（原 `scripts/project-python` 是 POSIX sh）。
  > 本机实测：Java→Python 调用链**正常**（脚本能被正确启动，仅缺少第三方模块）。
  > 但本机 Python 为 **3.14.5**，`numpy` / `scikit-learn` 尚无 cp314 预编译 wheel，
  > `pip install` 会转入源码编译而长时间无进展；且没有其它可用解释器
  > （`Python313` 目录只有 `Lib`/`Scripts`，无 `python.exe`）。
  > 建议安装 Python 3.11/3.12 后建虚拟环境，再把 `PYTHON_BIN` 指向
  > `.venv\Scripts\python.exe` 或 `scripts/project-python.cmd` 即可。
  > 另需注意：AI 功能还依赖豆包/DeepSeek 等外部 API Key，未配置时同样走降级逻辑。
- `ExternalTrendCollectorService` 的部分采集逻辑依赖 `bash`、Chrome 与 `Xvfb`（Linux 服务器场景），
  Windows 下这些非核心采集路径不可用。
- `sys_user` 种子数据中的 `ry` 测试账号、若依自带的 `sys_notice` 等表为若依原生数据，与业务无关。
