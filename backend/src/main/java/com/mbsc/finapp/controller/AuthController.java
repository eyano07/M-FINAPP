package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.auth.AuthResponse;
import com.mbsc.finapp.dto.auth.LoginRequest;
import com.mbsc.finapp.dto.auth.RefreshRequest;
import com.mbsc.finapp.security.UserPrincipal;
import com.mbsc.finapp.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal UserPrincipal principal) {
        var user = principal.getDomainUser();
        // telephone/photoUrl inclus ici (et pas seulement dans ProfilResponse) pour que
        // l'avatar de l'App Bar affiche la photo partout, pas seulement sur /profil.
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("id", user.getId());
        body.put("email", user.getEmail());
        body.put("nom", user.getNom() == null ? "" : user.getNom());
        body.put("prenom", user.getPrenom() == null ? "" : user.getPrenom());
        body.put("telephone", user.getTelephone());
        body.put("photoUrl", user.getPhotoCheminStockage() != null ? "/profil/photo" : null);
        body.put("roles", principal.getAuthorities().stream()
            .map(a -> a.getAuthority().replace("ROLE_", ""))
            .collect(Collectors.toSet()));
        return ResponseEntity.ok(body);
    }
}
