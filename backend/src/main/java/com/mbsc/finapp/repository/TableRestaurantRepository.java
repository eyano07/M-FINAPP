package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.TableRestaurant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TableRestaurantRepository extends JpaRepository<TableRestaurant, Long> {

    List<TableRestaurant> findBySalleIdOrderByIdAsc(Long salleId);
}
