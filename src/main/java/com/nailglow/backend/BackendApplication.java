package com.nailglow.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * NailGlow 启动类。
 *
 * <p>重构为若依（RuoYi-Vue 3.9.2 springboot3）基座后：
 * <ul>
 *   <li>扫描范围扩到 {@code com.ruoyi}，以装配内嵌的若依 framework / system 组件；
 *       业务代码仍在 {@code com.nailglow.backend}。</li>
 *   <li>排除 {@link DataSourceAutoConfiguration}：数据源由若依 {@code DruidConfig}
 *       以 {@code spring.datasource.druid.master} 显式构建（多数据源 + DynamicDataSource），
 *       否则 Hikari 会自动装配并与 Druid 冲突。</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = {"com.nailglow.backend", "com.ruoyi"}, exclude = {DataSourceAutoConfiguration.class})
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
        System.out.println("""
                (♥◠‿◠)ﾉﾞ  NailGlow 启动成功 (RuoYi-Vue + Redis)   ლ(´ڡ`ლ)ﾞ
                """);
    }

}
