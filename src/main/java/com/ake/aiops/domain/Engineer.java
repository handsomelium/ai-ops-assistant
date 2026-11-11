package com.ake.aiops.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/** 维修工程师。技能标签用于派单时的匹配校验。 */
public class Engineer {

    private final String name;
    private final String phone;
    private final Set<String> skills = new LinkedHashSet<>();
    /** 当前在岗 */
    private boolean onDuty = true;

    public Engineer(String name, String phone, String... skills) {
        this.name = name;
        this.phone = phone;
        this.skills.addAll(Set.of(skills));
    }

    public boolean hasSkill(String skill) {
        return skills.stream().anyMatch(s -> s.equalsIgnoreCase(skill));
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public Set<String> getSkills() {
        return skills;
    }

    public boolean isOnDuty() {
        return onDuty;
    }

    public void setOnDuty(boolean onDuty) {
        this.onDuty = onDuty;
    }
}
