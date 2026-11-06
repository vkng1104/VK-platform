package com.vkplatform.iam.verification.application.service;

import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** Runs callbacks directly and records which transaction, if any, each port call ran in. */
final class RecordingTransactions implements TransactionOperations {
    private int started;
    private int committed;
    private int rolledBack;
    private boolean active;

    @Override
    public <T> T execute(TransactionCallback<T> action) {
        started++;
        active = true;
        try {
            T result = action.doInTransaction(new SimpleTransactionStatus());
            committed++;
            return result;
        } catch (RuntimeException | Error failure) {
            rolledBack++;
            throw failure;
        } finally {
            active = false;
        }
    }

    String state() {
        return active ? "in transaction " + started : "outside a transaction";
    }

    int started() {
        return started;
    }

    int committed() {
        return committed;
    }

    int rolledBack() {
        return rolledBack;
    }
}
