package com.copenhagen.pay.payment.reconciliation;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayWorkerTest {

    @Test
    void runBatch_emptyQueue_stopsImmediately() {
        ReconciliationService service = mock(ReconciliationService.class);
        new PayWorker(service, 10).runBatch();
        verify(service).processNext();
    }

    @Test
    void runBatch_fullQueue_respectsBatchLimit() {
        ReconciliationService service = mock(ReconciliationService.class);
        when(service.processNext()).thenReturn(true);
        new PayWorker(service, 3).runBatch();
        verify(service, times(3)).processNext();
    }

    @Test
    void runBatch_oneFailure_continuesWithRemainingJobs() {
        ReconciliationService service = mock(ReconciliationService.class);
        when(service.processNext()).thenThrow(new IllegalStateException("simulated failure")).thenReturn(false);
        new PayWorker(service, 3).runBatch();
        verify(service, times(2)).processNext();
    }
}
