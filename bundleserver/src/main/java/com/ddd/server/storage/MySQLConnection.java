package com.ddd.server.storage;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MySQLConnection {
    @Value("${spring.datasource.driver-class-name}")
    private String driver;

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String uname;

    @Value("${spring.datasource.password}")
    private String password;

    public MySQLConnection() {}

    public Connection GetConnection() {
        try {
            System.out.println("DB connection begins");
            Class.forName(driver);

//            Connection con= DriverManager.getConnection(
//                   "jdbc:mysql://localhost:3306/dtn_server_db?useUnicode=true&useJDBCCompliantTimezoneShift=true
//                   &useLegacyDatetimeCode=false&serverTimezone=UTC","root","Triquenguyen@2702");

            Connection con = DriverManager.getConnection(url, uname, password);
            System.out.println("DB connected" + url + uname + password);
            return con;

            /*Statement stmt=con.createStatement();
            ResultSet rs=stmt.executeQuery("select * from emp");
            while(rs.next())
                System.out.println(rs.getInt(1)+"  "+rs.getString(2)+"  "+rs.getString(3));
            con.close();*/
        } catch (Exception e) {
            System.out.println(e);
        }
        return null;
    }

}
