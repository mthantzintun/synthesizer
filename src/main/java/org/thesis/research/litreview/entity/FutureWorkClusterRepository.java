package org.thesis.research.litreview.entity;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FutureWorkClusterRepository extends JpaRepository<FutureWorkClusterEntity, Long> {

    List<FutureWorkClusterEntity> findAllByOrderByPaperCountDesc();
}

