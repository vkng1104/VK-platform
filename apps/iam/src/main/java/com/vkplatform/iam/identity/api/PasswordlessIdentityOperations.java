package com.vkplatform.iam.identity.api;

import com.vkplatform.iam.identity.api.command.AccountAdministrationCommand;
import com.vkplatform.iam.identity.api.command.ConsumeSignInGrant;
import com.vkplatform.iam.identity.api.command.StartPasswordlessSignIn;
import com.vkplatform.iam.identity.api.command.VerifyPasswordlessSignIn;
import com.vkplatform.iam.identity.api.result.AccountSummary;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.identity.api.result.AuthenticationChallengeStarted;
import com.vkplatform.iam.identity.api.result.AuthenticationGrantIssued;

import java.util.UUID;

public interface PasswordlessIdentityOperations {
    AuthenticationChallengeStarted startSignIn(StartPasswordlessSignIn command);

    AuthenticationGrantIssued verifySignIn(VerifyPasswordlessSignIn command);

    AuthenticatedAccount consumeSignInGrant(ConsumeSignInGrant command);

    AuthenticatedAccount activeAccount(UUID accountId);

    AccountSummary account(UUID accountId);

    AccountSummary suspendAccount(AccountAdministrationCommand command);

    AccountSummary reactivateAccount(AccountAdministrationCommand command);

    AccountSummary deleteAccount(AccountAdministrationCommand command);
}
