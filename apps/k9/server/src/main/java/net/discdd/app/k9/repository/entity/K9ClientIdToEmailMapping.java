package net.discdd.app.k9.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor
@AllArgsConstructor
public class K9ClientIdToEmailMapping {
    @Id
    public String email;
    @Column(nullable = false)
    public String clientId;
    @Column(columnDefinition = "text", nullable = false)
    public String password;

    public String toString() {
        return String.format("%s -> %s", email, clientId);
    }
}
