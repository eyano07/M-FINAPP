package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.SalleRestaurant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SalleRestaurantRepository extends JpaRepository<SalleRestaurant, Long> {

    List<SalleRestaurant> findAllByOrderByOrdreAscIdAsc();
}
