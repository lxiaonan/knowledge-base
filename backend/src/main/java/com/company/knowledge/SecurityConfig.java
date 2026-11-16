package com.company.knowledge;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean HttpSessionSecurityContextRepository contextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean UserDetailsService userDetailsService(Repository repo) {
        return username -> repo.db().sql("SELECT username, password_hash, role FROM app_user WHERE username = ?").param(username)
            .query((r, i) -> User.withUsername(r.getString("username")).password(r.getString("password_hash")).roles(r.getString("role")).build())
            .optional().orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
    }
    @Bean SecurityFilterChain security(HttpSecurity http, HttpSessionSecurityContextRepository contexts) throws Exception {
        return http
            .securityContext(c -> c.securityContextRepository(contexts))
            .authorizeHttpRequests(a -> a
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/auth/csrf", "/api/auth/login", "/api/system", "/actuator/health", "/error").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> { res.setStatus(401); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"message\":\"请先登录\"}"); })
                .accessDeniedHandler((req, res, ex) -> { res.setStatus(403); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"message\":\"权限不足或会话已过期，请刷新重试\"}"); }))
            .logout(l -> l.logoutUrl("/api/auth/logout").logoutSuccessHandler((req,res,auth) -> res.setStatus(204)))
            .build();
    }
}
