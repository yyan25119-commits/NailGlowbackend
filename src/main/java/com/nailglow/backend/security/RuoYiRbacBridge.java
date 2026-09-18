package com.nailglow.backend.security;

import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.system.service.ISysRoleService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NailGlow ↔ 若依 RBAC 桥接。
 *
 * <p>本项目的业务表 {@code users} 用一列 {@code role}（'admin' / 'user'）表示身份，
 * 若依用 {@code sys_user} + {@code sys_role} + {@code sys_user_role} 表达。
 * 为了「保留现有业务表 + 叠加若依权限体系」，这里做如下映射：
 *
 * <ul>
 *   <li>业务侧 {@code users.role='admin'} → 若依角色 {@code admin}（超级管理员，拥有 {@code *:*:*}）</li>
 *   <li>业务侧 {@code users.role='user'}  → 若依角色 {@code common}（权限由 sys_menu 决定）</li>
 * </ul>
 *
 * <p>同时把业务用户懒同步到 {@code sys_user} / {@code sys_user_role}，
 * 这样若依自带的用户、角色、菜单管理界面能看到并管理这些账号，
 * 而业务接口仍完全依赖 {@code users} 表，互不破坏。
 */
@Component
public class RuoYiRbacBridge {

    /** 若依内置角色 ID（ry_*.sql 种子数据）。 */
    private static final long ROLE_ID_ADMIN = 1L;
    private static final long ROLE_ID_COMMON = 2L;

    private final JdbcTemplate jdbc;
    private final ISysRoleService roleService;

    /** 已同步过的账号，避免每次登录都写库。 */
    private final Map<String, Boolean> synced = new ConcurrentHashMap<>();

    public RuoYiRbacBridge(JdbcTemplate jdbc, ISysRoleService roleService) {
        this.jdbc = jdbc;
        this.roleService = roleService;
    }

    /** 业务角色标识 → 若依角色 ID。 */
    public long ruoyiRoleId(String businessRole) {
        return "admin".equals(businessRole) ? ROLE_ID_ADMIN : ROLE_ID_COMMON;
    }

    /** 业务角色标识 → 若依角色 key。 */
    public String ruoyiRoleKey(String businessRole) {
        return "admin".equals(businessRole) ? "admin" : "common";
    }

    /**
     * 构造用于登录态的若依角色对象。
     *
     * <p>优先从 {@code sys_role} 读取真实角色（这样若依后台改角色名/权限会生效），
     * 读不到时退化为按业务角色构造的临时角色，保证登录不被权限表缺失阻断。
     */
    public List<SysRole> resolveRoles(String businessRole) {
        long roleId = ruoyiRoleId(businessRole);
        try {
            List<SysRole> all = roleService.selectRoleAll();
            for (SysRole role : all) {
                if (role.getRoleId() != null && role.getRoleId() == roleId) {
                    return List.of(role);
                }
            }
        } catch (Exception ignored) {
            // 权限表不可用时不应阻断登录
        }
        return List.of(fallbackRole(businessRole));
    }

    private SysRole fallbackRole(String businessRole) {
        SysRole role = new SysRole();
        role.setRoleId(ruoyiRoleId(businessRole));
        role.setRoleKey(ruoyiRoleKey(businessRole));
        role.setRoleName("admin".equals(businessRole) ? "超级管理员" : "普通角色");
        role.setStatus("0");
        return role;
    }

    /**
     * 把业务账号懒同步到若依权限表（sys_user / sys_user_role）。
     * 幂等：已存在则跳过。任何异常都不影响登录主流程。
     */
    public void syncUser(Long userId, String account, String nickname, String businessRole) {
        if (account == null || account.isBlank()) {
            return;
        }
        if (synced.putIfAbsent(account, Boolean.TRUE) != null) {
            return;
        }
        try {
            Integer exists = jdbc.queryForObject(
                    "select count(*) from sys_user where user_name = ?", Integer.class, account);
            if (exists != null && exists > 0) {
                return;
            }
            jdbc.update("""
                    insert into sys_user(user_id, dept_id, user_name, nick_name, user_type, email,
                                         phonenumber, sex, avatar, password, status, del_flag,
                                         login_ip, login_date, pwd_update_date, create_by, create_time,
                                         update_by, update_time, remark)
                    values (?, null, ?, ?, '00', null, null, '2', null, null, '0', '0',
                            null, null, null, 'nailglow', current_timestamp, null, null, 'NailGlow 业务账号')
                    """, userId, account, nickname == null ? account : nickname);
            jdbc.update("insert ignore into sys_user_role(user_id, role_id) values (?, ?)",
                    userId, ruoyiRoleId(businessRole));
        } catch (Exception ignored) {
            // 同步失败不影响业务登录
        }
    }

    /** 同步失败后允许重试（例如权限表后来才建好）。 */
    public void forgetSyncCache() {
        synced.clear();
    }

    /** 供管理端展示：当前若依侧可见的角色清单。 */
    public List<String> roleKeys() {
        List<String> keys = new ArrayList<>();
        try {
            for (SysRole role : roleService.selectRoleAll()) {
                keys.add(role.getRoleKey());
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return keys;
    }
}
