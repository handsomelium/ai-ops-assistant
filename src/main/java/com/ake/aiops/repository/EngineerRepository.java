package com.ake.aiops.repository;

import com.ake.aiops.domain.Engineer;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 工程师仓库（内存实现）。 */
@Repository
public class EngineerRepository {

    private final Map<String, Engineer> store = new LinkedHashMap<>();

    public EngineerRepository() {
        save(new Engineer("张建国", "1380-0001", "强电", "配电", "照明"));
        save(new Engineer("李伟", "1380-0002", "暖通", "空调", "冷水机组"));
        save(new Engineer("王芳", "1380-0003", "电梯", "特种设备"));
        save(new Engineer("陈明", "1380-0004", "弱电", "安防", "网络"));
        save(new Engineer("刘洋", "1380-0005", "给排水", "水泵"));
        save(new Engineer("赵磊", "1380-0006", "暖通", "强电"));
    }

    public void save(Engineer engineer) {
        store.put(engineer.getName(), engineer);
    }

    public Optional<Engineer> findByName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(store.get(name.trim()));
    }

    public Collection<Engineer> findAll() {
        return store.values();
    }

    /** 按技能关键字模糊匹配（技能标签之间是"或"的关系）。 */
    public Collection<Engineer> findBySkill(String skillKeyword) {
        if (skillKeyword == null || skillKeyword.isBlank()) {
            return findAll();
        }
        String key = skillKeyword.trim();
        return store.values().stream().filter(e -> e.getSkills().stream().anyMatch(s -> s.contains(key))).toList();
    }
}
