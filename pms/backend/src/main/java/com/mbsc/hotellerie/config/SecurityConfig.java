package com.mbsc.hotellerie.config;

import com.mbsc.hotellerie.securite.JetonFinappFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/**
 * Aucune session serveur, aucun mot de passe ici : chaque requête porte le jeton M-FINAPP de l'utilisateur, vérifié par
 * {@link JetonFinappFilter}. Restent ouverts : la santé (test de M-FINAPP), l'état de l'appairage, la connexion.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filtres(HttpSecurity http, JetonFinappFilter jeton) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/sante", "/appairage", "/auth/**", "/error", "/public/**", "/ical/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jeton, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Le filtre ne s'exécute que dans la chaîne de sécurité (pas une seconde fois comme filtre de servlet). */
    @Bean
    FilterRegistrationBean<JetonFinappFilter> pasDeDoubleEnregistrement(JetonFinappFilter jeton) {
        FilterRegistrationBean<JetonFinappFilter> r = new FilterRegistrationBean<>(jeton);
        r.setEnabled(false);
        return r;
    }
}
