package com.nailglow.backend.service;

import com.nailglow.backend.security.RuoYiRbacBridge;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * NailGlow 鉴权门面。
 *
 * <p>重构后本类不再自己维护 token 表，而是复用若依的认证内核：
 * <ul>
 *   <li>令牌签发 / 续期 / 注销：{@link TokenService}（JWT + Redis，key = login_tokens:&lt;uuid&gt;）</li>
 *   <li>用户身份仍以业务表 {@code users} 为准（保留现有业务表，不迁移数据）</li>
 *   <li>角色与权限按若依 RBAC 模型装配到 {@link LoginUser}，供 {@code @ss.hasRole(...)} 使用</li>
 * </ul>
 *
 * <p>对外方法签名保持不变，因此 UserController / AgentController / AdminController /
 * AdminLiveWebSocketHandler 等 18 处调用点无需修改。
 */
@Service
public class AuthService {
    private static final long PUBLIC_USER_ID_FLOOR = 1_000_000L;
    /** 若依超级管理员角色标识，拥有全部权限。 */
    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_USER = "user";

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    private final JdbcTemplate jdbc;
    private final TokenService tokenService;
    private final RedisCache redisCache;
    private final RuoYiRbacBridge rbacBridge;
    private final SysPermissionService permissionService;

    @Value("${token.secret}")
    private String tokenSecret;

    @Value("${token.expireTime:10080}")
    private int expireTimeMinutes;

    public AuthService(JdbcTemplate jdbc, TokenService tokenService, RedisCache redisCache,
                       RuoYiRbacBridge rbacBridge, SysPermissionService permissionService) {
        this.jdbc = jdbc;
        this.tokenService = tokenService;
        this.redisCache = redisCache;
        this.rbacBridge = rbacBridge;
        this.permissionService = permissionService;
    }

    // ------------------------------------------------------------------
    // 登录 / 注册
    // ------------------------------------------------------------------

