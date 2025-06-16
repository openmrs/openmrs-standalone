package org.openmrs.standalone;

import java.io.File;

import static org.openmrs.standalone.OpenmrsUtil.importSqlFile;

public class DbInitializer {
    // This is to be called via pom-step-04 with id 'import-demo-sql'
    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver").newInstance();

        File sqlFilePath = new File("Demo-1.9.0.sql");
        String jdbcUrl = "jdbc:mysql://127.0.0.1:33328/openmrs?autoReconnect=true&useUnicode=true&characterEncoding=UTF-8&zeroDateTimeBehavior=convertToNull";
        String username = "openmrs";
        String password = "test";

        importSqlFile(sqlFilePath, jdbcUrl, username, password);
    }
}
