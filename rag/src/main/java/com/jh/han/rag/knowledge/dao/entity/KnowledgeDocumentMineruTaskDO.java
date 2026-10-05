
package com.jh.han.rag.knowledge.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档 MinerU 异步解析任务实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_knowledge_document_mineru_task")
public class KnowledgeDocumentMineruTaskDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 文档 ID
     */
    private String docId;

    /**
     * Python 服务返回的任务 ID
     */
    private String mineruTaskId;

    /**
     * Python 解析 requestId
     */
    private String requestId;

    /**
     * 任务状态
     */
    private String status;

    /**
     * 触发任务的操作人
     */
    private String operator;

    /**
     * Python 侧解析耗时（毫秒）
     */
    private Long parseDuration;

    /**
     * 回调上报的错误信息
     */
    private String errorMessage;

    /**
     * 提交时间
     */
    private Date submittedAt;

    /**
     * 回调到达时间
     */
    private Date callbackAt;

    /**
     * 整体完成时间
     */
    private Date completedAt;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}
