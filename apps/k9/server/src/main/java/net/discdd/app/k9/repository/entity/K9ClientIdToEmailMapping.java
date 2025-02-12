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

    public K9ClientIdToEmailMapping() {}

    public K9ClientIdToEmailMapping(String email, String clientId, String password) {
        this.email = email;
        this.clientId = clientId;
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
