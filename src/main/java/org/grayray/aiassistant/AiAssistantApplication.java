package org.grayray.aiassistant;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan({
        "org.grayray.aiassistant.user.mapper",
        "org.grayray.aiassistant.chat.mapper",
        "org.grayray.aiassistant.document.mapper",
        "org.grayray.aiassistant.knowledge.mapper"
})
@EnableAsync
public class AiAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAssistantApplication.class, args);
    }

}
