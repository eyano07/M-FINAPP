package com.mbsc.finapp.service;

import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Évalue réellement la garde {@code @PreAuthorize} d'une méthode pour un compte portant tels rôles — comme Spring le
 * fait à l'appel — au lieu de seulement relire la liste des rôles : un compte qui cumule plusieurs rôles est ainsi
 * couvert.
 */
final class GardesParRole {

    private GardesParRole() {}

    static boolean autorise(Class<?> classe, String methode, String... roles) {
        List<String> gardes = Arrays.stream(classe.getDeclaredMethods())
            .filter(m -> m.getName().equals(methode))
            .map(m -> m.getAnnotation(PreAuthorize.class))
            .filter(Objects::nonNull)
            .map(PreAuthorize::value)
            .distinct()
            .toList();
        assertEquals(1, gardes.size(), classe.getSimpleName() + "." + methode + " doit porter une garde @PreAuthorize");
        var compte = new UsernamePasswordAuthenticationToken("compte", null,
            Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        SecurityExpressionRoot racine = new SecurityExpressionRoot(compte) {};
        Boolean resultat = new SpelExpressionParser().parseExpression(gardes.get(0))
            .getValue(new StandardEvaluationContext(racine), Boolean.class);
        return Boolean.TRUE.equals(resultat);
    }
}
