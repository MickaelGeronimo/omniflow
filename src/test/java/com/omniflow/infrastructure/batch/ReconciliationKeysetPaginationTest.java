package com.omniflow.infrastructure.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniflow.application.port.out.LedgerRepositoryPort;
import com.omniflow.application.port.out.S3StoragePort;
import com.omniflow.domain.ledger.JournalEntry;
import com.omniflow.domain.ledger.PostingLeg;
import com.omniflow.domain.model.AccountId;
import com.omniflow.domain.model.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.item.ItemReader;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class ReconciliationKeysetPaginationTest {

    private LedgerRepositoryPort ledgerRepository;
    private ReconciliationBatchLauncher launcher;

    @BeforeEach
    void setUp() {
        ledgerRepository = Mockito.mock(LedgerRepositoryPort.class);
        launcher = new ReconciliationBatchLauncher(
                Mockito.mock(JobLauncher.class),
                Mockito.mock(Job.class),
                ledgerRepository,
                Mockito.mock(S3StoragePort.class),
                new ObjectMapper(),
                "test-bucket"
        );
    }

    private JournalEntry createBalancedEntry(String entryId, String refId, Instant timestamp) {
        AccountId debtor = AccountId.of("OMNI", "0001", "D-" + entryId);
        AccountId creditor = AccountId.of("OMNI", "0001", "C-" + entryId);
        PostingLeg leg1 = PostingLeg.debit(debtor, Money.usd("100.00"), "Debit " + entryId);
        PostingLeg leg2 = PostingLeg.credit(creditor, Money.usd("100.00"), "Credit " + entryId);
        return new JournalEntry(entryId, refId, timestamp, "Memo " + entryId, List.of(leg1, leg2));
    }

    @Test
    @DisplayName("Should sequentially advance lastEntryId cursor across keyset pages until EOF")
    void shouldAdvanceKeysetCursorAcrossPagesUntilEof() throws Exception {
        Instant cutoff = Instant.parse("2026-09-11T23:00:00Z");

        JournalEntry entry1 = createBalancedEntry("JRN-0001", "REF-1", cutoff.minusSeconds(30));
        JournalEntry entry2 = createBalancedEntry("JRN-0002", "REF-2", cutoff.minusSeconds(20));
        JournalEntry entry3 = createBalancedEntry("JRN-0003", "REF-3", cutoff.minusSeconds(10));
        JournalEntry entry4 = createBalancedEntry("JRN-0004", "REF-4", cutoff.minusSeconds(5));

        // First page (lastEntryId is null) returns JRN-0001 and JRN-0002
        when(ledgerRepository.findJournalEntriesKeyset(isNull(), eq(cutoff), eq(100)))
                .thenReturn(List.of(entry1, entry2));

        // Second page (lastEntryId is JRN-0002) returns JRN-0003 and JRN-0004
        when(ledgerRepository.findJournalEntriesKeyset(eq("JRN-0002"), eq(cutoff), eq(100)))
                .thenReturn(List.of(entry3, entry4));

        // Third page (lastEntryId is JRN-0004) returns empty list (EOF)
        when(ledgerRepository.findJournalEntriesKeyset(eq("JRN-0004"), eq(cutoff), eq(100)))
                .thenReturn(Collections.emptyList());

        ItemReader<JournalEntry> reader = launcher.ledgerItemReader(cutoff.toString());

        List<JournalEntry> readEntries = new ArrayList<>();
        JournalEntry item;
        while ((item = reader.read()) != null) {
            readEntries.add(item);
        }

        // Verify all 4 items read in exact order
        assertThat(readEntries).containsExactly(entry1, entry2, entry3, entry4);

        // Verify cursor progression
        ArgumentCaptor<String> cursorCaptor = ArgumentCaptor.forClass(String.class);
        verify(ledgerRepository, times(3)).findJournalEntriesKeyset(cursorCaptor.capture(), eq(cutoff), eq(100));

        List<String> capturedCursors = cursorCaptor.getAllValues();
        assertThat(capturedCursors.get(0)).isNull();
        assertThat(capturedCursors.get(1)).isEqualTo("JRN-0002");
        assertThat(capturedCursors.get(2)).isEqualTo("JRN-0004");

        // Further reads after EOF continue returning null without calling repository
        assertThat(reader.read()).isNull();
        verifyNoMoreInteractions(ledgerRepository);
    }
}
