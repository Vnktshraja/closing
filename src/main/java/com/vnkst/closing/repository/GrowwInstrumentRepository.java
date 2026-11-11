package com.vnkst.closing.repository;

import com.vnkst.closing.dto.FnoSymbolsDto;
import com.vnkst.closing.entity.GrowwInstruments;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface GrowwInstrumentRepository extends JpaRepository<GrowwInstruments, String> {

    @Query(value = "SELECT underlying_symbol AS symbol, instrument_type AS optionType, trading_symbol AS tradingSymbol FROM reactive.fnogrowwcsv WHERE underlying_symbol IS NOT NULL AND underlying_symbol <> ''", nativeQuery = true)
    List<FnoSymbolsDto> findAllFnoSymbolsDto();

    @Query(value = "SELECT trading_symbol FROM reactive.fnogrowwcsv", nativeQuery = true)
    List<String> findAllTradingSymbol();

    @Query(value = "SELECT DISTINCT ON (underlying_symbol) * FROM reactive.fnogrowwcsv WHERE underlying_symbol IS NOT NULL AND underlying_symbol <> ''", nativeQuery = true)
    List<GrowwInstruments> findAllSymbolAndLotSize();

    @Query(value = "SELECT DISTINCT underlying_symbol FROM reactive.fnogrowwcsv WHERE underlying_symbol IS NOT NULL AND underlying_symbol <> '' ORDER BY underlying_symbol ASC", nativeQuery = true)
    List<String> findDistinctUnderlyingSymbols();

    @Query(value = "SELECT DISTINCT trading_symbol FROM reactive.fnogrowwcsv WHERE instrument_type = 'IDX' OR instrument_type = 'SENSEX'", nativeQuery = true)
    List<String> fetchIndexSymbol();

    @Transactional
    @Modifying
    @Query(value = "TRUNCATE TABLE reactive.fnogrowwcsv RESTART IDENTITY", nativeQuery = true)
    void truncateAndResetIdentity();
}
