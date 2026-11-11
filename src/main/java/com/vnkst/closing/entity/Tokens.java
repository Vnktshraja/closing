package com.vnkst.closing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Entity
@Table(name = "tokens", schema = "reactive")
public class Tokens implements Persistable<String> {

    @Id
    @Column(name = "token_ref_id")
    private String tokenRefId;

    @Column(name = "token")
    private String token;

    @Column(name = "session_name")
    private String sessionName;

    @Column(name = "expiry")
    private LocalDateTime expiry;

    @Column(name = "token_name")
    private String tokenName;

    @Column(name = "is_active")
    private boolean isActive;

    @Transient
    private boolean isNew = true;

    @Override
    @Transient
    public String getId() {
        return this.tokenRefId;
    }

    @Override
    @Transient
    public boolean isNew() {
        return this.isNew;
    }

    public void setNew(boolean isNew) {
        this.isNew = isNew;
    }
}

