package com.discord.gateway.integration;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

public class DockerAvailableCondition implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        boolean isAvailable = false;
        try {
            isAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception | Error e) {
            // Ignore any failures during docker client resolution
        }
        
        if (isAvailable) {
            return ConditionEvaluationResult.enabled("Docker is available");
        } else {
            return ConditionEvaluationResult.disabled("Docker daemon is not running or accessible");
        }
    }
}