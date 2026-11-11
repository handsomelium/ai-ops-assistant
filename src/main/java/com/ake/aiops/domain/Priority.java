package com.ake.aiops.domain;

/** 工单优先级。 */
public enum Priority {

    HIGH("高", 3),
    MEDIUM("中", 2),
    LOW("低", 1);

    private final String label;
    private final int weight;

    Priority(String label, int weight) {
        this.label = label;
        this.weight = weight;
    }

    public String getLabel() {
        return label;
    }

    public int getWeight() {
        return weight;
    }
}
