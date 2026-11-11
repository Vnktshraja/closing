package com.vnkst.closing.repository;

import com.vnkst.closing.entity.Tokens;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface TokenRepository extends JpaRepository<Tokens, String> {

    @Query(value = "SELECT EXISTS (SELECT 1 FROM reactive.tokens)", nativeQuery = true)
    boolean checkWhetherTableIsNotEmpty();
}

