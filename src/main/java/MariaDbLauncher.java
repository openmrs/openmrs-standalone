/**
 * This class launches standalone at startup
 * Uses mariaDB  drive
 */
public class MariaDbLauncher.java(){

    private Connection connection;
    private static DBConnection dBConnection;

    public void EmbeddedMariaDB4j() throws Exception {
          DbConfigurationBuilder config =  DBConfigurationBuilder.newBuilder();
          config.setPort(0);// this port detects any free port, eg port= 3306,3316 depending on configuration
          DB db = DB.newEmbeddedDB(config.build());
          db.start();//starts the embedded db
          String user = "openmrs";// database name specification
          String pw = "root";// database password
          String tempDatabaseConnection = "jdbc:mysql://127.0.0.1:33328/openmrs?autoReconnect=true&sessionVariables=storage_engine=InnoDB&useUnicode=true&characterEncoding=UTF-8&server.initialize-user=true&createDatabaseIfNotExist=true&server.basedir=${project.build.directory}/demodatabase&server.datadir=${project.build.directory}/demodatabase/data&server.collation-server=utf8_general_ci&server.character-set-server=utf8&server.max_allowed_packet=32M";
          if(!user.equals("openmrs")&& pw.equals("root")){
              Class.forName("org.mariadb.jdbc.Driver");
              connection = DriverManager.getConnection(tempDatabaseConnection, user, pw);
              db.createDB(user, "root","");
          }

public static DBConnection getDBConnection() throws ClassNotFoundException, SQLException {
    if (dBConnection == null) {
        dBConnection = new DBConnection();
    }
    return dBConnection;
}

public Connection getConnection() {
    return connection;
}

}