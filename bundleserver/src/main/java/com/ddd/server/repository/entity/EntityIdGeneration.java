package com.ddd.server.repository.entity;

import org.springframework.context.annotation.Configuration;

@Configuration
public class EntityIdGeneration {

    //  @Bean
    //  BeforeConvertCallback<LargestAduIdReceived> beforeConvertCallbackLargestAduIdReceived() {
    //    return (d) -> {
    //      if (d.getId() == null) {
    //        d.setId(UUID.randomUUID().toString());
    //      }
    //      return d;
    //    };
    //  }
    //
    //  @Bean
    //  BeforeConvertCallback<LargestAduIdDelivered> beforeConvertCallbackLargestAduIdDelivered() {
    //    return (d) -> {
    //      if (d.getId() == null) {
    //        d.setId(UUID.randomUUID().toString());
    //      }
    //      return d;
    //    };
    //  }
    //
    //  @Bean
    //  BeforeConvertCallback<SentAduDetails> beforeConvertCallbackSentAduDetails() {
    //    return (d) -> {
    //      if (d.getId() == null) {
    //        d.setId(UUID.randomUUID().toString());
    //      }
    //      return d;
    //    };
    //  }
}
