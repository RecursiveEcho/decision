package dev.decision;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class AccountMigrationTest {
  @Test
  void upgradePopulatedV8WithoutReassigningOrChangingPayloads() throws Exception {
    String url = System.getenv("TEST_DATABASE_URL");
    assertTrue(url != null && url.endsWith("/decision_backend_test"));
    url += "?socketFactory=dev.decision.DirectSocketFactory";
    String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
    String user = "decision_test", password = "synthetic-test-only";
    String p = Json.id(), message = Json.id(), job = Json.id(), call = Json.id();
    try (Connection c = DriverManager.getConnection(url, user, password);
        Statement sql = c.createStatement()) {
      sql.execute("CREATE SCHEMA " + schema);
      try {
        sql.execute("SET search_path TO " + schema);
        Flyway.configure()
            .dataSource(url, user, password)
            .schemas(schema)
            .target("8")
            .load()
            .migrate();
        sql.executeUpdate(
            "INSERT INTO records(id,kind,data) VALUES ('"
                + p
                + "','problem','{\"title\":\"synthetic\"}')");
        sql.executeUpdate(
            "INSERT INTO records(id,problem_id,kind,data) VALUES ('"
                + message
                + "','"
                + p
                + "','message','{}'),('"
                + job
                + "','"
                + p
                + "','job','{}')");
        sql.executeUpdate(
            "INSERT INTO attachments(id,message_id,problem_id,position,bytes,metadata)"
                + " VALUES(gen_random_uuid(),'"
                + message
                + "','"
                + p
                + "',0,decode('010203','hex'),'{}')");
        sql.executeUpdate(
            "INSERT INTO record_history(record_id,kind,data) VALUES ('"
                + p
                + "','problem','{\"revision\":2}')");
        sql.executeUpdate(
            "INSERT INTO jobs(id,problem_id,input) VALUES('"
                + job
                + "','"
                + p
                + "','{\"operation\":\"chat\"}')");
        sql.executeUpdate(
            "INSERT INTO model_calls(id,model,status) VALUES('"
                + call
                + "','synthetic','succeeded')");
        sql.executeUpdate(
            "INSERT INTO idempotency_keys(scope,key,request_hash,status,response)"
                + " VALUES('owner:/example',gen_random_uuid(),'synthetic-hash',200,'synthetic-ciphertext')");
        sql.executeUpdate(
            "INSERT INTO chat_memory_tombstones VALUES('*','synthetic','synthetic',17)");
        sql.executeUpdate("UPDATE chat_memory_state SET epoch=5");
        sql.executeUpdate("UPDATE context_version SET revision=9");
        Flyway.configure().dataSource(url, user, password).schemas(schema).load().migrate();
        assertEquals("owner", one(sql, "SELECT username FROM accounts WHERE role='admin'"));
        for (String table :
            new String[] {
              "records",
              "jobs",
              "model_calls",
              "chat_memory_tombstones",
              "chat_memory_state",
              "context_version",
              "idempotency_keys"
            })
          assertEquals(
              AccountScope.ADMIN, one(sql, "SELECT DISTINCT account_id::text FROM " + table));
        assertEquals("3", one(sql, "SELECT count(*) FROM records"));
        assertEquals("9", one(sql, "SELECT revision FROM context_version"));
        assertEquals("5", one(sql, "SELECT epoch FROM chat_memory_state"));
        assertEquals("17", one(sql, "SELECT blocked_through_seq FROM chat_memory_tombstones"));
        assertEquals("synthetic-ciphertext", one(sql, "SELECT response FROM idempotency_keys"));
        assertEquals(
            "010203",
            one(
                sql,
                "SELECT encode(bytes,'hex') FROM attachments WHERE message_id='" + message + "'"));
        assertEquals("2", one(sql, "SELECT data->>'revision' FROM record_history"));
        assertEquals("queued", one(sql, "SELECT state FROM jobs WHERE id='" + job + "'"));
        assertThrows(
            SQLException.class,
            () ->
                sql.executeUpdate(
                    "INSERT INTO records(id,kind,data) VALUES(gen_random_uuid(),'problem','{}')"));
      } finally {
        sql.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }

  private static String one(Statement sql, String query) throws SQLException {
    try (ResultSet result = sql.executeQuery(query)) {
      assertTrue(result.next());
      String value = result.getString(1);
      assertFalse(result.next());
      return value;
    }
  }
}
