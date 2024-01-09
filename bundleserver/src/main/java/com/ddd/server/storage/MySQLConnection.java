package com.ddd.server.storage;


import java.sql.Connection;
import java.sql.DriverManager;

public class MySQLConnection {
    public MySQLConnection(){

    }

    public Connection GetConnection(){
        try{
            Class.forName("com.mysql.jdbc.Driver");
            Connection con= DriverManager.getConnection(
                    "jdbc:mysql://localhost:3306/dtn_server_db?useUnicode=true&useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false&serverTimezone=UTC","root","Triquenguyen@2702");
            return con;
            /*Statement stmt=con.createStatement();
            ResultSet rs=stmt.executeQuery("select * from emp");
            while(rs.next())
                System.out.println(rs.getInt(1)+"  "+rs.getString(2)+"  "+rs.getString(3));
            con.close();*/
        }catch(Exception e){
            System.out.println(e);
        }
        return null;
    }

}
