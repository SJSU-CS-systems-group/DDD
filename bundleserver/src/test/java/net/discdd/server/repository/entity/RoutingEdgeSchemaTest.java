package net.discdd.server.repository.entity;

import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The production schema is MySQL with {@code ddl-auto: update}, which never alters an existing column type, so
 * {@code routing_edge.source} must be a VARCHAR rather than a native {@code enum(...)} that would reject a
 * {@code RoutingSource} value added later. Built against MySQLDialect without a database, since H2 tests cannot show it.
 */
class RoutingEdgeSchemaTest {
    @Test
    void sourceIsVarcharOnMySql() {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DIALECT, "org.hibernate.dialect.MySQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .build();
        try {
            var metadata = new MetadataSources(registry).addAnnotatedClass(RoutingEdge.class).buildMetadata();
            var table = metadata.getEntityBinding(RoutingEdge.class.getName()).getTable();
            var column = table.getColumn(new org.hibernate.mapping.Column("source"));
            assertNotNull(column, "routing_edge.source");
            assertEquals("varchar(16)", column.getSqlType(metadata).toLowerCase(Locale.ROOT));
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
