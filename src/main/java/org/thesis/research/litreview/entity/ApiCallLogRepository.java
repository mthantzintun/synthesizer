package org.thesis.research.litreview.entity;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiCallLogRepository extends JpaRepository<ApiCallLogEntity, Long> {

    List<ApiCallLogEntity> findByCallType(String callType);

    List<ApiCallLogEntity> findByPaperId(Long paperId);
}
