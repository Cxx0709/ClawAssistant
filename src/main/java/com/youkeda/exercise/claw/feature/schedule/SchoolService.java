package com.youkeda.exercise.claw.feature.schedule;

import com.youkeda.exercise.claw.domain.schedule.SchoolEntity;
import com.youkeda.exercise.claw.identity.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 学校作息管理服务
 *
 * <p>以学校为核心管理作息配置，用户通过 schoolId 绑定学校。
 * 核心链路：
 * <pre>
 *   userId → UserProfileRepository.getSchoolId(userId) → SchoolScheduleConfigRepository → 作息
 * </pre>
 *
 * <p>主要功能：
 * <ul>
 *   <li>学校 CRUD（创建、查询、删除）</li>
 *   <li>用户-学校绑定（查询、设置）</li>
 *   <li>按用户或学校查询作息配置</li>
 *   <li>预置学校模板</li>
 * </ul>
 */
@Service
public class SchoolService {

    private static final Logger log = LoggerFactory.getLogger(SchoolService.class);

    /** 预置学校作息模板：学校名 -> 每节课 [startTime, endTime] */
    private static final java.util.Map<String, String[][]> PRESET_SCHOOLS = java.util.Map.of(
            "Default University", new String[][]{
                    {}, {"08:00", "08:45"}, {"08:55", "09:40"}, {"10:10", "10:55"}, {"11:05", "11:50"},
                    {"13:30", "14:15"}, {"14:25", "15:10"}, {"15:40", "16:25"}, {"16:35", "17:20"},
                    {"17:30", "18:15"}, {"19:00", "19:45"}, {"19:55", "20:40"}, {"20:50", "21:35"}
            },
            "无锡学院", new String[][]{
                    {}, {"08:00", "08:45"}, {"08:55", "09:40"}, {"10:10", "10:55"}, {"11:05", "11:50"},
                    {"13:30", "14:15"}, {"14:25", "15:10"}, {"15:40", "16:25"}, {"16:35", "17:20"},
                    {"17:30", "18:15"}, {"19:00", "19:45"}, {"19:55", "20:40"}, {"20:50", "21:35"}
            },
            "XX大学（50分钟课时）", new String[][]{
                    {}, {"08:00", "08:50"}, {"09:00", "09:50"}, {"10:20", "11:10"}, {"11:20", "12:10"},
                    {"14:00", "14:50"}, {"15:00", "15:50"}, {"16:20", "17:10"}, {"17:20", "18:10"},
                    {"19:00", "19:50"}, {"20:00", "20:50"}, {"21:00", "21:50"}, {}
            }
    );

    private final SchoolScheduleConfigRepository repository;
    private final UserProfileRepository userProfiles;

    public SchoolService(SchoolScheduleConfigRepository repository,
                         UserProfileRepository userProfiles) {
        this.repository = repository;
        this.userProfiles = userProfiles;
    }

    // ==================== 用户-学校绑定 ====================

    /**
     * 判断用户是否已绑定学校
     */
    public boolean isUserBoundToSchool(String userId) {
        return userProfiles.getSchoolId(userId) != null;
    }

    /**
     * 获取用户绑定的学校 ID
     *
     * @param userId 用户标识
     * @return 学校 ID，未绑定返回 null
     */
    public Long getUserSchoolId(String userId) {
        return userProfiles.getSchoolId(userId);
    }

    /**
     * 获取用户绑定的学校实体
     *
     * @param userId 用户标识
     * @return 学校实体，未绑定返回 null
     */
    public SchoolEntity getUserSchool(String userId) {
        Long schoolId = userProfiles.getSchoolId(userId);
        if (schoolId == null) return null;
        return repository.findSchoolById(schoolId);
    }

    /**
     * 绑定用户到指定学校
     *
     * @param userId   用户标识
     * @param schoolId 学校 ID
     */
    public void bindUserToSchool(String userId, Long schoolId) {
        if (schoolId == null) {
            log.warn("绑定学校失败：schoolId 为空 | userId={}", userId);
            return;
        }
        userProfiles.setSchoolId(userId, schoolId);
        log.info("用户绑定学校 | userId={} | schoolId={}", userId, schoolId);
    }

    /**
     * 解绑用户的学校
     *
     * @param userId 用户标识
     */
    public void unbindUserSchool(String userId) {
        userProfiles.setSchoolId(userId, null);
        log.info("用户解绑学校 | userId={}", userId);
    }

    // ==================== 学校查询 ====================

    /**
     * 获取用户当前学校的名称
     *
     * @param userId 用户标识
     * @return 学校名称，未绑定返回 null
     */
    public String getCurrentSchoolName(String userId) {
        SchoolEntity school = getUserSchool(userId);
        return school != null ? school.getSchoolName() : null;
    }

