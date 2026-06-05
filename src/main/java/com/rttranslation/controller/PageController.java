package com.rttranslation.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面控制器 - 返回 Thymeleaf 模板
 */
@Controller
public class PageController {

    @GetMapping("/")
    public String index() {
        return "index";
    }
}
