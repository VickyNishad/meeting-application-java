package com.example.meetplatform.common.persistence;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers side effects (media-server calls) until the surrounding transaction has committed, so they never run
 * while row locks and a database connection are held, and never for a change that was rolled back.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    /** Runs {@code action} after commit, or immediately when no transaction is active. */
    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
