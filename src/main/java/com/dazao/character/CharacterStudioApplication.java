package com.dazao.character;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 独立角色工作室入口，前端与后端共同构成一个仓库。 */
@SpringBootApplication
public class CharacterStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(CharacterStudioApplication.class, args);
    }
}
