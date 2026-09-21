package org.thesis.research.litreview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.thesis.research.litreview.config.LitreviewProperties;

@SpringBootApplication
@EnableConfigurationProperties(LitreviewProperties.class)
public class SynthesizerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SynthesizerApplication.class, args);
    }

}
