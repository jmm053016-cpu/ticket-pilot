package com.example.aiticketassistant.interfaces.rest;

import com.example.aiticketassistant.application.prompt.PromptCatalog;
import com.example.aiticketassistant.application.prompt.PromptReloadResult;
import com.example.aiticketassistant.application.prompt.PromptSourceInfo;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提示词热加载的运维入口（见 §5.4.4）。
 *
 * <p>用途：改完提示词**不用重启**——自动重载（按文件指纹）之外，还可以手动立即生效，
 * 并当场看到"读的是哪个文件、上一条错误是什么"。
 *
 * <p>⚠️ **本项目没有鉴权**（§11.1 第 1 条），所以这两个接口对任何能访问后端的人开放：
 * reload 只是重读本地文件、不执行外部输入，风险面很小，但正式环境应把它放在内网或关掉热加载
 * （{@code PROMPTS_HOT_RELOAD=false} 时 {@code info()} 仍可用，reload 仍会重读文件，只是不再自动）。
 */
@RestController
@RequestMapping("/admin/prompts")
public class PromptAdminController {
    private final PromptCatalog promptCatalog;

    public PromptAdminController(PromptCatalog promptCatalog) {
        this.promptCatalog = promptCatalog;
    }

    /** 当前各提示词的来源与最近一次重载错误。 */
    @GetMapping
    public List<PromptSourceInfo> list() {
        return promptCatalog.info();
    }

    /** 立即重新加载全部提示词；失败时返回 {@code success=false} 与原因，运行中的对话继续用上一版。 */
    @PostMapping("/reload")
    public PromptReloadResult reload() {
        return promptCatalog.reload();
    }
}
