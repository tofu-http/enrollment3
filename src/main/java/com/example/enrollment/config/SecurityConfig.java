package com.example.enrollment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/", "/login", "/css/**", "/images/**", "/js/**",
                    "/enroll", "/personal-information", "/contact-info",
                    "/family-info", "/educational-bg", "/requirements",
                    "/submit-enrollment", "/account_status", "/status" //
                ).permitAll()
                // Only allow Admins to access the Dashboard
                .requestMatchers("/admin/dashboard").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .csrf(csrf -> csrf.disable()) // Note: Enable this in production!
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(roleBasedSuccessHandler()) // Replaces defaultSuccessUrl
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            );

        return http.build();
    }

    @Bean
    public AuthenticationSuccessHandler roleBasedSuccessHandler() {
        return (request, response, authentication) -> {
            var roles = authentication.getAuthorities();
            
            // Logic to check the role of the person logging in
            boolean isAdmin = roles.stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN"));

            if (isAdmin) {
                response.sendRedirect("/admin/dashboard");
            } else {
                // For students/regular users
                response.sendRedirect("/index");
            }
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}