package com.acmeplex.api;

import com.acmeplex.api.notifier.EmailNotifier;
import com.acmeplex.api.notifier.NotificationManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }

    @Bean
    public NotificationManager notifier() {
        NotificationManager notificationManager = new NotificationManager();
        notificationManager.addObserver(new EmailNotifier());
        return notificationManager;
    }

}
