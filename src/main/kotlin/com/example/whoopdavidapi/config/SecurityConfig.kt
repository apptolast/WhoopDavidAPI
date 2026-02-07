package com.example.whoopdavidapi.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${app.powerbi.username}") private val powerBiUsername: String,
    @Value("\${app.powerbi.password}") private val powerBiPassword: String
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun userDetailsService(passwordEncoder: PasswordEncoder): UserDetailsService {
        val user = User.builder()
            .username(powerBiUsername)
            .password(passwordEncoder.encode(powerBiPassword))
            .roles("POWERBI")
            .build()
        return InMemoryUserDetailsManager(user)
    }

    // Cadena para endpoints de la API (Basic Auth, stateless)
    @Bean
    @Order(1)
    fun apiSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.anyRequest().authenticated()
            }
            .httpBasic { }
        return http.build()
    }

    // Cadena para OAuth2 (flujo de autorizacion con Whoop)
    @Bean
    @Order(2)
    fun oauth2SecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/login/**", "/oauth2/**")
            .csrf { it.disable() }
            .oauth2Login { }
        return http.build()
    }

    // Cadena para endpoints publicos (actuator, H2 console)
    @Bean
    @Order(3)
    fun publicSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher(
                "/actuator/**", "/h2-console/**", "/mock/**",
                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
            )
            .csrf { it.disable() }
            .headers { headers -> headers.frameOptions { it.sameOrigin() } }
            .authorizeHttpRequests { auth ->
                auth.anyRequest().permitAll()
            }
        return http.build()
    }

    // Cadena catch-all para el resto de requests (denegar por defecto)
    @Bean
    @Order(4)
    fun defaultSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { auth ->
                auth.anyRequest().denyAll()
            }
        return http.build()
    }
}
