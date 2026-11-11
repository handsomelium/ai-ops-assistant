package com.ake.aiops.service;

import com.ake.aiops.domain.Engineer;
import com.ake.aiops.domain.view.EngineerView;
import com.ake.aiops.repository.EngineerRepository;
import com.ake.aiops.repository.WorkOrderRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 工程师与派单可用性业务服务。 */
@Service
public class EngineerService {

    private final EngineerRepository engineerRepository;
    private final WorkOrderRepository workOrderRepository;
    private final SkillRules skillRules;

    public EngineerService(EngineerRepository engineerRepository,
                           WorkOrderRepository workOrderRepository,
                           SkillRules skillRules) {
        this.engineerRepository = engineerRepository;
        this.workOrderRepository = workOrderRepository;
        this.skillRules = skillRules;
    }

    public Optional<Engineer> find(String name) {
        return engineerRepository.findByName(name);
    }

    public Engineer require(String name) {
        return engineerRepository.findByName(name).orElseThrow(() ->
                new BusinessRuleException("没有名为「" + name + "」的工程师，请先调用 listEngineers 工具获取在岗名单"));
    }

    public int activeOrdersOf(String engineerName) {
        return workOrderRepository.findByAssignee(engineerName).size();
    }

    /**
     * 按工种列出可用工程师，并按当前负载从低到高排序。
     *
     * @param requiredSkill 目标设备要求的工种，可为 null
     */
    public List<EngineerView> candidates(String requiredSkill) {
        return engineerRepository.findAll().stream()
                .map(e -> new EngineerView(
                        e.getName(), e.getPhone(), List.copyOf(e.getSkills()), e.isOnDuty(),
                        activeOrdersOf(e.getName()),
                        requiredSkill != null && e.hasSkill(requiredSkill)))
                .sorted(Comparator
                        .comparing((EngineerView v) -> !v.matched())
                        .thenComparing(v -> !v.onDuty())
                        .thenComparingInt(EngineerView::activeOrders))
                .toList();
    }

    /** 推荐最合适的一位工程师：工种匹配 + 在岗 + 负载最低。 */
    public Optional<EngineerView> recommend(String requiredSkill) {
        return candidates(requiredSkill).stream()
                .filter(EngineerView::matched)
                .filter(EngineerView::onDuty)
                .filter(v -> v.activeOrders() < skillRules.maxConcurrentOrders())
                .findFirst();
    }
}
