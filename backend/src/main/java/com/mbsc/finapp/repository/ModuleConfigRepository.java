package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.ModuleConfig;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ModuleConfigRepository extends JpaRepository<ModuleConfig, ModuleMetier> {

    /**
     * Toutes les lignes, verrouillees en ecriture jusqu'a la fin de la transaction : serialise deux bascules de
     * modules simultanees, sans quoi chacune calculerait ses comptes sur l'etat de l'autre avant sa validation et
     * pourrait laisser un compte bloque mais actif (voir SynchronisationComptesModules).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ModuleConfig m")
    List<ModuleConfig> findAllPourMiseAJour();
}
