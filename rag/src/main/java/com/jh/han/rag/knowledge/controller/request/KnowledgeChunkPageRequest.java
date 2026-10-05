
package com.jh.han.rag.knowledge.controller.request;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

@Data
public class KnowledgeChunkPageRequest extends Page {

    private Integer enabled;
}
