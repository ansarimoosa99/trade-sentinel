package com.example.tradesentinel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TradeSentinelApplication {
  public static void main(String[] args) {
    SpringApplication.run(TradeSentinelApplication.class, args);
  }
}
