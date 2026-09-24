package ru.example.ukep.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    public static final String ADMIN_CTX_KEY = "ADMIN_SECURITY_CONTEXT";
    public static final String USER_CTX_KEY  = "USER_SECURITY_CONTEXT";

    @Value("${app.admin-ip-whitelist}")
    private String adminIpWhitelist;

    @Bean(name = "adminContextRepository")
    public HttpSessionSecurityContextRepository adminContextRepository() {
        HttpSessionSecurityContextRepository repo = new HttpSessionSecurityContextRepository();
        repo.setSpringSecurityContextKey(ADMIN_CTX_KEY);
        return repo;
    }

    @Bean(name = "userContextRepository")
    public HttpSessionSecurityContextRepository userContextRepository() {
        HttpSessionSecurityContextRepository repo = new HttpSessionSecurityContextRepository();
        repo.setSpringSecurityContextKey(USER_CTX_KEY);
        return repo;
    }

    private Filter adminIpFilter() {
        List<String> allowed = Arrays.stream(adminIpWhitelist.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                String ip = ((HttpServletRequest) req).getRemoteAddr();
                if (!allowed.contains(ip)) {
                    log.warn("Blocked admin access from IP: {}", ip);
                    ((HttpServletResponse) res).sendError(HttpServletResponse.SC_FORBIDDEN,
                            "Admin area available only from server");
                    return;
                }
                chain.doFilter(req, res);
            }
        };
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminChain(
            HttpSecurity http,
            UserDetailsService uds,
            @Qualifier("adminContextRepository") HttpSessionSecurityContextRepository adminRepo) throws Exception {

        log.info("Admin IP whitelist: {}",
                Arrays.stream(adminIpWhitelist.split(",")).map(String::trim).toList());

        http
            .securityMatcher("/admin/**", "/api/sign/admin/**")
            .userDetailsService(uds)
            .securityContext(sc -> sc.securityContextRepository(adminRepo))
            .sessionManagement(s -> s.sessionFixation().none())
            .addFilterBefore(adminIpFilter(), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                .requestMatchers("/admin/login", "/admin/challenge", "/admin/cert-login").permitAll()
                .anyRequest().hasRole("ADMIN"))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .csrf(c -> c.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain userChain(
            HttpSecurity http,
            UserDetailsService uds,
            @Qualifier("userContextRepository") HttpSessionSecurityContextRepository userRepo) throws Exception {

        http
            .userDetailsService(uds)
            .securityContext(sc -> sc.securityContextRepository(userRepo))
            .sessionManagement(s -> s.sessionFixation().none())
            .authorizeHttpRequests(a -> a
                .requestMatchers("/", "/login", "/login/confirm", "/error",
                                 "/ping",
                                 "/css/**", "/js/**", "/favicon.ico",
                                 "/h2-console/**").permitAll()
                .anyRequest().authenticated())
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .headers(h -> h.frameOptions(f -> f.sameOrigin()));
        return http.build();
    }
}
