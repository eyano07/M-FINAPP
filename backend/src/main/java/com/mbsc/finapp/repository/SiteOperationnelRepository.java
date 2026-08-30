package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.SiteOperationnel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SiteOperationnelRepository extends JpaRepository<SiteOperationnel, Long> {
    List<SiteOperationnel> findByActifTrueOrderByNom();
    List<SiteOperationnel> findAllByOrderByNom();
}