    /**
     * 根据用户获取学校作息配置
     *
     * <p>自动解析 userId → schoolId，未绑定时返回 null。
     *
     * @param userId 用户标识
     * @return 节次配置列表（按 periodNumber 排序）
     */
    public List<SchoolScheduleConfig> getScheduleByUser(String userId) {
        Long schoolId = userProfiles.getSchoolId(userId);
        if (schoolId == null) return List.of();
        return repository.findBySchoolId(schoolId);
    }

    /**
     * 根据用户获取课间配置
     *
     * @param userId 用户标识
     * @return 课间配置列表
     */
    public List<BreakConfig> getBreaksByUser(String userId) {
        Long schoolId = userProfiles.getSchoolId(userId);
        if (schoolId == null) return List.of();
        return repository.findBreaksBySchoolId(schoolId);
    }

    /**
     * 根据学校 ID 获取作息配置
     */
    public List<SchoolScheduleConfig> getScheduleBySchool(Long schoolId) {
        if (schoolId == null) return List.of();
        return repository.findBySchoolId(schoolId);
    }

    /**
     * 根据学校 ID 获取课间配置
     */
    public List<BreakConfig> getBreaksBySchool(Long schoolId) {
        if (schoolId == null) return List.of();
        return repository.findBreaksBySchoolId(schoolId);
    }

    /**
     * 获取学校实体
     */
    public SchoolEntity getSchoolById(Long schoolId) {
        if (schoolId == null) return null;
        return repository.findSchoolById(schoolId);
    }

    /**
     * 获取所有学校列表
     */
    public List<SchoolEntity> listAllSchools() {
        return repository.findAllSchools();
    }

    /**
     * 获取默认学校
     */
    public SchoolEntity getDefaultSchool() {
        return repository.findDefaultSchool();
    }

    // ==================== 学校创建与管理 ====================

    /**
     * 创建新学校（含默认作息配置）
     *
     * <p>如果 schoolName 匹配预置模板，自动使用预置作息；
     * 否则使用系统默认作息。
     *
     * @param schoolName 学校名称
     * @param schoolCode 学校编码（可选）
     * @return 创建的学校实体
     */
    public SchoolEntity createSchool(String schoolName, String schoolCode) {
        // 检查是否已存在同名学校
        SchoolEntity existing = repository.findSchoolByName(schoolName);
        if (existing != null) {
            log.info("学校已存在，直接返回 | id={} | name={}", existing.getId(), schoolName);
            return existing;
        }

        // 创建学校
        SchoolEntity school = new SchoolEntity(schoolName, schoolCode);
        school = repository.createSchool(school);

        // 检查是否有预置模板
        String[][] periodTimes = PRESET_SCHOOLS.get(schoolName);
        if (periodTimes != null) {
            // 使用预置模板作息
            List<SchoolScheduleConfig> configs = new ArrayList<>();
            for (int p = 1; p < periodTimes.length; p++) {
                if (periodTimes[p].length == 2) {
                    configs.add(new SchoolScheduleConfig(school.getId(), p,
                            periodTimes[p][0], periodTimes[p][1], null));
                }
            }
            repository.replaceAllSchedule(school.getId(), configs);
            log.info("学校使用预置作息模板 | school={} | periods={}", schoolName, configs.size());
        } else {
            // 使用默认作息
            repository.initSchoolSchedule(school.getId());
        }

        return school;
    }

    /**
     * 绑定用户到指定学校（按学校名称查找或创建）
     *
     * <p>如果学校不存在，自动创建。
     *
     * @param userId     用户标识
     * @param schoolName 学校名称
     * @return 绑定的学校实体
     */
    public SchoolEntity bindUserToSchoolByName(String userId, String schoolName) {
        if (schoolName == null || schoolName.isBlank()) return null;

        SchoolEntity school = repository.findSchoolByName(schoolName);
        if (school == null) {
            // 自动创建学校（含作息配置）
            school = createSchool(schoolName, null);
        }

        bindUserToSchool(userId, school.getId());
        return school;
    }

    /**
     * 自定义学校作息配置（覆盖写入）
     */
    public void customizeSchoolSchedule(Long schoolId, List<SchoolScheduleConfig> configs,
                                        List<BreakConfig> breaks) {
        if (configs != null && !configs.isEmpty()) {
            repository.replaceAllSchedule(schoolId, configs);
        }
        if (breaks != null && !breaks.isEmpty()) {
            repository.replaceAllBreaks(schoolId, breaks);
        }
        log.info("学校作息配置已自定义 | schoolId={}", schoolId);
    }

    /**
     * 删除学校（含作息和课间配置）
     */
    public void deleteSchool(Long schoolId) {
        repository.deleteSchool(schoolId);
    }

    // ==================== 预置学校 ====================

    /**
     * 获取所有预置学校名称列表
     */
    public List<String> listPresetSchoolNames() {
        return List.copyOf(PRESET_SCHOOLS.keySet());
    }
}
