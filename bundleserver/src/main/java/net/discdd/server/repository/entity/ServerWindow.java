package net.discdd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity(name = "ServerWindow")
@Table(name = "serverwindow")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ServerWindow {
    @Id
    @Column(name = "clientID", nullable = false, columnDefinition = "VARCHAR(256)")
    private String clientID;

    @Column(name = "startCounter", columnDefinition = "VARCHAR(256)")
    private String startCounter;

    @Column(name = "endCounter", columnDefinition = "VARCHAR(256)")
    private String endCounter;

    @Column(name = "windowLength", columnDefinition = "INTEGER")
    private int windowLength;

    @Override
    public String toString() {
        return "ServerWindow{" + "clientId='" + clientID + '\'' + ", startCounter='" + startCounter + '\'' +
                ", endCounter='" + endCounter + '\'' + ", windowLength=" + windowLength + '}';
    }
}
