package org.thesis.research.litreview.entity;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperRepository extends JpaRepository<Paper, Long> {

    Optional<Paper> findByCitekey(String citekey);

    List<Paper> findByStatus(Paper.IngestStatus status);

    boolean existsByCitekey(String citekey);
}

