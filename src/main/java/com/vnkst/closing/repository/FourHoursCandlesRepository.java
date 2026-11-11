package com.vnkst.closing.repository;

import com.vnkst.closing.entity.FourHoursCandles;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FourHoursCandlesRepository
        extends JpaRepository<FourHoursCandles, Long> {

    List<FourHoursCandles> findByFourRecordId(Long fourRecordId);

    @Query(value = "SELECT * FROM reactive.four_hours_candles WHERE four_record_id IN (:recordIds)", nativeQuery = true)
    List<FourHoursCandles> findByFourRecordIdsIn(@Param("recordIds") List<Long> recordIds);
}
