package com.vnkst.closing.repository;

import com.vnkst.closing.entity.PredictedStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
public interface PredictedStockRepository extends JpaRepository<PredictedStock, Long> {
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM reactive.predicted_stocks WHERE prediction_date = :predictionDate AND analysis_type = :analysisType", nativeQuery = true)
    void deleteByPredictionDateAndAnalysisType(
            @Param("predictionDate") LocalDate predictionDate,
            @Param("analysisType") String analysisType);

    List<PredictedStock> findBySymbol(String symbol);

    List<PredictedStock> findByPredictionDate(LocalDate predictionDate);

    List<PredictedStock> findByPredictionDateAndAnalysisType(LocalDate predictionDate, String analysisType);

    List<PredictedStock> findByAnalysisType(String analysisType);

    List<PredictedStock> findByOptionType(String optionType);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM reactive.predicted_stocks WHERE symbol = :symbol AND prediction_date = :predictionDate AND analysis_type = :analysisType)", nativeQuery = true)
    boolean existsBySymbolAndPredictionDateAndAnalysisType(
            @Param("symbol") String symbol,
            @Param("predictionDate") LocalDate predictionDate,
            @Param("analysisType") String analysisType);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM reactive.predicted_stocks WHERE prediction_date = :predictionDate AND analysis_type = :analysisType)", nativeQuery = true)
    boolean existsByPredictionDateAndAnalysisType(
            @Param("predictionDate") LocalDate predictionDate,
            @Param("analysisType") String analysisType);

    @Query(value = "SELECT * FROM reactive.predicted_stocks WHERE analysis_type NOT IN ('WEEK_HIGH_52', 'WEEK_LOW_52') ORDER BY prediction_date DESC, created_at DESC", nativeQuery = true)
    List<PredictedStock> findPredictionsExcept52Week();

    @Query(value = "SELECT * FROM reactive.predicted_stocks WHERE prediction_date = :predictionDate AND analysis_type NOT IN ('WEEK_HIGH_52', 'WEEK_LOW_52') ORDER BY prediction_date DESC, created_at DESC", nativeQuery = true)
    List<PredictedStock> findByPredictionDateExcept52Week(@Param("predictionDate") LocalDate predictionDate);

    @Transactional
    @Modifying
    @Query(value = "TRUNCATE TABLE reactive.predicted_stocks RESTART IDENTITY", nativeQuery = true)
    void truncateAndResetIdentity();

    default List<PredictedStock> saveAllDeduplicated(Iterable<PredictedStock> predictions) {
        if (predictions == null) {
            return List.of();
        }
        List<PredictedStock> saved = new ArrayList<>();
        Set<String> seenInBatch = new HashSet<>();
        for (PredictedStock p : predictions) {
            if (p != null && p.getSymbol() != null && p.getPredictionDate() != null && p.getAnalysisType() != null) {
                String key = p.getSymbol() + ":" + p.getPredictionDate() + ":" + p.getAnalysisType();
                if (seenInBatch.add(key)) {
                    boolean exists = existsBySymbolAndPredictionDateAndAnalysisType(
                            p.getSymbol(), p.getPredictionDate(), p.getAnalysisType());
                    if (!exists) {
                        saved.add(save(p));
                    }
                }
            }
        }
        return saved;
    }
}