    public Map<String, Object> login(String account, String password, String role) {
        String normalizedAccount = normalize(account);
        String normalizedPassword = normalize(password);
        if (normalizedAccount.isBlank() || normalizedPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入账号和密码");
        }

        List<Map<String, Object>> rows = jdbc.query("""
                select id, nickname, account, role, status, password_hash, try_count, favorite_style
                from users
                where account = ? and role = ?
                limit 1
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("nickname", rs.getString("nickname"));
            row.put("account", rs.getString("account"));
            row.put("role", rs.getString("role"));
            row.put("status", rs.getString("status"));
            row.put("passwordHash", rs.getString("password_hash"));
            row.put("tryCount", rs.getInt("try_count"));
            row.put("favoriteStyle", rs.getString("favorite_style"));
            return row;
        }, normalizedAccount, role);

        if (rows.isEmpty() || !matchesPassword(normalizedPassword, (String) rows.get(0).get("passwordHash"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        if ("禁用".equals(rows.get(0).get("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已停用");
        }
        if ("待审核".equals(rows.get(0).get("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号正在等待管理员审核，通过后才能登录");
        }

        Map<String, Object> row = rows.get(0);
        long userId = ((Number) row.get("id")).longValue();
        jdbc.update("update users set last_login_at = current_timestamp where id = ?", userId);

        // 签发若依令牌：JWT 里只放 uuid，真正的登录态存 Redis
        LoginUser loginUser = toLoginUser(row, role);
        String token = tokenService.createToken(loginUser);

        return buildSessionPayload(row, role, token);
    }

    public Map<String, Object> register(String account, String password, String nickname) {
        String normalizedAccount = normalize(account);
        String normalizedPassword = normalize(password);
        String displayName = normalize(nickname);
        if (normalizedAccount.isBlank() || normalizedPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入账号和密码");
        }
        if (normalizedPassword.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 6 位");
        }
        Integer exists = jdbc.queryForObject("select count(*) from users where account = ?", Integer.class, normalizedAccount);
        if (exists != null && exists > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账号已存在");
        }
        if (displayName.isBlank()) {
            displayName = normalizedAccount;
        }
        if (displayName.length() < 3 || displayName.length() > 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名长度需为 3-8 个字符");
        }

        // 新注册用户使用 BCrypt（若依标准），历史 SHA-256 账号仍可登录
        jdbc.update("""
                insert into users(nickname, account, password_hash, role, status, joined_at, last_login_at, try_count, favorite_style)
                values (?, ?, ?, 'user', '待审核', current_timestamp, current_timestamp, 0, null)
                """, displayName, normalizedAccount, BCRYPT.encode(normalizedPassword));

        // 同步到若依权限表，便于管理端统一查看
        try {
            Long newId = jdbc.queryForObject("select id from users where account = ? limit 1", Long.class, normalizedAccount);
            if (newId != null) {
                rbacBridge.syncUser(newId, normalizedAccount, displayName, ROLE_USER);
            }
        } catch (Exception ignored) {
            // 同步失败不影响注册
        }

        return Map.of(
                "pendingApproval", true,
                "status", "待审核",
                "message", "注册申请已提交，请等待管理员审核通过后再登录"
        );
    }

    // ------------------------------------------------------------------
    // 鉴权：从请求 / 裸 token 解析登录态
    // ------------------------------------------------------------------

    public Optional<AuthenticatedUser> authenticate(HttpServletRequest request) {
        return authenticateToken(bearerToken(request));
    }

    public Optional<AuthenticatedUser> authenticateToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            // 与若依 TokenService 相同的解析方式：JWT -> uuid -> Redis 中的 LoginUser
            Claims claims = Jwts.parser()
                    .setSigningKey(tokenSecret)
                    .parseClaimsJws(token)
                    .getBody();
            String uuid = (String) claims.get(Constants.LOGIN_USER_KEY);
            if (uuid == null || uuid.isBlank()) {
                return Optional.empty();
            }
            LoginUser loginUser = redisCache.getCacheObject(CacheConstants.LOGIN_TOKEN_KEY + uuid);
            if (loginUser == null || loginUser.getUser() == null) {
                return Optional.empty();
            }
            // 滑动续期：与若依一致，剩余不足 20 分钟自动续
            tokenService.verifyToken(loginUser);
            return Optional.of(toAuthenticatedUser(loginUser));
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    public AuthenticatedUser require(HttpServletRequest request, String role) {
        AuthenticatedUser user = authenticate(request)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
        if (!role.equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有访问权限");
        }
        return user;
    }

    public void logout(HttpServletRequest request) {
        String token = bearerToken(request);
        if (!token.isBlank()) {
            try {
                Claims claims = Jwts.parser()
                        .setSigningKey(tokenSecret)
                        .parseClaimsJws(token)
                        .getBody();
                String uuid = (String) claims.get(Constants.LOGIN_USER_KEY);
                if (uuid != null && !uuid.isBlank()) {
                    tokenService.delLoginUser(uuid);
                }
            } catch (Exception ignored) {
                // 令牌已失效或非法，登出视为成功
            }
        }
    }

    public String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length()).trim();
        }
        String fallback = request.getHeader("X-Auth-Token");
        return fallback == null ? "" : fallback.trim();
    }

    // ------------------------------------------------------------------
    // 口令：兼容历史 SHA-256 与若依 BCrypt
    // ------------------------------------------------------------------

    /** 历史 SHA-256 十六进制（64 位，无盐），保留以兼容既有账号。 */
    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Password hash failed", error);
        }
    }

    /** 若依标准 BCrypt 口令。 */
    public static String encodePassword(String rawPassword) {
        return BCRYPT.encode(rawPassword);
    }

    /**
     * 校验口令：BCrypt 哈希走 BCrypt，其余按历史 SHA-256 比对。
     * 这样存量账号无需重置密码即可继续登录。
     */
    private boolean matchesPassword(String rawPassword, String storedHash) {
        if (storedHash == null || storedHash.isBlank()) {
            return false;
        }
        if (storedHash.startsWith("$2a$") || storedHash.startsWith("$2b$") || storedHash.startsWith("$2y$")) {
            return BCRYPT.matches(rawPassword, storedHash);
        }
        return hashPassword(rawPassword).equals(storedHash);
    }

    public static long publicIdFor(long id) {
        if (id >= PUBLIC_USER_ID_FLOOR) {
            return id;
        }
        return PUBLIC_USER_ID_FLOOR + Math.max(0, id - 1);
    }

    // ------------------------------------------------------------------
    // 内部装配
    // ------------------------------------------------------------------

    /** 把 users 表的一行装配成若依 LoginUser（角色来自 sys_role，权限来自 sys_menu）。 */
    private LoginUser toLoginUser(Map<String, Object> row, String role) {
        long userId = ((Number) row.get("id")).longValue();
        String account = (String) row.get("account");
        String nickname = (String) row.get("nickname");

        // 把业务账号懒同步到若依权限表，使若依后台能统一管理
        rbacBridge.syncUser(userId, account, nickname, role);

        SysUser sysUser = new SysUser();
        sysUser.setUserId(userId);
        sysUser.setUserName(account);
        sysUser.setNickName(nickname);
        // 原样保留业务状态（正常/禁用/待审核/观察），避免破坏前端展示
        sysUser.setStatus((String) row.get("status"));
        sysUser.setDelFlag("0");
        // 角色从 sys_role 解析（若依后台调整角色即生效），失败时按业务角色兜底
        sysUser.setRoles(rbacBridge.resolveRoles(role));

        // 权限由若依 SysPermissionService 依据 sys_menu / sys_user_role 计算；
        // 管理员得到通配权限 *:*:*，普通用户得到 sys_menu 中配置的权限标识。
        Set<String> permissions;
        try {
            permissions = permissionService.getMenuPermission(sysUser);
        } catch (Exception error) {
            permissions = buildPermissions(role);
        }
        if (permissions == null || permissions.isEmpty()) {
            permissions = buildPermissions(role);
        }

        LoginUser loginUser = new LoginUser(userId, null, sysUser, permissions);
        loginUser.setUserId(userId);
        return loginUser;
    }

    /**
     * 权限集合：管理员授予若依通配权限 {@code *:*:*}；
     * 普通用户授予业务权限标识，便于后续按接口细分。
     */
    private Set<String> buildPermissions(String role) {
        if (ROLE_ADMIN.equals(role)) {
            return Set.of(Constants.ALL_PERMISSION);
        }
        return Set.of("nailglow:user");
    }

    private AuthenticatedUser toAuthenticatedUser(LoginUser loginUser) {
        SysUser sysUser = loginUser.getUser();
        return new AuthenticatedUser(
                loginUser.getUserId(),
                sysUser.getNickName(),
                sysUser.getUserName(),
                roleOf(loginUser),
                sysUser.getStatus()
        );
    }

    private String roleOf(LoginUser loginUser) {
        SysUser sysUser = loginUser.getUser();
        if (sysUser != null && sysUser.getRoles() != null) {
            for (SysRole sysRole : sysUser.getRoles()) {
                if (ROLE_ADMIN.equals(sysRole.getRoleKey())) {
                    return ROLE_ADMIN;
                }
            }
        }
        return ROLE_USER;
    }

    private Map<String, Object> buildSessionPayload(Map<String, Object> user, String role, String token) {
        long userId = ((Number) user.get("id")).longValue();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(expireTimeMinutes);

        Map<String, Object> visibleUser = new LinkedHashMap<>(user);
        visibleUser.remove("passwordHash");
        visibleUser.put("displayName", user.get("nickname"));
        visibleUser.put("publicId", publicIdFor(userId));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("expiresAt", expiresAt.toString());
        data.put(ROLE_ADMIN.equals(role) ? "admin" : "user", visibleUser);
        return data;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /** 供管理端展示在线用户等场景使用（若依能力透出）。 */
    public List<String> onlineTokens() {
        var keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
        return keys == null ? new ArrayList<>() : new ArrayList<>(keys);
    }

    /**
     * 踢出指定业务用户的全部登录态（Redis）。
     *
     * <p>重构前登录态存在 MySQL {@code auth_sessions} 表，管理端禁用/删除用户时
     * 只需删表行；现在登录态在 Redis，必须按 userId 扫描并删除对应 key，
     * 否则「禁用用户」后其旧令牌在有效期内仍可访问。
     *
     * @return 被清除的会话数量
     */
    public int kickOutUser(long userId) {
        int removed = 0;
        try {
            var keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
            if (keys == null) {
                return 0;
            }
            for (Object key : keys) {
                String cacheKey = String.valueOf(key);
                LoginUser loginUser = redisCache.getCacheObject(cacheKey);
                if (loginUser != null && loginUser.getUserId() != null && loginUser.getUserId() == userId) {
                    redisCache.deleteObject(cacheKey);
                    removed++;
                }
            }
        } catch (Exception ignored) {
            // 清理失败不应阻断管理操作
        }
        return removed;
    }

    /**
     * 按业务角色踢出全部登录态（用于「重置数据」清空所有普通用户）。
     *
     * @return 被清除的会话数量
     */
    public int kickOutUsersByRole(String role) {
        int removed = 0;
        try {
            var keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
            if (keys == null) {
                return 0;
            }
            for (Object key : keys) {
                String cacheKey = String.valueOf(key);
                LoginUser loginUser = redisCache.getCacheObject(cacheKey);
                if (loginUser != null && role.equals(roleOf(loginUser))) {
                    redisCache.deleteObject(cacheKey);
                    removed++;
                }
            }
        } catch (Exception ignored) {
            // 清理失败不应阻断管理操作
        }
        return removed;
    }
}
