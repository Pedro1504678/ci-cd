package com.example.cicd.app.resources;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloWord {

    @GetMapping("/hello-world")
    public String getMessage() {
        return "Hello World";
    }
}
