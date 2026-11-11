package com.vnkst.closing.repository;

import com.vnkst.closing.entity.FourHoursRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface FourHoursRecordRepository
        extends JpaRepository<FourHoursRecord, Long> {

    List<FourHoursRecord> findBySymbol(String symbol);

    List<FourHoursRecord> findBySymbolIn(Collection<String> symbols);

    List<FourHoursRecord> findByEnteredDate(LocalDate enteredDate);

    @Transactional
    @Modifying
    @Query(value = "TRUNCATE TABLE reactive.four_hours_candles, reactive.four_hours_record RESTART IDENTITY CASCADE", nativeQuery = true)
    void truncateTable();

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM reactive.four_hours_record WHERE symbol IN (:symbols)", nativeQuery = true)
    void deleteBySymbolIn(@Param("symbols") Collection<String> symbols);

    @Query(value = "SELECT * FROM reactive.four_hours_record WHERE entered_date <= :date", nativeQuery = true)
    List<FourHoursRecord> findAllBeforeOrEqualDate(@Param("date") LocalDate date);

    @Query(value = "SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM reactive.four_hours_record WHERE entered_date = :date", nativeQuery = true)
    boolean existsByEnteredDate(@Param("date") LocalDate date);
}
