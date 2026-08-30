package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Client;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClientRepository extends JpaRepository<Client, Long> {
    boolean existsByCodeIgnoreCase(String code);
    List<Client> findAllByOrderByNomAsc();
}
