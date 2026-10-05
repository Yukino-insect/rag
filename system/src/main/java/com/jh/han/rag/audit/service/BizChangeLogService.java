
package com.jh.han.rag.audit.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jh.han.rag.audit.controller.request.BizChangeLogPageRequest;
import com.jh.han.rag.audit.controller.vo.BizChangeLogVO;

public interface BizChangeLogService {

    IPage<BizChangeLogVO> page(BizChangeLogPageRequest requestParam);

    BizChangeLogVO get(String id);
}
