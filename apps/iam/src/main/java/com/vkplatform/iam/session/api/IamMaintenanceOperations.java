package com.vkplatform.iam.session.api;

import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.result.IamRetentionResult;

public interface IamMaintenanceOperations {
    IamRetentionResult cleanup(OperatorContext context);
}
