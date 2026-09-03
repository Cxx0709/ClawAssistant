package com.youkeda.exercise.claw.skill;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    private final Map<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, WorkflowWorker> workers = new ConcurrentHashMap<>();
    private final SkillsProperties properties;
    private final ApplicationContext applicationContext;

    public WorkflowRegistry(SkillsProperties properties, ApplicationContext applicationContext) {
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        // Register all WorkflowWorker beans from context
        Map<String, WorkflowWorker> workerBeans = applicationContext.getBeansOfType(WorkflowWorker.class);
        workerBeans.values().forEach(w -> workers.put(w.getName(), w));

        // Validate workflow definitions
        properties.getWorkflows().forEach((name, def) -> {
            if (!workers.containsKey(def.workerName())) {
                throw new IllegalStateException(
                    "Workflow [" + name + "] references unknown worker: " + def.workerName());
            }
            definitions.put(name, def);
            log.info("Workflow [{}] -> worker [{}]", name, def.workerName());
        });

        // Validate skill-workflow bindings
        properties.getSkillWorkflowBindings().forEach((skillName, workflowName) -> {
            if (!definitions.containsKey(workflowName)) {
                throw new IllegalStateException(
                    "Skill [" + skillName + "] references unknown workflow: " + workflowName);
            }
        });
    }

    public Optional<WorkflowDefinition> find(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    public Optional<WorkflowWorker> getWorker(String workflowName) {
        return Optional.ofNullable(definitions.get(workflowName))
                .map(def -> workers.get(def.workerName()));
    }

    public Collection<WorkflowDefinition> getAll() {
        return Collections.unmodifiableCollection(definitions.values());
    }
}
