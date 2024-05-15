package com.ddd.server.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class SNRDatabases {

    private String url;
    private String uname;
    private String password;
    private String dbName;
    private String dbURL;

    public SNRDatabases(String url, String uname, String password, String dbName) throws SQLException {
        // this.url = url;
        this.uname = uname;
        this.password = password;
        this.dbName = dbName;
        this.dbURL = url;

//        createDatabase();
    }

    private void createDatabase() throws SQLException {
        String createQuery = "create database " + dbName;

        DriverManager.registerDriver(new com.mysql.cj.jdbc.Driver());
        Connection conn = DriverManager.getConnection(url, uname, password);
        Statement statement = conn.createStatement();

        try {
            statement.execute(createQuery);
            System.out.println("[SNRDB]: Created DB");
        } catch (SQLException e) {
            System.out.println("[SNRDB]: Database already exists");
        }

        conn.close();
    }

    public boolean createTable(String createQuery) throws SQLException {
        Connection conn = DriverManager.getConnection(dbURL, uname, password);
        Statement statement = conn.createStatement();
        boolean exists = false;

        try {
            statement.executeUpdate(createQuery);
            System.out.println("[SNRDB]: Created Table");
        } catch (SQLException e) {
            System.out.println("[SNRDB]: Table already exists");
            exists = true;
        }
        conn.close();
        return exists;
    }

    public void insertIntoTable(String insertQuery) throws SQLException {
        executeUpdate(insertQuery);
    }

    public void updateEntry(String updateQuery) throws SQLException {
        executeUpdate(updateQuery);
    }

    public List<String[]> getFromTable(String query) throws SQLException {
        return executeQuery(query);
    }

    private void executeUpdate(String query) throws SQLException {
        Connection conn = DriverManager.getConnection(dbURL, uname, password);
        Statement statement = conn.createStatement();

        statement.executeUpdate(query);
        conn.close();
        System.out.println("[SNRDB]: Executed " + query);
    }

    private List<String[]> executeQuery(String query) throws SQLException {
        Connection conn = DriverManager.getConnection(dbURL, uname, password);
        Statement statement = conn.createStatement();

        ResultSet rs = statement.executeQuery(query);
        int numCols = rs.getMetaData().getColumnCount();
        List<String[]> results = new ArrayList<>();

        while (rs.next()) {
            String[] result = new String[numCols];
            for (int i = 1; i <= numCols; ++i) {
                result[i - 1] = rs.getString(i);
            }
            results.add(result);
        }

        conn.close();
        System.out.println("[SNRDB]: Executed " + query);

        return results;
    }
}