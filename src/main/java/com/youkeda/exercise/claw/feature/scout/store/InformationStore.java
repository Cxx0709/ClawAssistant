package com.youkeda.exercise.claw.feature.scout.store;

import com.youkeda.exercise.claw.feature.scout.processor.InformationItem;

import java.util.List;

/**
 * 信息存储接口
 */
public interface InformationStore {

    /**
     * 批量保存信息
     */
    void batchSave(List<InformationItem> items);

    /**
     * 向量检索
     */
    List<InformationItem> searchByVector(float[] vector, int topK);

    /**
     * 获取最近的信息
     */
    List<InformationItem> getRecent(int limit);

    /**
     * 删除过期信息
     */
    void deleteExpired(long beforeTimestamp);
}
