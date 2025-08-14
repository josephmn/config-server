package api.azure.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Main class for the Config Server application.
 * This class serves as the entry point for the Spring Boot application.
 * It loads environment variables from a .env file using the Dotenv library.
 *
 * @author Joseph Magallanes
 * @since 2025-08-02
 */
@SpringBootApplication
@EnableConfigServer
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
