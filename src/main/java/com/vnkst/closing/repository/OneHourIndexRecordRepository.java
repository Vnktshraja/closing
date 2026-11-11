package com.vnkst.closing.repository;

import com.vnkst.closing.entity.OneHourIndexRecord;
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
public interface OneHourIndexRecordRepository extends JpaRepository<OneHourIndexRecord, Long> {

    List<OneHourIndexRecord> findBySymbol(String symbol);

    List<OneHourIndexRecord> findBySymbolIn(Collection<String> symbols);

    List<OneHourIndexRecord> findByEnteredDate(LocalDate enteredDate);

    @Transactional
    @Modifying
    @Query(value = "TRUNCATE TABLE reactive.one_hour_index_candles, reactive.one_hour_index_record RESTART IDENTITY CASCADE", nativeQuery = true)
    void truncateTable();

    @Query(value = "SELECT * FROM reactive.one_hour_index_record WHERE entered_date <= :date", nativeQuery = true)
    List<OneHourIndexRecord> findAllBeforeOrEqualDate(@Param("date") LocalDate date);

    @Query(value = "SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM reactive.one_hour_index_record WHERE entered_date = :date", nativeQuery = true)
    boolean existsByEnteredDate(@Param("date") LocalDate date);
}

