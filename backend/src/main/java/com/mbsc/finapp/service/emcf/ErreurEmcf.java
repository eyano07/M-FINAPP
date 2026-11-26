package com.mbsc.finapp.service.emcf;

/** Échec de certification. {@code retentable} : panne passagère (réseau, 5xx) ; sinon refus définitif du dispositif. */
public class ErreurEmcf extends RuntimeException {

    private final boolean retentable;

    public ErreurEmcf(String message, boolean retentable, Throwable cause) {
        super(message, cause);
        this.retentable = retentable;
    }

    public boolean retentable() {
        return retentable;
    }
}
