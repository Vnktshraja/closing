package com.vnkst.closing.repository;

import com.vnkst.closing.entity.Orders;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrdersRepository extends JpaRepository<Orders, String> {
    @Query(value = "SELECT * FROM reactive.orders WHERE symbol = :symbol AND segment = :segment AND order_state = :state LIMIT 1", nativeQuery = true)
    Optional<Orders> getOpenOrder(@Param("symbol") String symbol, @Param("segment") String segment,
                                  @Param("state") String state);

    @Query(value = "SELECT * FROM reactive.orders WHERE symbol = :symbol AND segment = :segment LIMIT 1", nativeQuery = true)
    Optional<Orders> getOpenOrder(@Param("symbol") String symbol, @Param("segment") String segment);

    @Query(value = "SELECT * FROM reactive.orders WHERE transaction_date = :todaysDate", nativeQuery = true)
    List<Orders> getOpenOrderWithDate(@Param("todaysDate") LocalDate todaysDate);

    @Query(value = "SELECT * FROM reactive.orders WHERE order_state = 'ACTIVE'", nativeQuery = true)
    List<Orders> getOpenOrder();

    @Query(value = "SELECT * FROM reactive.orders WHERE order_state = 'ACTIVE' OR transaction_date = :todaysDate", nativeQuery = true)
    List<Orders> getActiveOrTodayOrders(@Param("todaysDate") LocalDate todaysDate);

    @Query(value = "SELECT * FROM reactive.orders WHERE symbol = :symbol AND order_state = :state AND transaction_date = :date LIMIT 1", nativeQuery = true)
    Optional<Orders> getOrderBySymbolStateAndDate(@Param("symbol") String symbol, @Param("state") String state, @Param("date") LocalDate date);

    @Query(value = "SELECT * FROM reactive.orders WHERE symbol = :symbol AND order_state = :state", nativeQuery = true)
    List<Orders> getOpenOrderBySymbolAndStatus(@Param("symbol") String symbol, @Param("state") String state);

    @Query(value = "SELECT * FROM reactive.orders WHERE order_state = :state", nativeQuery = true)
    List<Orders> getOrdersByState(@Param("state") String state);
}
