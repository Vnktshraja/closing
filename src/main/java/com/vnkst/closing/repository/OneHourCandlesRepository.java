package com.vnkst.closing.repository;

import com.vnkst.closing.entity.OneHourCandles;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OneHourCandlesRepository extends JpaRepository<OneHourCandles, Long> {

    List<OneHourCandles> findByOneRecordId(Long oneRecordId);

    @Query(value = "SELECT * FROM reactive.one_hour_candles WHERE one_record_id IN (:recordIds)", nativeQuery = true)
    List<OneHourCandles> findByOneRecordIdsIn(@Param("recordIds") List<Long> recordIds);
}

