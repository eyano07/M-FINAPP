package com.mbsc.finapp.service.emcf;

import org.springframework.util.StringUtils;

/** Configuration effective du dispositif e-MCF. */
public record ConfigEmcf(boolean actif, String mode, String urlBase, String jeton, String numeroDef, int delaiMs) {

    public boolean simulation() {
        return "SIMULATION".equals(mode);
    }

    /** Le dispositif est joignable : simulation, ou adresse du e-MCF renseignée. */
    public boolean utilisable() {
        return simulation() || StringUtils.hasText(urlBase);
    }
}
