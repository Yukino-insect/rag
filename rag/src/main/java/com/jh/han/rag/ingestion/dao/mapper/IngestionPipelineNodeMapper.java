
package com.jh.han.rag.ingestion.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jh.han.rag.ingestion.dao.entity.IngestionPipelineNodeDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface IngestionPipelineNodeMapper extends BaseMapper<IngestionPipelineNodeDO> {

    @Delete("DELETE FROM t_ingestion_pipeline_node WHERE pipeline_id = #{pipelineId}")
    int physicalDeleteByPipelineId(@Param("pipelineId") String pipelineId);
}
