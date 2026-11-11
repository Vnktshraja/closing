package com.vnkst.closing.repository;

import com.vnkst.closing.entity.OneHourIndexCandles;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OneHourIndexCandlesRepository extends JpaRepository<OneHourIndexCandles, Long> {

    List<OneHourIndexCandles> findByOneRecordId(Long oneRecordId);

    @Query(value = "SELECT * FROM reactive.one_hour_index_candles WHERE one_record_id IN (:recordIds)", nativeQuery = true)
    List<OneHourIndexCandles> findByOneRecordIdsIn(@Param("recordIds") List<Long> recordIds);
}

