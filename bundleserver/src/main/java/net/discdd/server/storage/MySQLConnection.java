package net.discdd.server.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

@Component
public class MySQLConnection {
    private static final Logger logger = Logger.getLogger(MySQLConnection.class.getName());

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
            logger.log(INFO, "DataBase connection begins");
            Class.forName(driver);

//            Connection con= DriverManager.getConnection(
//                   "jdbc:mysql://localhost:3306/dtn_server_db?useUnicode=true&useJDBCCompliantTimezoneShift=true
//                   &useLegacyDatetimeCode=false&serverTimezone=UTC","root","Triquenguyen@2702");

            Connection con = DriverManager.getConnection(url, uname, password);
            logger.log(INFO, "DataBase connected" + url + uname + password);
            return con;

            /*Statement stmt=con.createStatement();
            ResultSet rs=stmt.executeQuery("select * from emp");
            while(rs.next())
                logger.log(WARNING,rs.getInt(1)+"  "+rs.getString(2)+"  "+rs.getString(3));
            con.close();*/
        } catch (Exception e) {
            logger.log(SEVERE, "Problem getting connection", e);
        }
        return null;
    }

}
