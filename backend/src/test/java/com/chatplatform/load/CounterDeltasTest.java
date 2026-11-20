package com.chatplatform.load;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Two-instance runs (base2): the stored rate the classifier uses must count both instances' messages. */
class CounterDeltasTest {

    private static PrometheusText scrape(double created, double ackCount, double ackSumSeconds) {
        return PrometheusText.parse("""
                chat_messages_sent_total{channel="ws",outcome="created"} %s
                chat_ws_send_ack_seconds_count %s
                chat_ws_send_ack_seconds_sum %s
                """.formatted(created, ackCount, ackSumSeconds));
    }

    @Test
    void oneInstance() {
        var m = LoadMain.counterDeltas(List.of(scrape(100, 100, 1)), List.of(scrape(400, 400, 4)));
        assertThat(m.get("messagesCreated")).isEqualTo(300.0);
        assertThat(m.get("serverAckMeanMs")).isEqualTo(10.0);
    }

    @Test
    void twoInstancesAreSummed() {
        var m = LoadMain.counterDeltas(
                List.of(scrape(100, 100, 1), scrape(50, 50, 0.5)),
                List.of(scrape(250, 250, 2.5), scrape(200, 200, 3.5)));
        assertThat(m.get("messagesCreated")).isEqualTo(300.0);            // 150 + 150, not one instance's 150
        assertThat(m.get("serverAckMeanMs")).isEqualTo(15.0);             // (1.5 s + 3 s) / 300 acks
    }

    @Test
    void aMissingScrapeGivesNoDeltas() {
        assertThat(LoadMain.counterDeltas(List.of(scrape(1, 1, 1)), java.util.Arrays.asList((PrometheusText) null)))
                .isEmpty();
    }
}
