package com.vnkst.closing.repository;

import com.vnkst.closing.entity.FNOHighLow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface FNOHighLowRepository extends JpaRepository<FNOHighLow, Long> {

    Optional<FNOHighLow> findByTradingSymbolAndCreatedDate(String tradingSymbol, LocalDate createdDate);

    List<FNOHighLow> findByCreatedDate(LocalDate createdDate);

    @Transactional
    @Modifying
    @Query(value = "TRUNCATE TABLE reactive.fno_high_low RESTART IDENTITY CASCADE", nativeQuery = true)
    void truncateTable();

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM reactive.fno_high_low WHERE trading_symbol IN (:symbols)", nativeQuery = true)
    void deleteByTradingSymbolIn(@Param("symbols") Collection<String> symbols);

    @Query(value = "SELECT * FROM reactive.fno_high_low WHERE created_date = (SELECT MAX(created_date) FROM reactive.fno_high_low WHERE created_date <= :date)", nativeQuery = true)
    List<FNOHighLow> findLatestBeforeDate(@Param("date") LocalDate date);

    @Query(value = "SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM reactive.fno_high_low WHERE created_date = :date", nativeQuery = true)
    boolean existsByCreatedDate(@Param("date") LocalDate date);
}

