package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.GroupeTaxeDgi;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GroupeTaxeDgiRepository extends JpaRepository<GroupeTaxeDgi, String> {
    List<GroupeTaxeDgi> findAllByOrderByOrdreAscCodeAsc();
}
