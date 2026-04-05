package com.evacuation.engine.repository;

import com.evacuation.engine.model.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RouteRepository extends JpaRepository<Route, Long> {

    List<Route> findByBlocked(boolean blocked);

    List<Route> findByHighTraffic(boolean highTraffic);

}