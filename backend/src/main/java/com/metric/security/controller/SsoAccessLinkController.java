package com.metric.security.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Legacy product link: authenticate through UserManager before creating a local session. */
@RestController
@RequestMapping("/api/sso")
public class SsoAccessLinkController {
    private final OidcSsoController oidc;

    public SsoAccessLinkController(OidcSsoController oidc) {
        this.oidc = oidc;
    }

    @GetMapping("/access-link")
    public ResponseEntity<Void> accessLink(@RequestParam(required = false) String username,
                                           @RequestParam(required = false) String target,
                                           HttpServletRequest request) {
        // username is kept only for old links. The authenticated IdP identity decides the local user.
        return oidc.silentEntry(target, request);
    }
}
