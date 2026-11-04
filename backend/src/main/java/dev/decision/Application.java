package dev.decision;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Application {
  public static void main(String[] args) {
    if (args.length == 4 && args[0].equals("--parse-attachment")) {
      DocumentParser.child(args[1], args[2], args[3]);
      return;
    }
    SpringApplication.run(Application.class, args);
  }
}
