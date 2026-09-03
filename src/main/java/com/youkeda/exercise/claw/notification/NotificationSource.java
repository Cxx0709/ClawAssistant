package com.youkeda.exercise.claw.notification;

/**
 * 通知来源接口。
 * 每个通知类型（考试/比赛/动漫/活动/就业）实现此接口。
 * Source 保持纤薄，只做编排：collect → dedup → classify → decide → push。
 */
public interface NotificationSource {

    /** Source 标识，用于日志和配置开关 */
    String getName();

    /** 执行一次检查。Source 内部编排自己的组件 */
    void check();
}
