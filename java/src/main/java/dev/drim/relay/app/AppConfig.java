package dev.drim.relay.app;

import dev.drim.relay.id.IdFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Application-wide beans that are not component-scanned (the id factory's generator id). */
@Configuration
public class AppConfig {
  /**
   * The app's id factory. Generator id 1 is reserved for the running service; the suite's seeding
   * factory and the naive test host use distinct generator ids so their ids never collide.
   */
  @Bean
  public IdFactory idFactory() {
    return new IdFactory((short) 1);
  }
}
