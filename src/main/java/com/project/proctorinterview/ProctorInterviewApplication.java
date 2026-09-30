package com.project.proctorinterview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
// Enables the interview expiry sweep (InterviewExpirySweeper), which closes
// interviews abandoned past their deadline. The only scheduled work in the app.
@EnableScheduling
// Only for sending candidate email off the request thread - see
// SchedulingMailListener. Nothing else in the app is @Async, and an interview
// never waits on it.
@EnableAsync
public class ProctorInterviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProctorInterviewApplication.class, args);
    }
}
