package com.nailglow.backend.security;

import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.model.LoginUser;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.function.Supplier;

/**
 * NailGlow 角色授权管理器。
 *
 * <p>为什么不用 Spring Security 的 {@code hasRole(...)}：
 * 若依的 {@link LoginUser#getAuthorities()} 固定返回 {@code null}（权限不走 GrantedAuthority，
 * 而是由 {@code @ss.hasPermi(...)} 基于 Redis 中的 permissions 集合判断），
 * 因此 {@code hasRole()} 在这里恒为 false。本类直接从 {@link LoginUser#getUser()} 的
 * 角色列表读取 {@code roleKey} 判断。
 *
 * <p>严格复刻重构前 {@code AuthRequiredFilter} 的语义：
 * <ul>
 *   <li>未登录（无令牌/令牌失效）-> 返回 null 决策，交由 AuthenticationEntryPoint 输出 401</li>
 *   <li>已登录但角色不等于所需角色 -> 拒绝，交由 AccessDeniedHandler 输出 403</li>
 * </ul>
 * 角色为「精确匹配」，与原实现一致（admin 令牌不能访问 /api/user/**）。
 */
public class NailGlowAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final String requiredRole;

    public NailGlowAuthorizationManager(String requiredRole) {
        this.requiredRole = requiredRole;
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || isAnonymous(auth)) {
            // 未认证：必须返回「拒绝」而不是 null。
            // Spring Security 的 AuthorizationFilter 只在 decision != null 且 !granted 时
            // 抛 AccessDeniedException；返回 null 等于「弃权」，最终会被当作放行，
            // 导致未带令牌也能访问受保护接口。
            // 返回 false 后由 ExceptionTranslationFilter 识别为匿名身份，
            // 转而调用 AuthenticationEntryPoint，输出 401 {"code":401,...}。
            return new AuthorizationDecision(false);
        }
        Object principal = auth.getPrincipal();
        if (!(principal instanceof LoginUser loginUser) || loginUser.getUser() == null) {
            return new AuthorizationDecision(false);
        }
        return new AuthorizationDecision(requiredRole.equals(roleOf(loginUser)));
    }

    private boolean isAnonymous(Authentication auth) {
        return auth instanceof AnonymousAuthenticationToken;
    }

    private String roleOf(LoginUser loginUser) {
        if (loginUser.getUser().getRoles() != null) {
            for (SysRole role : loginUser.getUser().getRoles()) {
                if ("admin".equals(role.getRoleKey())) {
                    return "admin";
                }
            }
        }
        return "user";
    }
}
