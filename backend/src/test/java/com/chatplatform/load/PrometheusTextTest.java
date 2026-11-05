package com.chatplatform.load;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusTextTest {

    private static final String SCRAPE = """
            # HELP hikaricp_connections_pending Pending threads
            # TYPE hikaricp_connections_pending gauge
            hikaricp_connections_pending{pool="chatplatform-pool"} 3.0
            hikaricp_connections_active{pool="chatplatform-pool"} 10.0
            process_cpu_usage 0.4521
            chat_messages_sent_total{channel="ws",outcome="created"} 120.0
            chat_messages_sent_total{channel="rest",outcome="created"} 5.0
            executor_queued_tasks{name="wsPushExecutor"} 7.0
            """;

    @Test
    void sumsAMetricAcrossItsSeries() {
        PrometheusText p = PrometheusText.parse(SCRAPE);
        assertThat(p.sum("hikaricp_connections_pending")).isEqualTo(3.0);
        assertThat(p.sum("process_cpu_usage")).isEqualTo(0.4521);
        assertThat(p.sum("chat_messages_sent_total")).isEqualTo(125.0);
    }

    @Test
    void filtersBySeriesLabel() {
        PrometheusText p = PrometheusText.parse(SCRAPE);
        assertThat(p.sum("chat_messages_sent_total", "channel=\"ws\"")).isEqualTo(120.0);
        assertThat(p.sum("executor_queued_tasks", "name=\"wsPushExecutor\"")).isEqualTo(7.0);
    }

    @Test
    void missingMetricIsNaNAndAPrefixIsNotAMatch() {
        PrometheusText p = PrometheusText.parse(SCRAPE);
        assertThat(p.sum("nope")).isNaN();
        assertThat(p.sum("hikaricp_connections")).as("whole name only").isNaN();
    }
}
