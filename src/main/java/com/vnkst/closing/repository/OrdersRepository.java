package com.multiplier.reactive.repository;

import com.multiplier.reactive.entity.Orders;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

@Repository
public interface OrdersRepository extends ReactiveCrudRepository<Orders, String> {

    @Query(value = "SELECT * FROM reactive.orders WHERE symbol = :symbol AND segment = :segment AND order_state = :state LIMIT 1")
    Mono<Orders> getOpenOrder(@Param("symbol") String symbol, @Param("segment") String segment,
            @Param("state") String state);

    @Query(value = "SELECT * FROM reactive.orders WHERE symbol = :symbol AND segment = :segment")
    Mono<Orders> getOpenOrder(@Param("symbol") String symbol, @Param("segment") String segment);

    @Query(value = "SELECT * FROM reactive.orders WHERE transaction_date = :todaysDate")
    Flux<Orders> getOpenOrderWithDate(@Param("todaysDate") LocalDate todaysDate);

    @Query(value = "SELECT * FROM reactive.orders WHERE order_state = 'ACTIVE'")
    Flux<Orders> getOpenOrder();

    @Query(value = "SELECT * FROM reactive.orders WHERE order_state = 'ACTIVE' OR transaction_date = :todaysDate")
    Flux<Orders> getActiveOrTodayOrders(@Param("todaysDate") LocalDate todaysDate);

    @Query(value = "SELECT * FROM reactive.orders WHERE symbol = :symbol AND order_state = :state AND transaction_date = :date LIMIT 1")
    Mono<Orders> getOrderBySymbolStateAndDate(@Param("symbol") String symbol, @Param("state") String state, @Param("date") LocalDate date);

    @Query(value = "SELECT * FROM reactive.orders WHERE symbol = :symbol AND order_state = :state")
    Flux<Orders> getOpenOrderBySymbolAndStatus(@Param("symbol") String symbol, @Param("state") String state);

    @Query(value = "SELECT * FROM reactive.orders WHERE order_state = :state")
    Flux<Orders> getOrdersByState(@Param("state") String state);
}
