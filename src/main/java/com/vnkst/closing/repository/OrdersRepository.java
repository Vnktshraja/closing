package com.vnkst.closing.repository;

import com.vnkst.closing.entity.Orders;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;


import java.time.LocalDate;

@Repository
public interface OrdersRepository extends ReactiveCrudRepository<Orders, String> {

}
