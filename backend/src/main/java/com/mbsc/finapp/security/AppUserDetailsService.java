package com.mbsc.finapp.security;

import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * @param identifiant adresse e-mail ou nom d'utilisateur (voir {@link IdentifiantConnexion}) ;
     *                    aussi le sujet des jetons, qui est l'identifiant tel qu'enregistre.
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String identifiant) throws UsernameNotFoundException {
        return trouver(identifiant)
            .map(UserPrincipal::new)
            .orElseThrow(() -> new UsernameNotFoundException("Utilisateur introuvable: " + identifiant));
    }

    /**
     * Correspondance exacte d'abord (cas des jetons), puis sans tenir compte de la casse : « Dfin_Bruno »
     * retrouve « dfin_bruno ». Si plusieurs comptes ne different que par la casse (anciennes donnees), on
     * ne devine pas : aucun compte n'est retourne et la connexion est refusee.
     */
    private Optional<User> trouver(String identifiant) {
        if (identifiant == null || identifiant.isBlank()) {
            return Optional.empty();
        }
        String saisi = identifiant.trim();
        Optional<User> exact = userRepository.findByEmail(saisi);
        if (exact.isPresent()) {
            return exact;
        }
        List<User> memeSansCasse = userRepository.findAllByEmailIgnoreCase(saisi);
        return memeSansCasse.size() == 1 ? Optional.of(memeSansCasse.get(0)) : Optional.empty();
    }
}
