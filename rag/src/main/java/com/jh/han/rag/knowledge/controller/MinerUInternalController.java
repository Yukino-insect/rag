
package com.jh.han.rag.knowledge.controller;

import com.jh.han.rag.core.parser.mineru.MinerUTaskService;
import com.jh.han.rag.framework.convention.Result;
import com.jh.han.rag.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * MinerU Python 服务内部回调入口
 */
@RestController
@RequiredArgsConstructor
public class MinerUInternalController {

    private final MinerUTaskService minerUTaskService;

    @PostMapping(value = "/internal/mineru/callback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Void> callback(@RequestHeader("X-MinerU-Timestamp") String timestamp,
                                 @RequestHeader("X-MinerU-Signature") String signature,
                                 @RequestBody String rawBody) {
        minerUTaskService.handleMineruCallback(rawBody, timestamp, signature);
        return Results.success();
    }
}
